package com.familyremote.app.child

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.common.ConnectionState
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivitySessionBinding
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.StatusPayload
import com.familyremote.app.net.VideoFrameMessage
import com.familyremote.app.pairing.PairingEntry
import com.familyremote.app.pairing.PairingRepository
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single child-side screen hosting one connection attempt end-to-end: `WaitingForParent -> Live
 * -> Reconnecting -> Disconnected` (docs/plans/connection-status-and-resilience/PLAN.md §3/§4/§6).
 * See res/layout/activity_session.xml for why this is one Activity with visibility-toggled
 * sections rather than several -- a live socket connection must not be dropped by an Activity
 * transition. `android:configChanges` is set on this Activity in the manifest (survives rotation
 * without recreating) for the same reason -- this is the final design (confirmed by
 * docs/plans/screen-rotation-handling/PLAN.md §4): `VideoDecoderView`'s layout-driven
 * `onSizeChanged` still fires normally on a child-device rotation (configChanges only suppresses
 * Activity recreation, not layout), and a parent-side rotation is handled independently via the
 * `VIDEO_FRAME` header's `FrameGeometry` (see that same view), so no additional reflow code is
 * needed here.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var viewModel: ConnectionStateViewModel
    private lateinit var pairing: PairingEntry

    private val inputAvailabilityViewModel = InputAvailabilityViewModel()
    private lateinit var textInputBar: TextInputBar

    /** Shared by `TapInputController` and `TextInputBar`'s gating (PLAN.md §10) -- input is only
     * allowed while the outer `ConnectionState` is `Live` *and* `inputAvailable` is true. */
    private val isInputAllowed = { viewModel.state.value is ConnectionState.Live && inputAvailabilityViewModel.inputAvailable.value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Root stays edge-to-edge (video should mirror the parent's full screen); only the
        // top-anchored overlay chrome gets the status-bar inset, per issue #22.
        binding.topOverlayBannerContainer.applyStatusBarTopInsetPadding()
        binding.liveStatusText.applyStatusBarTopInsetPadding()
        binding.flagSecureHelpButton.applyStatusBarTopInsetPadding()
        hideSystemNavigationBar()

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
        viewModel.onOtherMessage = { frame -> handleOtherMessage(frame) }

        binding.waitingCancelButton.setOnClickListener {
            viewModel.cancelWaiting()
            finish()
        }
        binding.retryButton.setOnClickListener { viewModel.retryWaiting() }
        binding.disconnectedBackButton.setOnClickListener { finish() }
        binding.rejectedBackButton.setOnClickListener { finish() }

        setUpInputUi()

        lifecycleScope.launch { viewModel.state.collect { renderState(it) } }
        lifecycleScope.launch { viewModel.latestStatus.collect { renderStatus(it) } }
        lifecycleScope.launch { viewModel.listenerError.collect { renderListenerError(it) } }
        lifecycleScope.launch { inputAvailabilityViewModel.inputAvailable.collect { renderInputAvailability(it) } }
        lifecycleScope.launch { inputAvailabilityViewModel.textInjectFailed.collect { showTextInjectFailedNotice() } }
        // issue #38: the message-input bar only earns its screen space (and stops crowding the
        // real nav bar area below it) while the parent actually has somewhere to put the text.
        lifecycleScope.launch {
            inputAvailabilityViewModel.hasFocusedInputField.collect { hasFocusedField ->
                binding.textInputContainer.visibility = if (hasFocusedField) View.VISIBLE else View.GONE
            }
        }
        // issue #39: the "why is the screen black?" hint is only relevant while it actually is.
        lifecycleScope.launch {
            binding.videoDecoderView.isContentBlank.collect { isBlank ->
                binding.flagSecureHelpButton.visibility = if (isBlank) View.VISIBLE else View.GONE
            }
        }

        viewModel.startWaiting(pairing)
    }

    /**
     * Wires the tap/swipe/text input UI, per docs/plans/remote-input-control/PLAN.md §5/§8/§9.
     * `TapInputController`/`TextInputBar` both gate on the outer `ConnectionState` being `Live`
     * (not just `inputAvailable`, PLAN.md §10) -- neither can function once the connection itself
     * is no longer live, regardless of `inputAvailable`'s last known value.
     */
    private fun setUpInputUi() {
        val tapInputController = TapInputController(
            decoderView = binding.videoDecoderView,
            scope = lifecycleScope,
            isInputAllowed = isInputAllowed,
            sendFrame = { frame -> viewModel.sendFrame(frame) },
            onValidGesture = { points -> binding.tapMarkerOverlay.showMarker(points) }
        )
        binding.videoDecoderView.setOnTouchListener(tapInputController)

        textInputBar = TextInputBar(
            editText = binding.textInputEditText,
            sendButton = binding.textInputSendButton,
            scope = lifecycleScope,
            sendFrame = { frame -> viewModel.sendFrame(frame) }
        )

        binding.flagSecureHelpButton.setOnClickListener { showFlagSecureHelpDialog() }
    }

    /**
     * Hides the child device's own OS navigation bar for this Activity's whole lifetime (issue
     * #34) -- it only ever operates the child's own OS, never the parent being viewed, so leaving
     * it visible during a share session is actively misleading. A tap on the parent's own
     * on-screen nav bar, visible within the shared video itself, already reaches the parent as an
     * ordinary relayed `INPUT_TAP` -- no separate child-side Back/Home relay UI is needed on top
     * of that. Uses transient-by-swipe behavior (not fully locked/kiosk), so the child can still
     * swipe from the screen edge to reveal it temporarily and use the OS's own Home/Back if they
     * genuinely need to leave the app -- matches the standard immersive-video UX pattern, not a
     * lockout (touch arbitration itself remains an accepted limitation, issue #8).
     */
    private fun hideSystemNavigationBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    /** Dispatches frame types `ConnectionStateViewModel` doesn't itself parse -- `VIDEO_FRAME`
     * (rendering) and `ACCESSIBILITY_STATUS`/`TEXT_INJECT_FAILED` (view-only mode, PLAN.md §7/§8).
     * Called from the session-channel read loop's background thread. */
    private fun handleOtherMessage(frame: MessageFrame) {
        when (frame.type) {
            MessageType.VIDEO_FRAME -> binding.videoDecoderView.onVideoFrame(VideoFrameMessage.parse(frame))
            MessageType.ACCESSIBILITY_STATUS, MessageType.TEXT_INJECT_FAILED -> inputAvailabilityViewModel.onFrame(frame)
            else -> Unit
        }
    }

    private fun renderInputAvailability(available: Boolean) {
        binding.viewOnlyBanner.visibility = if (available) View.GONE else View.VISIBLE
        textInputBar.setEnabled(isInputAllowed())
    }

    private fun showTextInjectFailedNotice() {
        Snackbar.make(binding.root, R.string.session_text_inject_failed_notice, Snackbar.LENGTH_SHORT).show()
    }

    private fun showFlagSecureHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_help_hint_title)
            .setMessage(R.string.session_help_hint_message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope cancellation alone does not unblock an in-progress blocking
        // accept()/read() call, so resources are closed explicitly here.
        viewModel.release()
        binding.videoDecoderView.release()
    }

    private fun renderState(state: ConnectionState) {
        binding.waitingSection.visibility = if (state is ConnectionState.WaitingForParent) View.VISIBLE else View.GONE
        binding.liveSection.visibility =
            if (state is ConnectionState.Live || state is ConnectionState.Reconnecting) View.VISIBLE else View.GONE
        binding.disconnectedSection.visibility = if (state is ConnectionState.Disconnected) View.VISIBLE else View.GONE
        binding.rejectedSection.visibility = if (state is ConnectionState.Rejected) View.VISIBLE else View.GONE
        binding.reconnectingBanner.visibility = if (state is ConnectionState.Reconnecting) View.VISIBLE else View.GONE

        when (state) {
            is ConnectionState.WaitingForParent ->
                binding.waitingText.text = getString(R.string.session_waiting_title, state.pairing.label)
            is ConnectionState.Disconnected ->
                binding.disconnectedText.text = getString(R.string.session_disconnected_title, state.pairing.label)
            is ConnectionState.Rejected ->
                binding.rejectedText.text = getString(R.string.session_rejected_title, state.pairing.label)
            // Fresh Live entry (initial connect, or reconnect-then-live) -- the previous
            // inputAvailable value belonged to a now-superseded connection (PLAN.md §7).
            is ConnectionState.Live -> inputAvailabilityViewModel.reset()
            else -> Unit
        }

        // Re-evaluate now that the outer ConnectionState may have changed -- `reset()` above only
        // re-emits on `inputAvailable`'s own collector when its value actually changes (e.g. it
        // stays `true` across a Live->Live no-op), which would otherwise leave `TextInputBar`
        // stale on a Live->Reconnecting->Live cycle that never flips `inputAvailable` (PLAN.md §10).
        textInputBar.setEnabled(isInputAllowed())
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
