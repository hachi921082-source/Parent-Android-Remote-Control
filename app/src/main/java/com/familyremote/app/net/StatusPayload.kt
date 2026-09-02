package com.familyremote.app.net

import org.json.JSONObject

/**
 * Payload shape shared by `STATUS_HEARTBEAT` (parent->child, every 5s) and `STATUS_EVENT`
 * (parent->child, sent immediately on change) -- both carry the same fields, per
 * docs/plans/README.md §4. See docs/plans/connection-status-and-resilience/PLAN.md §5.
 */
data class StatusPayload(
    val wifiConnected: Boolean,
    val networkType: String,
    val signalStrength: Int?,
    val ssid: String?,
    val timestampMs: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("wifiConnected", wifiConnected)
        put("networkType", networkType)
        put("signalStrength", signalStrength ?: JSONObject.NULL)
        put("ssid", ssid ?: JSONObject.NULL)
        put("timestampMs", timestampMs)
    }.toString()

    /** [isEvent] selects the wire `MessageType` -- same payload shape either way (README.md §4). */
    fun toFrame(isEvent: Boolean): MessageFrame =
        MessageFrame.of(if (isEvent) MessageType.STATUS_EVENT else MessageType.STATUS_HEARTBEAT, toJson())

    companion object {
        fun parse(raw: String): StatusPayload {
            val json = JSONObject(raw)
            return StatusPayload(
                wifiConnected = json.optBoolean("wifiConnected", false),
                networkType = json.optString("networkType", "NONE"),
                signalStrength = if (json.isNull("signalStrength")) null else json.optInt("signalStrength"),
                ssid = if (json.isNull("ssid")) null else json.optString("ssid"),
                timestampMs = json.optLong("timestampMs", 0L)
            )
        }
    }
}
