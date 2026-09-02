package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.R
import com.familyremote.app.databinding.ActivityAccessibilityOnboardingBinding

/**
 * Onboarding step 2 (detailed-spec §13): guided screen shown when the parent-mode tab is opened
 * without the Accessibility Service permission granted. See PLAN.md §5.
 *
 * Non-blocking by design: Remote Input Control (Tier 2), which is what actually *uses* this
 * permission, is not implemented yet, so there is currently nothing for the OS Settings screen to
 * even offer enabling (no accessibility-service component is declared by this app in this tier).
 * Forcing a hard block here would make onboarding for this feature alone impossible to complete.
 * The "Continue" button is therefore always enabled; only the status text reflects whether the
 * permission happens to already be granted.
 */
class AccessibilityOnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessibilityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.continueButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val granted = AccessibilityPermissionCheck.isAnyAccessibilityServiceEnabledForThisApp(this)
        binding.statusText.setText(
            if (granted) {
                R.string.accessibility_onboarding_status_granted
            } else {
                R.string.accessibility_onboarding_status_not_granted
            }
        )
    }
}
