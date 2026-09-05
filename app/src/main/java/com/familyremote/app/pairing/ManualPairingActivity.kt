package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyremote.app.R
import com.familyremote.app.common.Constants
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityManualPairingBinding
import kotlinx.coroutines.launch

/**
 * Manual pairing code entry (issue #49) -- child-side counterpart to [ChildPairingScanActivity],
 * for when scanning the parent's QR code isn't possible (no camera permission, a bad-lighting/
 * reflective screen, or the parent's code was simply read aloud/written down). Android
 * counterpart of the PC client's `Pairing/ManualPairingView`: same two fields (host + code), same
 * client-side format pre-check gating the button, same handshake call -- [PairingServer] on the
 * parent side already treats both channels uniformly (see its own `isManualCodeShaped` check), so
 * no parent/wire-protocol change was needed for this.
 */
class ManualPairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManualPairingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePairButtonEnabled()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        binding.hostEditText.addTextChangedListener(watcher)
        binding.codeEditText.addTextChangedListener(watcher)

        binding.pairButton.setOnClickListener { performHandshake() }
    }

    private fun updatePairButtonEnabled() {
        val host = binding.hostEditText.text?.toString()?.trim().orEmpty()
        val normalizedCode = CrockfordBase32.normalize(binding.codeEditText.text?.toString().orEmpty())
        binding.pairButton.isEnabled = binding.hostEditText.isEnabled &&
            host.isNotEmpty() &&
            CrockfordBase32.isValidFormat(normalizedCode)
    }

    private fun performHandshake() {
        val host = binding.hostEditText.text?.toString()?.trim().orEmpty()
        val normalizedCode = CrockfordBase32.normalize(binding.codeEditText.text?.toString().orEmpty())

        setFormEnabled(false)
        binding.statusText.setText(R.string.child_pairing_connecting)
        binding.progressBar.visibility = View.VISIBLE

        val childHost = TailscaleAddressResolver.resolve()
        if (childHost == null) {
            binding.progressBar.visibility = View.GONE
            binding.statusText.setText(R.string.child_pairing_tailscale_missing)
            setFormEnabled(true)
            return
        }

        lifecycleScope.launch {
            val result = PairingClient().performHandshake(
                parentHost = host,
                port = Constants.PAIRING_PORT,
                tempToken = normalizedCode,
                childHost = childHost
            )
            binding.progressBar.visibility = View.GONE
            when (result) {
                is PairingClientResult.Success -> onHandshakeSuccess(host, result)
                is PairingClientResult.Error -> {
                    binding.statusText.text = getString(R.string.child_pairing_error_generic, result.message)
                    setFormEnabled(true)
                }
            }
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        binding.hostEditText.isEnabled = enabled
        binding.codeEditText.isEnabled = enabled
        updatePairButtonEnabled()
    }

    private fun onHandshakeSuccess(parentHost: String, result: PairingClientResult.Success) {
        val intent = Intent(this, PairingLabelPromptActivity::class.java).apply {
            putExtra(PairingLabelPromptActivity.EXTRA_CHILD_ID, result.childId.toString())
            putExtra(PairingLabelPromptActivity.EXTRA_PARENT_HOST, parentHost)
            putExtra(PairingLabelPromptActivity.EXTRA_SHARED_SECRET, result.sharedSecret)
        }
        startActivity(intent)
        setResult(Activity.RESULT_OK)
        finish()
    }
}
