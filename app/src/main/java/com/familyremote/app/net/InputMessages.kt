package com.familyremote.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire payloads for the remote-input-control feature's child->parent input messages plus the one
 * parent->child failure-feedback message, per docs/plans/README.md §4 and
 * docs/plans/remote-input-control/PLAN.md §4/§6/§8. Grouped in one file the same way
 * `SessionHandshakeMessages.kt` groups `SESSION_HELLO`/`SESSION_END` -- these all belong to the
 * same feature's wire contract.
 *
 * Coordinates are sent as raw parent-pixel integers (not normalized 0..1), per PLAN.md §4's
 * implementation choice: the parent already knows its own current frame dimensions, so
 * normalization would only add a round-trip precision concern with no benefit here.
 */

/** A single point of a swipe path, already scaled to parent-frame pixel coordinates. */
data class InputPathPoint(val x: Int, val y: Int)

/** `INPUT_TAP` (child->parent): a single tap, already scaled to parent-frame pixel coordinates. */
data class InputTapPayload(
    val x: Int,
    val y: Int
) {
    fun toJson(): String = JSONObject().apply {
        put("x", x)
        put("y", y)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.INPUT_TAP, toJson())

    companion object {
        fun parse(raw: String): InputTapPayload {
            val json = JSONObject(raw)
            return InputTapPayload(x = json.getInt("x"), y = json.getInt("y"))
        }
    }
}

/** `INPUT_SWIPE` (child->parent): a sampled path plus total duration, per PLAN.md §5. */
data class InputSwipePayload(
    val pathPoints: List<InputPathPoint>,
    val durationMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("pathPoints", JSONArray().apply {
            pathPoints.forEach { point ->
                put(JSONObject().apply {
                    put("x", point.x)
                    put("y", point.y)
                })
            }
        })
        put("durationMs", durationMs)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.INPUT_SWIPE, toJson())

    companion object {
        fun parse(raw: String): InputSwipePayload {
            val json = JSONObject(raw)
            val pointsJson = json.optJSONArray("pathPoints") ?: JSONArray()
            val points = (0 until pointsJson.length()).map { index ->
                val pointJson = pointsJson.getJSONObject(index)
                InputPathPoint(pointJson.getInt("x"), pointJson.getInt("y"))
            }
            return InputSwipePayload(points, json.optLong("durationMs", 0L))
        }
    }
}

/** `INPUT_TEXT` (child->parent): the complete composed string, per PLAN.md §8/Decision #9. */
data class InputTextPayload(
    val text: String
) {
    fun toJson(): String = JSONObject().apply {
        put("text", text)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.INPUT_TEXT, toJson())

    companion object {
        fun parse(raw: String): InputTextPayload {
            val json = JSONObject(raw)
            return InputTextPayload(text = json.optString("text", ""))
        }
    }
}

/**
 * `TEXT_INJECT_FAILED` (parent->child): sent when `INPUT_TEXT` injection fails because no
 * editable node currently has focus on the parent's screen, per PLAN.md §6/§8.
 */
data class TextInjectFailedPayload(
    val reason: String
) {
    fun toJson(): String = JSONObject().apply {
        put("reason", reason)
    }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.TEXT_INJECT_FAILED, toJson())

    companion object {
        const val REASON_NO_FOCUSED_FIELD = "NO_FOCUSED_FIELD"

        fun parse(raw: String): TextInjectFailedPayload {
            val json = JSONObject(raw)
            return TextInjectFailedPayload(reason = json.optString("reason", "unknown"))
        }
    }
}
