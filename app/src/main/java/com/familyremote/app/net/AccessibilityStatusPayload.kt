package com.familyremote.app.net

import org.json.JSONObject

/**
 * Payload of the `ACCESSIBILITY_STATUS` frame (parent->child), per docs/plans/README.md §4. Sent
 * whenever the parent-side Accessibility Service permission grant transitions (not every periodic
 * check tick), per docs/plans/remote-input-control/PLAN.md §7 -- drives the child's view-only mode.
 */
data class AccessibilityStatusPayload(
    val granted: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("granted", granted)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.ACCESSIBILITY_STATUS, toJson())

    companion object {
        fun parse(raw: String): AccessibilityStatusPayload {
            val json = JSONObject(raw)
            return AccessibilityStatusPayload(granted = json.optBoolean("granted", false))
        }
    }
}
