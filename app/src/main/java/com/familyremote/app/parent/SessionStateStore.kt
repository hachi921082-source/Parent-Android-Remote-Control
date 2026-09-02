package com.familyremote.app.parent

import android.content.Context

/**
 * Durable (plain `SharedPreferences` -- nothing sensitive) record of whether a sharing session
 * should still be considered active. Read by [SessionKeepAliveReceiver] after a possible full
 * process-kill, where any in-memory state on [ScreenShareForegroundService] would be gone too.
 * See docs/plans/screen-sharing-core/PLAN.md §7 Layer 2.
 */
class SessionStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markActive() {
        prefs.edit()
            .putBoolean(KEY_SESSION_WAS_ACTIVE, true)
            .putLong(KEY_LAST_ACTIVE_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SESSION_WAS_ACTIVE)
            .remove(KEY_LAST_ACTIVE_AT_MS)
            .apply()
    }

    fun sessionWasActive(): Boolean = prefs.getBoolean(KEY_SESSION_WAS_ACTIVE, false)

    companion object {
        private const val PREFS_NAME = "session_keep_alive_prefs"
        private const val KEY_SESSION_WAS_ACTIVE = "session_was_active"
        private const val KEY_LAST_ACTIVE_AT_MS = "last_active_at_ms"
    }
}
