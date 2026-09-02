package com.familyremote.app.parent

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Layer-2 defense-in-depth check (docs/plans/screen-sharing-core/PLAN.md §7). Fires on an
 * `AlarmManager` wake-up independent of [ScreenShareForegroundService]'s own lifecycle. If the
 * durable `sessionWasActive` flag ([SessionStateStore]) says a session should still be running
 * but the service process is gone, restarts the service -- it cannot carry a `MediaProjection`
 * grant across that restart (a killed process invalidates the grant), so
 * [ScreenShareForegroundService] itself detects the missing grant on start and no-ops rather than
 * attempting capture (PLAN.md §10 Case 2).
 */
class SessionKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = SessionStateStore(context)
        if (!store.sessionWasActive()) {
            // Session already ended cleanly (or never started) -- nothing to keep alive.
            return
        }

        if (isServiceRunning(context)) {
            // Service is still alive -- reschedule the next check (AlarmManager wake-ups are
            // one-shot, not repeating, when using setExactAndAllowWhileIdle).
            SessionKeepAliveScheduler(context).schedule()
            return
        }

        // Service process is gone -- restart it (see class doc for why no grant can be restored).
        ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenShareForegroundService::class.java)
        )
    }

    @Suppress("DEPRECATION")
    // ActivityManager.getRunningServices is deprecated but remains the direct way for a process
    // to query its own service's running state; binding just to check would have side effects.
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == ScreenShareForegroundService::class.java.name }
    }
}
