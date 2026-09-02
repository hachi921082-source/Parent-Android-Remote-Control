package com.familyremote.app.parent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Registers the Layer-2 defense-in-depth periodic check (docs/plans/screen-sharing-core/PLAN.md
 * §7). Independent of [ScreenShareForegroundService]'s own lifecycle -- fires even if the
 * service's own in-process reconnect logic (Layer 1) never got a chance to run before being
 * killed. Uses `AlarmManager.setExactAndAllowWhileIdle()` rather than WorkManager's
 * `PeriodicWorkRequest`, which enforces a 15-minute minimum interval too coarse for this check
 * (PLAN.md §7).
 */
class SessionKeepAliveScheduler(private val context: Context) {

    fun schedule() {
        alarmManager().setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel() {
        alarmManager().cancel(pendingIntent(context))
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        private const val REQUEST_CODE = 4210

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, SessionKeepAliveReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
