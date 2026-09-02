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
import androidx.core.app.NotificationCompat
import com.familyremote.app.MainActivity
import com.familyremote.app.R
import com.familyremote.app.common.Constants
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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenCaptureEncoder? = null

    private var captureWidth = 0
    private var captureHeight = 0

    private val sessionServersByChild = mutableMapOf<UUID, ParentSessionServer>()
    private var expectedChildCount = 0
    private var endedChildCount = 0

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
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        wakeLockController = ParentWakeLockController(this)
        sessionStateStore = SessionStateStore(this)
        keepAliveScheduler = SessionKeepAliveScheduler(this)
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
        dialAuthorizedChildren()
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
                onMessageReceived = {
                    // INPUT_TAP/INPUT_SWIPE/INPUT_TEXT, owned by remote-input-control (not yet
                    // built) -- intentionally unhandled here per PLAN.md §2/§6 so a later tier can
                    // attach its own handling without needing to touch this file again.
                },
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
