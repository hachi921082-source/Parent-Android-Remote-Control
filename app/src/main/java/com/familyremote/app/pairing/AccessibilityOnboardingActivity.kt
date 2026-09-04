package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.common.AccessibilityPermissionMonitor
import com.familyremote.app.common.applyNavigationBarBottomInsetMargin
import com.familyremote.app.common.applyStatusBarTopInsetPadding
import com.familyremote.app.databinding.ActivityAccessibilityOnboardingBinding

/**
 * Onboarding step 2 (detailed-spec §13): guided screen shown when the parent-mode tab is opened
 * without the Accessibility Service permission and/or battery-optimization exemption granted.
 * See PLAN.md §5. Also covers the former standalone battery/background-operation checklist step
 * (previously [MyOsSetupChecklistActivity], merged in here since both are one-time device-setup
 * actions performed by the same tech-savvy family member, not the elderly parent -- see issue #27)
 * -- including ZTE-Nubia-S2-specific auto-start instructions if that device is detected, otherwise
 * no auto-start section at all (there's no programmatic way to verify that OEM-specific setting,
 * so it's only shown where it's known to matter).
 *
 * Non-blocking by design: even though `ParentAccessibilityService` now exists
 * (docs/plans/remote-input-control/PLAN.md §6), forcing a hard block on this screen would still
 * make onboarding stuck-able for a parent who, for whatever reason, can't complete the OS
 * Settings step immediately. The "Continue" button is therefore always enabled; only the status
 * texts reflect whether each permission happens to already be granted.
 */
class AccessibilityOnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilityOnboardingBinding
    private val isRecognizedDevice = DeviceDetector.isZteNubiaS2()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyStatusBarTopInsetPadding()
        binding.continueButton.applyNavigationBarBottomInsetMargin()

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

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
            binding.autoStartStepText.visibility = View.VISIBLE
        }

        binding.continueButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateBatteryStatus()
    }

    private fun updateAccessibilityStatus() {
        val granted = AccessibilityPermissionMonitor.isGranted(this)
        binding.statusText.setText(
            if (granted) {
                R.string.accessibility_onboarding_status_granted
            } else {
                R.string.accessibility_onboarding_status_not_granted
            }
        )
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
}
