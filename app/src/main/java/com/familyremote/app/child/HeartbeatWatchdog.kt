package com.familyremote.app.child

import com.familyremote.app.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the `Live -> Reconnecting -> Disconnected` transitions, per
 * docs/plans/connection-status-and-resilience/PLAN.md §6. Tracks time since the last inbound
 * message on the session channel (any message counts, not just `STATUS_HEARTBEAT` -- see
 * [onMessageReceived]) and, separately, the 60s reconnect window once `Reconnecting` begins.
 */
class HeartbeatWatchdog(
    private val onReconnectingDetected: () -> Unit,
    private val onDisconnectedDetected: () -> Unit
) {
    @Volatile
    private var lastReceivedAt = 0L

    @Volatile
    private var reconnectingSince: Long? = null

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        lastReceivedAt = System.currentTimeMillis()
        reconnectingSince = null
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(Constants.HEARTBEAT_INTERVAL_MS)
                checkOnce()
            }
        }
    }

    private fun checkOnce() {
        val now = System.currentTimeMillis()
        val since = reconnectingSince
        if (since == null) {
            val missedThresholdMs = Constants.MISSED_HEARTBEAT_THRESHOLD * Constants.HEARTBEAT_INTERVAL_MS
            if (now - lastReceivedAt >= missedThresholdMs) {
                markReconnecting(now)
            }
        } else if (now - since >= Constants.RECONNECT_WINDOW_MS) {
            job?.cancel()
            onDisconnectedDetected()
        }
    }

    private fun markReconnecting(now: Long) {
        reconnectingSince = now
        onReconnectingDetected()
    }

    /**
     * Explicit external signal that the connection is known to be lost right now (a `SESSION_END`
     * receipt or an `IOException` on the read loop) -- same effect as a detected missed heartbeat,
     * just immediate instead of waiting for the next 5s check tick. No-op if already reconnecting,
     * so a later missed-heartbeat tick can't restart the 60s window from a fresh "now".
     */
    fun notifyConnectionLost() {
        if (reconnectingSince == null) markReconnecting(System.currentTimeMillis())
    }

    /** Called on any inbound session-channel message; also clears a `Reconnecting` mark, since
     * receiving anything means the connection is alive again. */
    fun onMessageReceived() {
        lastReceivedAt = System.currentTimeMillis()
        reconnectingSince = null
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
