package com.familyremote.app.parent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.familyremote.app.common.Constants
import com.familyremote.app.net.StatusPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Parent-side: samples Wi-Fi/connectivity state and produces `STATUS_HEARTBEAT`/`STATUS_EVENT`
 * payloads, per docs/plans/connection-status-and-resilience/PLAN.md §5. Only produces the
 * payload and hands it to [onStatusPayload] -- writing it to each connected `ParentSessionServer`
 * is owned by [ScreenShareForegroundService] (started alongside it, per that plan's §2 component
 * table), not this class.
 */
class ParentStatusMonitor(
    private val context: Context,
    private val onStatusPayload: (payload: StatusPayload, isEvent: Boolean) -> Unit
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private var tickerJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(scope: CoroutineScope) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitEvent()
            override fun onLost(network: Network) = emitEvent()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitEvent()
        }
        networkCallback = callback
        runCatching { connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }

        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                onStatusPayload(buildPayload(), false)
                delay(Constants.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun emitEvent() {
        onStatusPayload(buildPayload(), true)
    }

    private fun buildPayload(): StatusPayload {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val networkType = when {
            wifiConnected -> "WIFI"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            else -> "NONE"
        }

        var signalStrength: Int? = null
        var ssid: String? = null
        if (wifiConnected) {
            // Best-effort only -- SSID visibility requires location permission on some API levels
            // this app does not request; if unobtainable, the field is simply omitted (null)
            // rather than blocking the feature, per detailed-spec §9.
            runCatching {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val info = wifiManager?.connectionInfo
                signalStrength = info?.rssi
                ssid = info?.ssid?.trim('"')?.takeUnless { it.isEmpty() || it == UNKNOWN_SSID }
            }
        }

        return StatusPayload(
            wifiConnected = wifiConnected,
            networkType = networkType,
            signalStrength = signalStrength,
            ssid = ssid,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
