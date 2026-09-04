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
 * Onboarding step 2 (detailed-spec §13): the single "Initial Setup" screen shown when the
 * parent-mode tab is opened without Accessibility Service, battery-optimization exemption, and/or
 * Tailscale all already in place. See PLAN.md §5. Covers what used to be three separate steps --
 * accessibility, background/battery ([MyOsSetupChecklistActivity], merged in per issue #27), and
 * Tailscale install/connect (formerly [ParentPairingActivity]'s own missing-Tailscale UI, merged
 * in per issue #28) -- since all three are one-time device-setup actions performed by the same
 * tech-savvy family member, not the elderly parent (issue #27). Includes ZTE-Nubia-S2-specific
 * auto-start instructions if that device is detected, otherwise no auto-start section at all
 * (there's no programmatic way to verify that OEM-specific setting, so it's only shown where it's
 * known to matter).
 *
 * Non-blocking by design: even though `ParentAccessibilityService` now exists
 * (docs/plans/remote-input-control/PLAN.md §6), forcing a hard block on this screen would still
 * make onboarding stuck-able for a parent who, for whatever reason, can't complete the OS
 * Settings step immediately. The "Continue" button is therefore always enabled; only the status
 * texts reflect whether each item happens to already be granted/connected. No retry button is
 * needed anywhere on this screen -- every status is re-checked live in [onResume], so simply
 * returning to this screen (e.g. after installing Tailscale) already refreshes it (issue #28).
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

        binding.installTailscaleButton.setOnClickListener { openTailscalePlayStoreListing() }
        binding.openTailscaleButton.setOnClickListener { openTailscaleApp() }

        binding.continueButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateBatteryStatus()
        updateTailscaleStatus()
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

    private fun updateTailscaleStatus() {
        val connected = TailscaleAddressResolver.resolve() != null
        binding.tailscaleStatusText.setText(
            if (connected) {
                R.string.parent_pairing_tailscale_status_connected
            } else {
                R.string.parent_pairing_tailscale_status_not_connected
            }
        )
    }

    private fun openTailscalePlayStoreListing() {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE_NAME"))
        )
    }

    private fun openTailscaleApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE_NAME)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            // Not installed yet -- same destination as installTailscaleButton, since tapping
            // "Open Tailscale" before installing is a plausible mis-click.
            openTailscalePlayStoreListing()
        }
    }

    companion object {
        private const val TAILSCALE_PACKAGE_NAME = "com.tailscale.ipn"
    }
}
