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
import com.familyremote.app.net.SessionBusyPayload
import com.familyremote.app.net.StatusPayload
import com.familyremote.app.net.VideoFrameMessage
import com.familyremote.app.pairing.AuthorizedChild
import com.familyremote.app.pairing.PairingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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

    /**
     * `ScreenCaptureEncoder.onNalUnit` calls are already strictly sequential (MediaCodec delivers
     * every `MediaCodec.Callback` method one at a time, in order, on that encoder's own callback
     * thread) -- but the old code re-launched a fresh `serviceScope.launch(Dispatchers.IO) { ... }`
     * per NAL unit, which hands each one to an unordered thread-pool coroutine. Two NAL units for
     * the same frame (e.g. SPS/PPS then the IDR slice) could then reach [broadcastVideoFrame] --
     * and therefore [ParentSessionServer.sendFrame]'s socket write -- out of order or, worse,
     * genuinely concurrently and interleaved mid-write, corrupting the `MessageFrame` framing on
     * the wire (observed as the child's `MessageFrame.readFrom` crashing with "Invalid frame
     * length: 0" once real video frames were actually flowing for the first time -- this bug
     * predates this session's encoder rewrite but was never previously exercised). A single
     * long-lived consumer draining this channel restores strict ordering and non-overlapping
     * writes without blocking the encoder's own callback thread.
     */
    private data class PendingVideoFrame(val data: ByteArray, val presentationTimeUs: Long)
    private var videoFrameChannel: Channel<PendingVideoFrame>? = null

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

    /**
     * Single-session-enforcement arbitration (docs/plans/single-session-enforcement/PLAN.md §3.1):
     * [activeChildId] is the one child/PC client currently holding the Live slot, if any.
     * [arbitrationLock] guards it -- serviceScope runs on Dispatchers.Default, so the per-child
     * coroutines in [runSessionLoop] genuinely race across threads, not just across suspension
     * points, when their concurrent `connect()` calls succeed close together.
     */
    private val arbitrationLock = Any()
    private var activeChildId: UUID? = null

    /** Replaces the old flat `endedChildCount` counter (PLAN.md §3.3) -- every child, win-or-lose,
     * that has permanently given up on this sharing session (no more reconnect attempts left). */
    private val exhaustedChildIds = mutableSetOf<UUID>()

    /** Last value broadcast by [startAccessibilityStatusTicker] -- `null` until the first check
     * runs, so that check's result is always sent even if it happens to read `false`. */
    private var lastKnownAccessibilityGranted: Boolean? = null

    /** Same as [lastKnownAccessibilityGranted] but for `hasFocusedInputField` (issue #38). */
    private var lastKnownHasFocusedInputField: Boolean? = null

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

        // Issue #37 root cause: `resources.displayMetrics` here returned a height that excludes
        // the system nav bar (confirmed live, sense4: 2064 vs. the real 2280) -- capture then
        // silently cropped off the bottom of the screen, so a tap the child computed against that
        // too-short declared frame height could never reach anywhere near the real bottom of the
        // screen `dispatchGesture` addresses, landing ~200px too high (onto the app's own UI
        // instead of the OS nav bar visible there). `onDefaultDisplayChanged()` below already used
        // `Display.getRealMetrics()` (the full physical size, matching `dispatchGesture`'s
        // coordinate space) for its own reconfigure-on-rotation path -- this now matches that, so
        // capture and Accessibility gesture dispatch agree on one screen size from the start.
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = if (display != null) {
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                display.getRealMetrics(it)
            }
        } else {
            resources.displayMetrics // DEFAULT_DISPLAY is always expected to exist; unreachable in practice.
        }
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val dpi = metrics.densityDpi

        val channel = startVideoFrameConsumer()
        val newEncoder = ScreenCaptureEncoder(onNalUnit = { data, presentationTimeUs ->
            channel.trySend(PendingVideoFrame(data, presentationTimeUs))
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
            val channel = videoFrameChannel ?: startVideoFrameConsumer()
            val newEncoder = ScreenCaptureEncoder(onNalUnit = { data, presentationTimeUs ->
                channel.trySend(PendingVideoFrame(data, presentationTimeUs))
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
     * `ACCESSIBILITY_STATUS {granted, hasFocusedInputField}` to every connected child whenever
     * *either* value changes (not every tick) -- the pre-flight check in
     * `ParentAccessibilityService` already catches permission revocation on every actual input
     * attempt; this only surfaces it during idle stretches. `hasFocusedInputField` (issue #38)
     * drives the child's message-input bar visibility the same way.
     */
    private fun startAccessibilityStatusTicker() {
        lastKnownAccessibilityGranted = null
        lastKnownHasFocusedInputField = null
        serviceScope.launch {
            while (currentCoroutineContext().isActive) {
                val granted = AccessibilityPermissionMonitor.isGranted(this@ScreenShareForegroundService)
                val hasFocusedInputField = connectedAccessibilityService?.hasFocusedInputField() ?: false
                if (granted != lastKnownAccessibilityGranted || hasFocusedInputField != lastKnownHasFocusedInputField) {
                    lastKnownAccessibilityGranted = granted
                    lastKnownHasFocusedInputField = hasFocusedInputField
                    broadcastFrame(AccessibilityStatusPayload(granted, hasFocusedInputField).toFrame())
                }
                delay(Constants.ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun dialAuthorizedChildren() {
        val children = PairingRepository(applicationContext).getAuthorizedChildren()
        expectedChildCount = children.size
        exhaustedChildIds.clear()
        if (children.isEmpty()) return // nothing to dial -- capture still runs, harmlessly idle.
        children.forEach { child -> serviceScope.launch { runSessionLoop(child) } }
    }

    /**
     * Dials [child], retrying with exponential backoff (1s, 2s, 4s... capped at 10s) for up to
     * [Constants.RECONNECT_WINDOW_MS] -- covers both the initial dial (PLAN.md §6 step 4) and a
     * later reconnect after connection loss (PLAN.md §7 Layer 1), which share this one loop. Once
     * `connect()` succeeds, [tryClaimSession] arbitrates the single-session-enforcement Live slot
     * (docs/plans/single-session-enforcement/PLAN.md §3.1) -- a losing candidate never reaches
     * `sessionServersByChild` and is handed to [handleLosingCandidate] instead.
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
                if (tryClaimSession(child)) {
                    sessionServersByChild[child.childId] = server
                    // success -- the server's own read loop now owns this connection's lifetime.
                } else {
                    // Lost the arbitration race against whichever candidate already claimed the
                    // slot (PLAN.md §4 RESOLVED: "explicit + give up") -- tell the loser, close its
                    // throwaway socket, and give up for good; no retry-loop re-entry for the loser.
                    handleLosingCandidate(child, server)
                }
                return
            }

            if (System.currentTimeMillis() - windowStartMs >= Constants.RECONNECT_WINDOW_MS) {
                // Never claimed the slot within this window -- always a non-active exhaustion.
                onChildPermanentlyDone(child, wasActive = false)
                return
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Returns true iff [child] just won the single Live slot. Synchronous, non-suspending
     * critical section only -- safe to call from any of the concurrently-racing per-child
     * coroutines (docs/plans/single-session-enforcement/PLAN.md §3.1). */
    private fun tryClaimSession(child: AuthorizedChild): Boolean = synchronized(arbitrationLock) {
        if (activeChildId == null) {
            activeChildId = child.childId
            true
        } else {
            false
        }
    }

    /** §4 RESOLVED ("explicit + give up", PLAN.md §5): [child] connected but lost the arbitration
     * race. Sends `SESSION_BUSY` over the just-connected (otherwise-unused) socket, closes it, and
     * gives up on this child for good -- no retry-loop re-entry for the loser. */
    private suspend fun handleLosingCandidate(child: AuthorizedChild, server: ParentSessionServer) {
        server.sendFrame(MessageFrame.of(MessageType.SESSION_BUSY, SessionBusyPayload().toJson()))
        server.close()
        onChildPermanentlyDone(child, wasActive = false)
    }

    private fun onChildSessionEnded(child: AuthorizedChild, reason: String) {
        val wasActive = (activeChildId == child.childId)
        sessionServersByChild.remove(child.childId)?.close()
        if (wasActive) {
            // Free the slot only when the ending child was the actual claimant -- a losing
            // candidate's own throwaway socket closing (handleLosingCandidate) must never touch
            // activeChildId (PLAN.md §3.2).
            synchronized(arbitrationLock) { activeChildId = null }
        }
        if (reason == "connection_lost") {
            // Layer 1 (§7): attempt to re-dial with a fresh reconnect window.
            serviceScope.launch { runSessionLoop(child) }
        } else {
            // Child intentionally ended the session (SESSION_END from the far side) -- nothing
            // left to reconnect for this child.
            onChildPermanentlyDone(child, wasActive)
        }
    }

    /**
     * Renamed from the old `onChildReconnectExhausted` (docs/plans/single-session-enforcement/
     * PLAN.md §3.3) -- [child] has no more reconnect attempts left, either because it was the
     * active claimant giving up for good, or because it was a candidate (winner or loser) that
     * never becomes active again. Only the active claimant exhausting stops the whole sharing
     * session outright; a losing/never-claimed candidate exhausting only stops the session if
     * every dialed candidate has now given up and nobody ever claimed the slot.
     */
    private fun onChildPermanentlyDone(child: AuthorizedChild, wasActive: Boolean) {
        exhaustedChildIds += child.childId
        when {
            wasActive ->
                // The one client that was actually Live gave up for good -- there is no longer an
                // active or recoverable session (screen-sharing-core PLAN.md §8), so sharing ends.
                stopSession("reconnect_timeout")
            activeChildId == null && exhaustedChildIds.size >= expectedChildCount ->
                // Nobody ever claimed the slot, and every dialed candidate has now given up.
                stopSession("reconnect_timeout")
            else ->
                // A losing candidate gave up while someone else is genuinely Live -- not a reason
                // to stop the session.
                Unit
        }
    }

    /** Creates (once per capture session) the ordered queue [PendingVideoFrame]s are drained
     * through -- see that class's doc for why a single long-lived consumer is required here. */
    private fun startVideoFrameConsumer(): Channel<PendingVideoFrame> {
        val channel = Channel<PendingVideoFrame>(Channel.UNLIMITED)
        videoFrameChannel = channel
        serviceScope.launch(Dispatchers.IO) {
            for (frame in channel) {
                broadcastVideoFrame(frame.data, frame.presentationTimeUs)
            }
        }
        return channel
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
        videoFrameChannel?.close()
        videoFrameChannel = null
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
     * attempted.
     *
     * This service is declared with `foregroundServiceType="mediaProjection"` in the manifest, so
     * on API 34+ *any* call to `startForeground()` here -- regardless of the notification content
     * -- is validated by the OS as an actual MediaProjection foreground service start, and throws
     * `SecurityException` when (as here) there is no active grant. `startForeground()` must
     * therefore not be called at all on this path; skipping it and calling `stopSelf()` directly
     * is the standard resolution (confirmed via a real crash on real hardware, not just read from
     * docs -- see GitHub issue for the crash this replaced).
     */
    private fun handleNoValidGrantAtStart() {
        sessionStateStore.clear()
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
            .addAction(0, getString(R.string.screen_share_notification_stop_action), stopPendingIntent())
            .build()

    /** issue #36: previously nothing in the UI ever sent [ACTION_STOP] -- the only way to end a
     * session was the OS's own generic MediaProjection "stop casting" control, or leaving it
     * running indefinitely (by design, task-removal alone must not stop sharing -- see
     * [releaseCaptureResources]'s doc). This notification action, plus [ParentHomeFragment]'s
     * button, are the two real stop entry points. */
    private fun stopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
