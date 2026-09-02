package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.databinding.ActivityMyosSetupChecklistBinding

/**
 * Onboarding step 3 (detailed-spec §13/§2): a manually-ticked checklist, shown once per parent
 * device -- with ZTE-Nubia-S2-specific instructions if that device is detected, otherwise a
 * generic defensive fallback. See PLAN.md §6. There is no programmatic way to verify these
 * OEM-specific settings were actually applied (purely informational/guided).
 */
class MyOsSetupChecklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyosSetupChecklistBinding
    private val isRecognizedDevice = DeviceDetector.isZteNubiaS2()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyosSetupChecklistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.introText.setText(
            if (isRecognizedDevice) R.string.myos_checklist_intro_specific else R.string.myos_checklist_intro_generic
        )

        // The battery-usage deep link (ACTION_APPLICATION_DETAILS_SETTINGS) is a standard Android
        // intent that works on any device, so it's offered regardless of device recognition.
        binding.batterySettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        if (isRecognizedDevice) {
            binding.autoStartStepText.visibility = android.view.View.VISIBLE
            binding.autoStartCheckbox.visibility = android.view.View.VISIBLE
        } else {
            binding.genericAckCheckbox.visibility = android.view.View.VISIBLE
        }

        binding.continueButton.setOnClickListener {
            markAcknowledged()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun markAcknowledged() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACKNOWLEDGED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "onboarding_prefs"
        private const val KEY_ACKNOWLEDGED = "myos_checklist_acknowledged_v1"

        /** Used by [OnboardingWizardActivity] to skip this step on re-entry, per PLAN.md §5. */
        fun isAcknowledged(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ACKNOWLEDGED, false)
    }
}
