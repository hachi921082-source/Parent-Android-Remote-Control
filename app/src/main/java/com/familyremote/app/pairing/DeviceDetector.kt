package com.familyremote.app.pairing

import android.os.Build

/**
 * Detects whether the running device is the ZTE Nubia S2 (MyOS), which has confirmed aggressive
 * background-kill behavior requiring the manual steps in detailed-spec §2 / PLAN.md §6. Any other
 * device falls back to a generic, defensive checklist (unrecognized devices, including the AQUOS
 * Wish4 which has no confirmed steps of its own -- see docs/QA.md verification-TODO).
 */
object DeviceDetector {

    fun isZteNubiaS2(): Boolean {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return manufacturer.contains("ZTE", ignoreCase = true) &&
            model.contains("Nubia S2", ignoreCase = true)
    }
}
