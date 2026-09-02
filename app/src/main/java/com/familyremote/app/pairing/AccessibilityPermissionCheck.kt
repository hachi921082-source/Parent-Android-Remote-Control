package com.familyremote.app.pairing

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

/**
 * FORWARD-REFERENCE STUB (docs/plans/README.md §8): Remote Input Control (Tier 2, not yet
 * implemented) will own a shared `common.AccessibilityPermissionMonitor` that this onboarding
 * screen should eventually delegate to, once that feature's `ParentAccessibilityService`
 * component actually exists to be checked for by name.
 *
 * Until then, this is a local, standalone check using the same one-liner the real class will
 * wrap (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`). Because no accessibility-service
 * component is declared by this app yet, this checks only whether *any* component of this app's
 * own package is present in the colon-separated enabled-services list -- it cannot check for a
 * specific service class name until Tier 2 lands and defines one. Do not extend this into a
 * parallel/duplicate permission-check design; swap the call site in
 * [AccessibilityOnboardingActivity] for the real `AccessibilityPermissionMonitor` once Tier 2 is
 * merged.
 */
object AccessibilityPermissionCheck {

    fun isAnyAccessibilityServiceEnabledForThisApp(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (TextUtils.isEmpty(enabledServices)) return false

        val packagePrefix = "${context.packageName}/"
        return enabledServices
            .split(':')
            .any { it.startsWith(packagePrefix) }
    }
}
