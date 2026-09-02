package com.familyremote.app.net

import org.json.JSONObject
import java.util.UUID

/**
 * Payload of the `SESSION_HELLO` frame. In this feature's direction (see
 * docs/plans/screen-sharing-core/PLAN.md §6 step 2), the parent is the side dialing out, so it
 * sends this first to identify/authenticate itself to the child it is connecting to.
 */
data class SessionHelloPayload(
    val childId: UUID,
    val sharedSecret: String
) {
    fun toJson(): String = JSONObject().apply {
        put("childId", childId.toString())
        put("sharedSecret", sharedSecret)
    }.toString()

    companion object {
        fun parse(raw: String): SessionHelloPayload {
            val json = JSONObject(raw)
            return SessionHelloPayload(
                childId = UUID.fromString(json.getString("childId")),
                sharedSecret = json.getString("sharedSecret")
            )
        }
    }
}

/**
 * Payload of the `SESSION_END` frame (either direction, per docs/plans/README.md §4).
 */
data class SessionEndPayload(
    val reason: String
) {
    fun toJson(): String = JSONObject().apply {
        put("reason", reason)
    }.toString()

    companion object {
        fun parse(raw: String): SessionEndPayload {
            val json = JSONObject(raw)
            return SessionEndPayload(reason = json.optString("reason", "unknown"))
        }
    }
}
