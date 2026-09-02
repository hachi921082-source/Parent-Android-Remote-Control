package com.familyremote.app.pairing

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.databinding.ActivityChildPairingScanBinding
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch

/**
 * Child-side counterpart of [ParentPairingActivity]: scans the parent's QR code, then performs
 * the pairing handshake, then hands off to [PairingLabelPromptActivity]. See PLAN.md §4 steps 2-3, 5.
 */
class ChildPairingScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildPairingScanBinding

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner() else showPermissionNeeded()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildPairingScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantPermissionButton.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.scanAgainButton.setOnClickListener { checkPermissionAndScan() }

        checkPermissionAndScan()
    }

    private fun checkPermissionAndScan() {
        binding.grantPermissionButton.visibility = View.GONE
        binding.scanAgainButton.visibility = View.GONE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionNeeded() {
        binding.statusText.setText(R.string.child_pairing_camera_permission_rationale)
        binding.grantPermissionButton.visibility = View.VISIBLE
    }

    private fun launchScanner() {
        binding.statusText.text = ""
        IntentIntegrator(this).setOrientationLocked(false).initiateScan()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        if (scanResult.contents == null) {
            // User cancelled the scanner without producing a result.
            finish()
            return
        }
        handleScannedContents(scanResult.contents)
    }

    private fun handleScannedContents(contents: String) {
        val payload = PairingQrPayload.parse(contents)
        if (payload == null) {
            binding.statusText.setText(R.string.child_pairing_invalid_qr)
            binding.scanAgainButton.visibility = View.VISIBLE
            return
        }
        performHandshake(payload)
    }

    private fun performHandshake(payload: PairingQrPayload) {
        binding.statusText.setText(R.string.child_pairing_connecting)
        binding.progressBar.visibility = View.VISIBLE

        val childHost = TailscaleAddressResolver.resolve()
        if (childHost == null) {
            binding.progressBar.visibility = View.GONE
            binding.statusText.setText(R.string.child_pairing_tailscale_missing)
            binding.scanAgainButton.visibility = View.VISIBLE
            return
        }

        lifecycleScope.launch {
            val result = PairingClient().performHandshake(
                parentHost = payload.parentHost,
                port = payload.port,
                tempToken = payload.tempToken,
                childHost = childHost
            )
            binding.progressBar.visibility = View.GONE
            when (result) {
                is PairingClientResult.Success -> onHandshakeSuccess(payload, result)
                is PairingClientResult.Error -> {
                    binding.statusText.text = getString(R.string.child_pairing_error_generic, result.message)
                    binding.scanAgainButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onHandshakeSuccess(payload: PairingQrPayload, result: PairingClientResult.Success) {
        val intent = Intent(this, PairingLabelPromptActivity::class.java).apply {
            putExtra(PairingLabelPromptActivity.EXTRA_CHILD_ID, result.childId.toString())
            putExtra(PairingLabelPromptActivity.EXTRA_PARENT_HOST, payload.parentHost)
            putExtra(PairingLabelPromptActivity.EXTRA_SHARED_SECRET, result.sharedSecret)
        }
        startActivity(intent)
        setResult(Activity.RESULT_OK)
        finish()
    }
}
