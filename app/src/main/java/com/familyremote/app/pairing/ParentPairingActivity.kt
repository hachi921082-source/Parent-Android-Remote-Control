package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.common.Constants
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityParentPairingBinding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch

/**
 * Onboarding step 4 -- parent side (PLAN.md §4 step 1). Resolves this device's Tailscale
 * address, starts the temporary pairing listener, and displays the QR code the child scans.
 */
class ParentPairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentPairingBinding
    private lateinit var repository: PairingRepository
    private var pairingJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()
        repository = PairingRepository(this)

        binding.retryButton.setOnClickListener { startPairingAttempt() }
        binding.openTailscaleButton.setOnClickListener { openTailscaleApp() }
        binding.doneButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        startPairingAttempt()
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingJob?.cancel()
    }

    private fun startPairingAttempt() {
        binding.retryButton.visibility = View.GONE
        binding.openTailscaleButton.visibility = View.GONE
        binding.doneButton.visibility = View.GONE
        binding.qrImageView.visibility = View.GONE
        binding.hostAddressText.visibility = View.GONE
        binding.manualCodeText.visibility = View.GONE

        val parentHost = TailscaleAddressResolver.resolve()
        if (parentHost == null) {
            binding.statusText.setText(R.string.parent_pairing_tailscale_missing)
            binding.openTailscaleButton.visibility = View.VISIBLE
            binding.retryButton.visibility = View.VISIBLE
            return
        }

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

    private fun openTailscaleApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE_NAME)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE_NAME"))
            )
        }
    }

    companion object {
        private const val QR_SIZE_PX = 720
        private const val TAILSCALE_PACKAGE_NAME = "com.tailscale.ipn"
        private const val MANUAL_CODE_GROUP_SIZE = 4
    }
}
