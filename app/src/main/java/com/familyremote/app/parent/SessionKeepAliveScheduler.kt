package com.familyremote.app.parent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Registers the Layer-2 defense-in-depth periodic check (docs/plans/screen-sharing-core/PLAN.md
 * §7). Independent of [ScreenShareForegroundService]'s own lifecycle -- fires even if the
 * service's own in-process reconnect logic (Layer 1) never got a chance to run before being
 * killed. Uses `AlarmManager.setExactAndAllowWhileIdle()` rather than WorkManager's
 * `PeriodicWorkRequest`, which enforces a 15-minute minimum interval too coarse for this check
 * (PLAN.md §7).
 *
 * This is purely a backup mechanism -- it must never be able to take down the primary share-start
 * flow. On API 33+ (this app's target), `SCHEDULE_EXACT_ALARM` is not auto-granted; the user must
 * separately enable "Alarms & reminders" in system settings, which this app does not currently
 * prompt for. If that permission isn't available, [schedule] simply no-ops: Layer 2 doesn't run,
 * but Layer 1 (in-process reconnect) and the core capture/share flow are unaffected.
 */
class SessionKeepAliveScheduler(private val context: Context) {

    fun schedule() {
        // Below API 31 there is no exact-alarm permission gate -- the call is always allowed.
        // From API 31 onward, check canScheduleExactAlarms() (auto-granted pre-33, opt-in from
        // 33+) before attempting the call at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager().canScheduleExactAlarms()) {
            Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted -- Layer 2 keep-alive check skipped")
            return
        }

        try {
            alarmManager().setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                pendingIntent(context)
            )
        } catch (e: SecurityException) {
            // Second line of defense against an unexpected permission-state edge case (e.g. the
            // setting is revoked between the check above and this call) -- never let this crash
            // the just-started foreground service.
            Log.w(TAG, "setExactAndAllowWhileIdle threw SecurityException -- Layer 2 keep-alive check skipped", e)
        }
    }

    fun cancel() {
        alarmManager().cancel(pendingIntent(context))
    }

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        private const val REQUEST_CODE = 4210
        private const val TAG = "SessionKeepAliveSched"

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
