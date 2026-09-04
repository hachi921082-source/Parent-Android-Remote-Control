package com.familyremote.app.pairing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.familyremote.app.common.AccessibilityPermissionMonitor

/**
 * Orchestrates onboarding steps 2->4 (detailed-spec §13), which are all performed physically
 * on the parent device -- see PLAN.md §5. Step 2 (accessibility + battery setup, merged into one
 * screen per issue #27) is skipped on re-entry once both are already granted -- both are now
 * directly, live-checkable, so no separate "acknowledged" flag is needed; step 4 (pairing
 * exchange) always runs since each new pairing needs a fresh handshake. Step 5 (label assignment)
 * is the child device's counterpart, handled separately by [ChildPairingScanActivity] /
 * [PairingLabelPromptActivity].
 */
class OnboardingWizardActivity : AppCompatActivity() {

    private var currentStep = Step.SETUP
    private lateinit var stepLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.familyremote.app.R.layout.activity_onboarding_wizard)

        stepLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                advanceToNextStep()
            } else {
                // User backed out of a step; abandon the whole wizard rather than guessing intent.
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        runCurrentStep()
    }

    private fun runCurrentStep() {
        when (currentStep) {
            Step.SETUP -> {
                if (AccessibilityPermissionMonitor.isGranted(this) && isIgnoringBatteryOptimizations()) {
                    advanceToNextStep()
                } else {
                    stepLauncher.launch(Intent(this, AccessibilityOnboardingActivity::class.java))
                }
            }
            Step.PAIRING -> {
                stepLauncher.launch(Intent(this, ParentPairingActivity::class.java))
            }
            Step.DONE -> {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun advanceToNextStep() {
        currentStep = currentStep.next()
        runCurrentStep()
    }

    private enum class Step {
        SETUP, PAIRING, DONE;

        fun next(): Step = entries[(ordinal + 1).coerceAtMost(entries.size - 1)]
    }
}
