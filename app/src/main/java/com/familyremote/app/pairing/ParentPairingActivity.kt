package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.common.Constants
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.common.openTailscaleAppOrWebsite
import com.familyremote.app.databinding.ActivityParentPairingBinding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch

/**
 * Onboarding step 4 -- parent side (PLAN.md §4 step 1). Resolves this device's Tailscale
 * address, starts the temporary pairing listener, and displays the QR code the child scans.
 *
 * Tailscale install/connect is no longer *explained* here -- [OnboardingWizardActivity]'s
 * preceding setup step already gates on it being connected before this screen is first reached
 * (issue #28). A null [TailscaleAddressResolver.resolve] result here means it was disconnected
 * since then (e.g. the parent toggled it off by accident) -- issue #52 adds a one-tap "Open
 * Tailscale" recovery button for that case (skips explaining the notification shade's Quick
 * Settings tile; the parent just needs to open Tailscale and tap its connect switch), plus an
 * [onResume] re-check so returning here from Tailscale resumes pairing automatically.
 */
class ParentPairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentPairingBinding
    private lateinit var repository: PairingRepository
    private var pairingJob: kotlinx.coroutines.Job? = null
    private var pairingServer: PairingServer? = null

    /** Set while blocked on [TailscaleAddressResolver.resolve] returning null -- lets [onResume]
     * know it's safe to retry automatically (an active QR/pairing attempt must not be restarted
     * just because the Activity resumed for an unrelated reason). */
    private var tailscaleMissing = false
    private var isFirstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()
        repository = PairingRepository(this)

        binding.retryButton.setOnClickListener { startPairingAttempt() }
        binding.doneButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
        binding.openSourceLicensesButton.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        binding.openTailscaleButton.setOnClickListener { openTailscaleAppOrWebsite() }

        startPairingAttempt()
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            // onCreate already called startPairingAttempt() once -- skip the resume that
            // immediately follows it so a fresh screen load doesn't regenerate the QR twice.
            isFirstResume = false
            return
        }
        if (tailscaleMissing) {
            startPairingAttempt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // stop() first: Job.cancel() alone can't interrupt a thread already blocked in
        // ServerSocket.accept() (see PairingServer.stop()'s doc comment) -- without this, the
        // listen port stays bound until the next connection or the accept timeout, so an
        // immediate re-entry into this screen could hit "Address already in use".
        pairingServer?.stop()
        pairingJob?.cancel()
    }

    private fun startPairingAttempt() {
        pairingServer?.stop()
        binding.retryButton.visibility = View.GONE
        binding.doneButton.visibility = View.GONE
        binding.qrImageView.visibility = View.GONE
        binding.hostAddressText.visibility = View.GONE
        binding.manualCodeText.visibility = View.GONE
        binding.openTailscaleButton.visibility = View.GONE

        val parentHost = TailscaleAddressResolver.resolve()
        if (parentHost == null) {
            tailscaleMissing = true
            binding.statusText.setText(R.string.parent_pairing_tailscale_missing)
            binding.openTailscaleButton.visibility = View.VISIBLE
            return
        }
        tailscaleMissing = false

        val tempToken = SecureTokenGenerator.generateTempPairingToken()
        val qrPayload = PairingQrPayload(
            v = PairingQrPayload.VERSION,
            parentHost = parentHost,
            port = Constants.PAIRING_PORT,
            tempToken = tempToken
        )
        showQrCode(qrPayload.toJson())

        val manualCode = ManualPairingCodeGenerator.generate()
        showHostAddress(parentHost)
        showManualCode(manualCode)

        binding.statusText.setText(R.string.parent_pairing_waiting)

        pairingJob = lifecycleScope.launch {
            val server = PairingServer(repository)
            pairingServer = server
            val result = server.listenForHandshake(
                bindAddress = parentHost,
                port = Constants.PAIRING_PORT,
                tempToken = tempToken,
                manualCode = manualCode,
                validityMs = Constants.PAIRING_TOKEN_VALIDITY_MS
            )
            onPairingResult(result)
        }
    }

    private fun onPairingResult(result: PairingServerResult) {
        when (result) {
            is PairingServerResult.Success -> {
                hidePairingAttemptViews()
                binding.statusText.setText(R.string.parent_pairing_success)
                binding.doneButton.visibility = View.VISIBLE
            }
            is PairingServerResult.Timeout -> {
                hidePairingAttemptViews()
                binding.statusText.setText(R.string.parent_pairing_error_timeout)
                binding.retryButton.visibility = View.VISIBLE
            }
            is PairingServerResult.Error -> {
                hidePairingAttemptViews()
                binding.statusText.text = getString(R.string.parent_pairing_error_generic, result.message)
                binding.retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun hidePairingAttemptViews() {
        binding.qrImageView.visibility = View.GONE
        binding.hostAddressText.visibility = View.GONE
        binding.manualCodeText.visibility = View.GONE
    }

    private fun showQrCode(contents: String) {
        val bitmap = BarcodeEncoder().encodeBitmap(contents, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX)
        binding.qrImageView.setImageBitmap(bitmap)
        binding.qrImageView.visibility = View.VISIBLE
    }

    private fun showHostAddress(parentHost: String) {
        binding.hostAddressText.text = getString(R.string.parent_pairing_host_address_label, parentHost)
        binding.hostAddressText.visibility = View.VISIBLE
    }

    private fun showManualCode(manualCode: String) {
        val formattedCode = manualCode.chunked(MANUAL_CODE_GROUP_SIZE).joinToString("-")
        binding.manualCodeText.text = getString(R.string.parent_pairing_manual_code_label, formattedCode)
        binding.manualCodeText.visibility = View.VISIBLE
    }

    companion object {
        private const val QR_SIZE_PX = 720
        private const val MANUAL_CODE_GROUP_SIZE = 4
    }
}
