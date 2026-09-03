package com.familyremote.app.parent

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import com.familyremote.app.MainActivity
import com.familyremote.app.R
import com.familyremote.app.common.AccessibilityPermissionMonitor
import com.familyremote.app.common.Constants
import com.familyremote.app.net.AccessibilityStatusPayload
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.StatusPayload
import com.familyremote.app.net.VideoFrameMessage
import com.familyremote.app.pairing.AuthorizedChild
import com.familyremote.app.pairing.PairingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The single long-lived foreground service for an active sharing session: owns the
 * `MediaProjection` object, `VirtualDisplay`, encoder, per-child network session connection(s),
 * wake lock, and reconnect logic. See docs/plans/screen-sharing-core/PLAN.md §2.
 * Foreground service type: `mediaProjection` (declared in the manifest).
 */
class ScreenShareForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var wakeLockController: ParentWakeLockController
    private lateinit var sessionStateStore: SessionStateStore
    private lateinit var keepAliveScheduler: SessionKeepAliveScheduler
    private lateinit var statusMonitor: ParentStatusMonitor
    private lateinit var displayManager: DisplayManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenCaptureEncoder? = null

    private var captureWidth = 0
    private var captureHeight = 0

    private var displayListenerRegistered = false

    /**
     * Screen-rotation handling (docs/plans/screen-rotation-handling/PLAN.md §2): fires reliably on
     * rotation independent of any Activity lifecycle (this Service has no Activity to hang a
     * `Configuration` callback off during background operation). Filters to the default (real,
     * physical) display -- `onDisplayChanged` also fires for the `VirtualDisplay` this service
     * itself owns (e.g. as a side effect of [reconfigureCaptureForDisplayChange] below), which must
     * not be treated as a fresh rotation to react to again.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDefaultDisplayChanged()
        }
    }

    private val sessionServersByChild = mutableMapOf<UUID, ParentSessionServer>()
    private var expectedChildCount = 0
    private var endedChildCount = 0

    /** Last value broadcast by [startAccessibilityStatusTicker] -- `null` until the first check
     * runs, so that check's result is always sent even if it happens to read `false`. */
    private var lastKnownAccessibilityGranted: Boolean? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Fires identically whether the app or the OS status-bar control initiated the stop
            // (detailed-spec §5) -- no special-case branch needed.
            stopSession("os_stop")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        activeInstance = this
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        wakeLockController = ParentWakeLockController(this)
        sessionStateStore = SessionStateStore(this)
        keepAliveScheduler = SessionKeepAliveScheduler(this)
        statusMonitor = ParentStatusMonitor(this) { payload, isEvent -> broadcastStatus(payload, isEvent) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("parent_stop")
            return START_NOT_STICKY
        }

        if (mediaProjection != null) {
            // Already capturing -- duplicate trampoline call while running, or a benign
            // Service-only restart with the process surviving (PLAN.md §10 Case 1). Nothing new
            // to do; the notification is already visible.
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtraCompat(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            handleNoValidGrantAtStart()
            return START_NOT_STICKY
        }

        startCaptureSession(resultCode, resultData)
        return START_STICKY
    }

    private fun startCaptureSession(resultCode: Int, resultData: Intent) {
        // Must call startForeground() with the mediaProjection type before obtaining the
        // MediaProjection itself (required by the OS on newer Android versions).
        startForeground(NOTIFICATION_ID, buildActiveNotification())

        val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            // Defensive: resultCode == RESULT_OK and resultData != null were already validated
            // by the caller, but the platform API's return type is nullable.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val dpi = metrics.densityDpi

        val newEncoder = ScreenCaptureEncoder(onNalUnit = { data, presentationTimeUs ->
            serviceScope.launch(Dispatchers.IO) { broadcastVideoFrame(data, presentationTimeUs) }
        })
        newEncoder.start(captureWidth, captureHeight)
        encoder = newEncoder

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureWidth,
            captureHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newEncoder.inputSurface,
            null,
            null
        )

        wakeLockController.acquire()
        sessionStateStore.markActive()
        keepAliveScheduler.schedule()
        registerDisplayListener()
        dialAuthorizedChildren()
        // Started alongside this service per docs/plans/connection-status-and-resilience/PLAN.md
        // §5 -- hands its payloads off to whichever ParentSessionServer instances are connected
        // at broadcast time (mirrors broadcastVideoFrame's fan-out below).
        statusMonitor.start(serviceScope)
        startAccessibilityStatusTicker()
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayManager.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
    }

    /**
     * Screen-rotation handling (docs/plans/screen-rotation-handling/PLAN.md §2): the default
     * display's dimensions changed (e.g. the parent physically rotated their device). Compares
     * against the currently configured capture dimensions and reconfigures the capture pipeline in
     * place if they differ -- width/height end up swapped for a 90deg/270deg rotation, so a plain
     * inequality check (rather than any orientation-specific branching) is enough to cover both
     * portrait->landscape and landscape->portrait symmetrically (detailed-spec §12's "applies
     * symmetrically" requirement).
     */
    private fun onDefaultDisplayChanged() {
        if (mediaProjection == null || virtualDisplay == null) return // no active capture to reconfigure.
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        if (metrics.widthPixels == captureWidth && metrics.heightPixels == captureHeight) return
        reconfigureCaptureForDisplayChange(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /**
     * In-session capture reconfiguration -- does not stop/restart `MediaProjection` or touch the
     * network session (docs/plans/screen-rotation-handling/PLAN.md §2/§5). A fresh
     * [ScreenCaptureEncoder]/`MediaCodec` instance is created for the new dimensions on every API
     * level (simpler to reason about than in-place `MediaCodec` reconfigure, and necessary so the
     * encoded output -- and therefore the `VIDEO_FRAME` header the child relies on to detect the
     * change, docs/plans/README.md §4 -- actually reflects the new dimensions rather than staying
     * at the old encode resolution while only the `VirtualDisplay`'s logical size changes). The new
     * encoder is started and handed to the display *before* the old encoder is torn down, to keep
     * the capture gap as short as possible (detailed-spec §12/§5's continuity requirement).
     *
     * API tiers only differ in how the `VirtualDisplay` itself picks up the new encoder surface:
     * `VirtualDisplay.resize()` (API 31+) is an in-place resize of the existing `VirtualDisplay`
     * (same object, same `MediaProjection` grant); API 26-30 has no `resize()` API, so the
     * `VirtualDisplay` itself is released and recreated via the same `MediaProjection` grant
     * (`createVirtualDisplay` is a normal, repeatable operation on an already-granted
     * `MediaProjection`, not equivalent to Decision #1's one-time consent dialog).
     */
    private fun reconfigureCaptureForDisplayChange(newWidth: Int, newHeight: Int, dpi: Int) {
        val projection = mediaProjection ?: return
        try {
            val newEncoder = ScreenCaptureEncoder(onNalUnit = { data, presentationTimeUs ->
                serviceScope.launch(Dispatchers.IO) { broadcastVideoFrame(data, presentationTimeUs) }
            })
            newEncoder.start(newWidth, newHeight)

            val oldVirtualDisplay = virtualDisplay
            virtualDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && oldVirtualDisplay != null) {
                oldVirtualDisplay.resize(newWidth, newHeight, dpi)
                oldVirtualDisplay.surface = newEncoder.inputSurface
                oldVirtualDisplay
            } else {
                oldVirtualDisplay?.release()
                projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    newWidth,
                    newHeight,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newEncoder.inputSurface,
                    null,
                    null
                )
            }

            encoder?.stop()
            encoder = newEncoder
            captureWidth = newWidth
            captureHeight = newHeight
        } catch (e: Exception) {
            // Reconfiguration failed (e.g. transient codec resource contention) -- leave whatever
            // was already running in place rather than tearing down a working session over a failed
            // rotation reconfigure. The next onDisplayChanged (e.g. rotating back) retries.
        }
    }

    /**
     * Periodic check (docs/plans/remote-input-control/PLAN.md §7, detailed-spec §11): every
     * [Constants.ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS] while a session is Live, broadcasts
     * `ACCESSIBILITY_STATUS {granted}` to every connected child whenever the value *changes*
     * (not every tick) -- the pre-flight check in `ParentAccessibilityService` already catches
     * revocation on every actual input attempt; this only surfaces it during idle stretches.
     */
    private fun startAccessibilityStatusTicker() {
        lastKnownAccessibilityGranted = null
        serviceScope.launch {
            while (currentCoroutineContext().isActive) {
                val granted = AccessibilityPermissionMonitor.isGranted(this@ScreenShareForegroundService)
                if (granted != lastKnownAccessibilityGranted) {
                    lastKnownAccessibilityGranted = granted
                    broadcastFrame(AccessibilityStatusPayload(granted).toFrame())
                }
                delay(Constants.ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun dialAuthorizedChildren() {
        val children = PairingRepository(applicationContext).getAuthorizedChildren()
        expectedChildCount = children.size
        endedChildCount = 0
        if (children.isEmpty()) return // nothing to dial -- capture still runs, harmlessly idle.
        children.forEach { child -> serviceScope.launch { runSessionLoop(child) } }
    }

    /**
     * Dials [child], retrying with exponential backoff (1s, 2s, 4s... capped at 10s) for up to
     * [Constants.RECONNECT_WINDOW_MS] -- covers both the initial dial (PLAN.md §6 step 4) and a
     * later reconnect after connection loss (PLAN.md §7 Layer 1), which share this one loop.
     */
    private suspend fun runSessionLoop(child: AuthorizedChild) {
        var backoffMs = INITIAL_BACKOFF_MS
        val windowStartMs = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val server = ParentSessionServer(
                child = child,
                port = Constants.SESSION_PORT,
                onMessageReceived = { frame -> onInputFrameReceived(frame) },
                onSessionEnded = { reason -> onChildSessionEnded(child, reason) }
            )
            val connected = server.connect(serviceScope)
            if (connected) {
                sessionServersByChild[child.childId] = server
                return // success -- the server's own read loop now owns this connection's lifetime.
            }

            if (System.currentTimeMillis() - windowStartMs >= Constants.RECONNECT_WINDOW_MS) {
                onChildReconnectExhausted(child)
                return
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun onChildSessionEnded(child: AuthorizedChild, reason: String) {
        sessionServersByChild.remove(child.childId)?.close()
        if (reason == "connection_lost") {
            // Layer 1 (§7): attempt to re-dial with a fresh reconnect window.
            serviceScope.launch { runSessionLoop(child) }
        } else {
            // Child intentionally ended the session (SESSION_END from the far side) -- nothing
            // left to reconnect for this child.
            onChildReconnectExhausted(child)
        }
    }

    private fun onChildReconnectExhausted(child: AuthorizedChild) {
        endedChildCount++
        if (expectedChildCount > 0 && endedChildCount >= expectedChildCount) {
            // No child has a recoverable session left -- there is no longer an active or
            // recoverable session (PLAN.md §8), so the whole sharing session ends.
            stopSession("reconnect_timeout")
        }
    }

    private suspend fun broadcastVideoFrame(data: ByteArray, presentationTimeUs: Long) {
        val message = VideoFrameMessage(captureWidth, captureHeight, presentationTimeUs, data)
        sessionServersByChild.values.toList().forEach { server ->
            if (server.isConnected()) {
                server.sendVideoFrame(message)
            }
        }
    }

    private fun broadcastStatus(payload: StatusPayload, isEvent: Boolean) {
        broadcastFrame(payload.toFrame(isEvent))
    }

    /**
     * Generic fan-out to every connected child, reused by [broadcastStatus], the
     * `ACCESSIBILITY_STATUS` ticker (§7), and [sendFrameToConnectedChildren] (the companion-level
     * entry point [ParentAccessibilityService] uses for `TEXT_INJECT_FAILED`,
     * docs/plans/remote-input-control/PLAN.md §6/§8).
     */
    private fun broadcastFrame(frame: MessageFrame) {
        serviceScope.launch(Dispatchers.IO) {
            sessionServersByChild.values.toList().forEach { server ->
                if (server.isConnected()) {
                    server.sendFrame(frame)
                }
            }
        }
    }

    /** Routes an inbound `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` frame (already deframed by
     * [ParentSessionServer]) to the currently-bound [ParentAccessibilityService], if any -- a
     * no-op if the Accessibility Service isn't connected (view-only from the child's perspective,
     * PLAN.md §7). */
    private fun onInputFrameReceived(frame: MessageFrame) {
        if (frame.type == MessageType.INPUT_TAP || frame.type == MessageType.INPUT_SWIPE ||
            frame.type == MessageType.INPUT_TEXT
        ) {
            connectedAccessibilityService?.onInputFrameReceived(frame)
        }
    }

    private fun stopSession(reason: String) {
        val serversToClose = sessionServersByChild.values.toList()
        sessionServersByChild.clear()
        serviceScope.launch {
            serversToClose.forEach { it.sendSessionEndAndClose(reason) }
        }

        releaseCaptureResources()

        // Intentional/normal stop -- clear the durable flag so the Layer-2 watchdog
        // (SessionKeepAliveReceiver/SessionStateStore, PLAN.md §7) knows there is nothing left to
        // restart, and cancel its pending alarm outright.
        sessionStateStore.clear()
        keepAliveScheduler.cancel()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Releases the wake lock, `VirtualDisplay`, encoder, and `MediaProjection` -- the resources
     * owned by a single capture session. Shared by [stopSession] (intentional stop) and
     * [onDestroy] (covers PLAN.md §10 Case 1: an unexpected OS-triggered destroy of just the
     * Service component while the app process survives, which would otherwise leak the wake lock
     * indefinitely since a freshly recreated Service instance's [ParentWakeLockController] starts
     * with no reference to the lock the old instance is still holding). Null-safe on every field,
     * so it is harmless to call again after [stopSession] already released everything.
     */
    private fun releaseCaptureResources() {
        unregisterDisplayListener()
        statusMonitor.stop()
        wakeLockController.release()
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        mediaProjection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        mediaProjection = null
    }

    /**
     * PLAN.md §10 Case 2: reached when this instance was started without a usable
     * `MediaProjection` grant (e.g. [SessionKeepAliveReceiver]'s Layer-2 restart after a full
     * process-kill, or a bare system restart in a fresh process). A killed process invalidates
     * the grant -- it cannot be silently resumed, so no capture/encode/network-dial work is
     * attempted. The OS still requires `startForeground()` to be called promptly after
     * `startForegroundService()`, so a notification is briefly posted and immediately torn down.
     */
    private fun handleNoValidGrantAtStart() {
        startForeground(NOTIFICATION_ID, buildIdleNotification())
        sessionStateStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Mirrors stopSession()'s resource cleanup so an unexpected OS-triggered destroy of just
        // the Service component (process survives, PLAN.md §10 Case 1) can't leak the wake lock,
        // VirtualDisplay, encoder, or MediaProjection. Deliberately does NOT touch
        // sessionStateStore/keepAliveScheduler here -- those encode "this was an intentional
        // stop" and must stay untouched (sessionWasActive left true) on an unexpected destroy so
        // the Layer-2 watchdog (SessionKeepAliveReceiver, PLAN.md §7) can detect and restart it.
        // Harmless to call again if stopSession() already ran (every release below is null-safe).
        releaseCaptureResources()
        serviceJob.cancel()
        _isRunning.value = false
        if (activeInstance === this) activeInstance = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.screen_share_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildActiveNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notification_title))
            .setContentText(getString(R.string.screen_share_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)

        /** Backs the duplicate-launch guard (PLAN.md §4) -- checked by
         * [ShareStartTrampolineActivity] before requesting a new capture intent. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /** The currently-running instance, if any -- set/cleared in [onCreate]/[onDestroy]. Backs
         * [sendFrameToConnectedChildren], the one companion-level entry point that needs to reach
         * into this service's instance state (`sessionServersByChild`) from outside
         * (docs/plans/remote-input-control/PLAN.md §6). */
        @Volatile
        private var activeInstance: ScreenShareForegroundService? = null

        /** Set by [ParentAccessibilityService.onServiceConnected]/cleared by its `onDestroy` --
         * the companion reference [ParentAccessibilityService] "binds" through, per
         * docs/plans/README.md §8's dependency-table note. Nullable: input frames simply have
         * nowhere to go (silently dropped) while no Accessibility Service is connected, which is
         * the expected view-only-from-the-child's-perspective state (PLAN.md §7). */
        @Volatile
        private var connectedAccessibilityService: ParentAccessibilityService? = null

        fun attachAccessibilityService(service: ParentAccessibilityService) {
            connectedAccessibilityService = service
        }

        fun detachAccessibilityService(service: ParentAccessibilityService) {
            if (connectedAccessibilityService === service) connectedAccessibilityService = null
        }

        /** Entry point [ParentAccessibilityService] uses to relay `TEXT_INJECT_FAILED` back to
         * every connected child (PLAN.md §6/§8) -- a no-op if no session is currently active. */
        fun sendFrameToConnectedChildren(frame: MessageFrame) {
            activeInstance?.broadcastFrame(frame)
        }

        private const val ACTION_STOP = "com.familyremote.app.parent.action.STOP"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 3301
        private const val NOTIFICATION_CHANNEL_ID = "screen_share_channel"
        private const val VIRTUAL_DISPLAY_NAME = "FamilyRemoteScreenShare"

        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 10_000L

        fun createStartIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenShareForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun createStopIntent(context: Context): Intent =
            Intent(context, ScreenShareForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}

private fun Intent.getParcelableExtraCompat(name: String): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
