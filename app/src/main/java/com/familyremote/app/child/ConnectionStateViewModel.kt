package com.familyremote.app.child

import com.familyremote.app.common.ConnectionState
import com.familyremote.app.common.Constants
import com.familyremote.app.net.StatusPayload
import com.familyremote.app.pairing.PairingEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket

/**
 * Single source of truth for [ConnectionState], per
 * docs/plans/connection-status-and-resilience/PLAN.md §2/§3. Owns [ChildSessionListener] (the
 * listening socket) and, once `Live`, a [ChildSessionChannel] (reads the established socket) and
 * [HeartbeatWatchdog] (drives `Live -> Reconnecting -> Disconnected`). Scoped to the lifetime of
 * the single child-side screen that hosts an entire connection attempt (see `child/SessionActivity`)
 * -- not an androidx `ViewModel` subclass (this project has no such dependency elsewhere), but
 * named to match the plan's component table; [scope] is expected to be cancelled by its owner
 * ([release]'s caller) when that screen goes away.
 */
class ConnectionStateViewModel(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _latestStatus = MutableStateFlow<StatusPayload?>(null)
    val latestStatus: StateFlow<StatusPayload?> = _latestStatus.asStateFlow()

    private val _listenerError = MutableStateFlow<String?>(null)
    /** Set when [ChildSessionListener] itself fails to start listening (e.g. no Tailscale address
     * resolvable) -- distinct from a mismatched handshake attempt, which stays silent per PLAN.md
     * §4 step 4. Cleared on the next successful (re)arm. */
    val listenerError: StateFlow<String?> = _listenerError.asStateFlow()

    private val listener = ChildSessionListener()
    private var channel: ChildSessionChannel? = null
    private var watchdog: HeartbeatWatchdog? = null

    fun startWaiting(pairing: PairingEntry) {
        _latestStatus.value = null
        _state.value = ConnectionState.WaitingForParent(pairing)
        arm(pairing)
    }

    /** Manual retry after a [listenerError] (e.g. Tailscale became reachable since the last try). */
    fun retryWaiting() {
        val pairing = (_state.value as? ConnectionState.WaitingForParent)?.pairing ?: return
        arm(pairing)
    }

    private fun arm(pairing: PairingEntry) {
        listener.start(scope, pairing, Constants.SESSION_PORT) { result ->
            when (result) {
                is ChildSessionListener.Result.Connected -> {
                    _listenerError.value = null
                    onConnected(pairing, result.socket)
                }
                is ChildSessionListener.Result.Failed -> _listenerError.value = result.message
            }
        }
    }

    private fun onConnected(pairing: PairingEntry, socket: Socket) {
        _state.value = ConnectionState.Live(pairing)

        val activeWatchdog = watchdog ?: HeartbeatWatchdog(
            onReconnectingDetected = { onReconnectingDetected(pairing) },
            onDisconnectedDetected = { onDisconnectedDetected(pairing) }
        ).also { watchdog = it }
        activeWatchdog.start(scope) // (re)starts -- lastReceivedAt/reconnectingSince reset to "now".

        channel?.close()
        val newChannel = ChildSessionChannel(
            socket = socket,
            onAnyMessage = { activeWatchdog.onMessageReceived() },
            onStatusUpdate = { payload -> _latestStatus.value = payload },
            onSessionEnded = { watchdog?.notifyConnectionLost() }
        )
        channel = newChannel
        newChannel.startReading(scope)
    }

    private fun onReconnectingDetected(pairing: PairingEntry) {
        val current = _state.value
        if (current !is ConnectionState.Live && current !is ConnectionState.Reconnecting) return
        if (current !is ConnectionState.Reconnecting) {
            _state.value = ConnectionState.Reconnecting(pairing, since = System.currentTimeMillis())
        }
        // Re-arms a short-lived accept loop (§6) -- reuses the still-open ServerSocket if the
        // blip didn't actually close it, or re-opens it if the OS did.
        arm(pairing)
    }

    private fun onDisconnectedDetected(pairing: PairingEntry) {
        channel?.close()
        channel = null
        watchdog?.stop()
        watchdog = null
        listener.stop()
        _state.value = ConnectionState.Disconnected(pairing)
    }

    /** Cancel button in `WaitingForParent` -- back to Idle/PairingSelect, per PLAN.md §4 step 5. */
    fun cancelWaiting() {
        listener.stop()
        _state.value = ConnectionState.Idle
    }

    /** "Back to device list" from `Disconnected`, or the hosting screen being torn down. Safe to
     * call even if cleanup already happened (e.g. via [onDisconnectedDetected]). */
    fun release() {
        listener.stop()
        channel?.close()
        channel = null
        watchdog?.stop()
        watchdog = null
    }
}
