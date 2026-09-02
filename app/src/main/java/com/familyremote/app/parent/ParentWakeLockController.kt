package com.familyremote.app.parent

import android.content.Context
import android.os.PowerManager

/**
 * Acquires/releases the screen-stays-on wake lock tied to [ScreenShareForegroundService]'s
 * active lifetime. See docs/plans/screen-sharing-core/PLAN.md §8.
 */
class ParentWakeLockController(context: Context) {

    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    // SCREEN_BRIGHT_WAKE_LOCK is deprecated in the SDK but remains the only direct Service-level
    // API that keeps the *screen* (not just the CPU) on without a foreground Activity -- this app
    // has no other reason to keep an Activity alive during a session. See PLAN.md §8.
    fun acquire() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val WAKE_LOCK_TAG = "FamilyRemote:ScreenShareWakeLock"
    }
}
