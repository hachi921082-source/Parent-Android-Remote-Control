package com.familyremote.app.child

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.common.ConnectionState
import com.familyremote.app.databinding.ActivitySessionBinding
import com.familyremote.app.net.StatusPayload
import com.familyremote.app.pairing.PairingEntry
import com.familyremote.app.pairing.PairingRepository
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single child-side screen hosting one connection attempt end-to-end: `WaitingForParent -> Live
 * -> Reconnecting -> Disconnected` (docs/plans/connection-status-and-resilience/PLAN.md §3/§4/§6).
 * See res/layout/activity_session.xml for why this is one Activity with visibility-toggled
 * sections rather than several -- a live socket connection must not be dropped by an Activity
 * transition. `android:configChanges` is set on this Activity in the manifest (survives rotation
 * without recreating) for the same reason, as an interim measure pending
 * docs/plans/screen-rotation-handling/PLAN.md's proper child-side reflow handling.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var viewModel: ConnectionStateViewModel
    private lateinit var pairing: PairingEntry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pairingId = intent.getStringExtra(EXTRA_PAIRING_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val resolvedPairing = pairingId?.let { id ->
            PairingRepository(this).getPairingEntries().firstOrNull { it.id == id }
        }
        if (resolvedPairing == null) {
            // Entry was deleted (or a malformed Intent) between the list screen and here -- there
            // is nothing to connect to; just leave.
            finish()
            return
        }
        pairing = resolvedPairing

        viewModel = ConnectionStateViewModel(lifecycleScope)

        binding.waitingCancelButton.setOnClickListener {
            viewModel.cancelWaiting()
            finish()
        }
        binding.retryButton.setOnClickListener { viewModel.retryWaiting() }
        binding.disconnectedBackButton.setOnClickListener { finish() }

        lifecycleScope.launch { viewModel.state.collect { renderState(it) } }
        lifecycleScope.launch { viewModel.latestStatus.collect { renderStatus(it) } }
        lifecycleScope.launch { viewModel.listenerError.collect { renderListenerError(it) } }

        viewModel.startWaiting(pairing)
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope cancellation alone does not unblock an in-progress blocking
        // accept()/read() call, so resources are closed explicitly here.
        viewModel.release()
    }

    private fun renderState(state: ConnectionState) {
        binding.waitingSection.visibility = if (state is ConnectionState.WaitingForParent) View.VISIBLE else View.GONE
        binding.liveSection.visibility =
            if (state is ConnectionState.Live || state is ConnectionState.Reconnecting) View.VISIBLE else View.GONE
        binding.disconnectedSection.visibility = if (state is ConnectionState.Disconnected) View.VISIBLE else View.GONE
        binding.reconnectingBanner.visibility = if (state is ConnectionState.Reconnecting) View.VISIBLE else View.GONE

        when (state) {
            is ConnectionState.WaitingForParent ->
                binding.waitingText.text = getString(R.string.session_waiting_title, state.pairing.label)
            is ConnectionState.Disconnected ->
                binding.disconnectedText.text = getString(R.string.session_disconnected_title, state.pairing.label)
            else -> Unit
        }
    }

    private fun renderListenerError(message: String?) {
        binding.listenerErrorText.visibility = if (message != null) View.VISIBLE else View.GONE
        binding.retryButton.visibility = if (message != null) View.VISIBLE else View.GONE
        if (message != null) {
            binding.listenerErrorText.setText(R.string.session_listener_error)
        }
    }

    private fun renderStatus(payload: StatusPayload?) {
        if (payload == null) {
            binding.liveStatusText.setText(R.string.session_status_placeholder)
            return
        }
        val networkTypeText = when (payload.networkType) {
            "WIFI" -> getString(R.string.network_type_wifi)
            "CELLULAR" -> getString(R.string.network_type_cellular)
            else -> getString(R.string.network_type_none)
        }
        val lines = mutableListOf(
            getString(
                if (payload.wifiConnected) R.string.session_status_wifi_connected
                else R.string.session_status_wifi_disconnected
            ),
            getString(R.string.session_status_network_type, networkTypeText)
        )
        payload.signalStrength?.let { lines += getString(R.string.session_status_signal, it) }
        payload.ssid?.let { lines += getString(R.string.session_status_ssid, it) }
        binding.liveStatusText.text = lines.joinToString("\n")
    }

    companion object {
        private const val EXTRA_PAIRING_ID = "extra_pairing_id"

        fun createIntent(context: Context, pairingId: UUID): Intent =
            Intent(context, SessionActivity::class.java).apply {
                putExtra(EXTRA_PAIRING_ID, pairingId.toString())
            }
    }
}
