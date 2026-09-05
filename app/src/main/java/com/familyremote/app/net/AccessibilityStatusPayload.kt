package com.familyremote.app.net

import org.json.JSONObject

/**
 * Payload of the `ACCESSIBILITY_STATUS` frame (parent->child), per docs/plans/README.md §4. Sent
 * whenever the parent-side Accessibility Service permission grant transitions (not every periodic
 * check tick), per docs/plans/remote-input-control/PLAN.md §7 -- drives the child's view-only mode.
 *
 * [hasFocusedInputField] was added for issue #38: whether the parent currently has a focused
 * editable field (the same check `ParentAccessibilityService.handleText()` already makes before
 * injecting text), so the child can show its message-input bar only when it would actually do
 * something -- `optBoolean` defaults it to `false` so an old cached/malformed payload degrades to
 * "hidden", not a false-positive shown bar.
 */
data class AccessibilityStatusPayload(
    val granted: Boolean,
    val hasFocusedInputField: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("granted", granted)
        put("hasFocusedInputField", hasFocusedInputField)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.ACCESSIBILITY_STATUS, toJson())

    companion object {
        fun parse(raw: String): AccessibilityStatusPayload {
            val json = JSONObject(raw)
            return AccessibilityStatusPayload(
                granted = json.optBoolean("granted", false),
                hasFocusedInputField = json.optBoolean("hasFocusedInputField", false)
            )
        }
    }
}
