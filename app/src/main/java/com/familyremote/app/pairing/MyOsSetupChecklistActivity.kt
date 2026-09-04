package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.common.applyNavigationBarBottomInsetMargin
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityMyosSetupChecklistBinding

/**
 * Onboarding step 3 (detailed-spec §13/§2): guided instructions, shown once per parent device --
 * with ZTE-Nubia-S2-specific instructions if that device is detected, otherwise a generic
 * defensive fallback. See PLAN.md §6. There is no programmatic way to verify these OEM-specific
 * settings were actually applied, so this step is purely informational/guided -- no confirmation
 * checkbox is shown, since a checkbox that can't actually verify completion would just invite
 * elderly users to tick it without doing the underlying step (issue #23).
 */
class MyOsSetupChecklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyosSetupChecklistBinding
    private val isRecognizedDevice = DeviceDetector.isZteNubiaS2()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyosSetupChecklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()
        binding.continueButton.applyNavigationBarBottomInsetMargin()

        binding.introText.setText(
            if (isRecognizedDevice) R.string.myos_checklist_intro_specific else R.string.myos_checklist_intro_generic
        )

        // Deep-links straight to the system's "allow unrestricted battery usage" dialog for this
        // app (issue #26) -- falls back to the generic App Info screen if that intent can't be
        // resolved (some OEM builds may not support it).
        binding.batterySettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
        }

        if (isRecognizedDevice) {
            binding.autoStartStepText.visibility = android.view.View.VISIBLE
        }

        binding.continueButton.setOnClickListener {
            markAcknowledged()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val ignoringOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
        binding.batteryStatusText.setText(
            if (ignoringOptimizations) {
                R.string.myos_checklist_battery_status_granted
            } else {
                R.string.myos_checklist_battery_status_not_granted
            }
        )
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
