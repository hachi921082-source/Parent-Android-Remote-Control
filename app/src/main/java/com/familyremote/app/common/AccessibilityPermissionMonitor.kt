package com.familyremote.app.common

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.familyremote.app.parent.ParentAccessibilityService

/**
 * Single shared Accessibility Service permission check, per docs/plans/remote-input-control/
 * PLAN.md §7 (detailed-spec §11). Checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for
 * [ParentAccessibilityService]'s exact component name -- a durable check that works even if that
 * service's own `onServiceConnected`/`onDestroy` callbacks were never reliably observed due to a
 * kill. Used by both the pre-flight check (before each injected gesture/text action) and the
 * periodic 30s check (`ScreenShareForegroundService`) described in PLAN.md §7, and reused as-is by
 * `docs/plans/pairing-and-onboarding/PLAN.md`'s onboarding screen (same check logic, different
 * callers) -- this class supersedes that feature's earlier local `AccessibilityPermissionCheck`
 * stub (docs/plans/README.md §8's forward-reference note), now that [ParentAccessibilityService]
 * actually exists to be checked for by name. Lives in `common` (rather than `parent`) since it is
 * called from both the `pairing` and `parent` packages.
 */
object AccessibilityPermissionMonitor {

    fun isGranted(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (TextUtils.isEmpty(enabledServices)) return false

        val target = ComponentName(context, ParentAccessibilityService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(target, ignoreCase = true) }
    }
}
