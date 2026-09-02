package com.familyremote.app.pairing

import org.json.JSONObject
import java.util.UUID

/** Payload of the child->parent `PAIRING_HELLO` frame. See PLAN.md §4 step 3. */
data class PairingHelloPayload(
    val tempToken: String,
    val childHost: String,
    val proposedSecret: String
) {
    fun toJson(): String = JSONObject().apply {
        put("tempToken", tempToken)
        put("childHost", childHost)
        put("proposedSecret", proposedSecret)
    }.toString()

    companion object {
        fun parse(raw: String): PairingHelloPayload {
            val json = JSONObject(raw)
            return PairingHelloPayload(
                tempToken = json.getString("tempToken"),
                childHost = json.getString("childHost"),
                proposedSecret = json.getString("proposedSecret")
            )
        }
    }
}

/** Payload of the parent->child `PAIRING_ACK` frame. See PLAN.md §4 step 4. */
data class PairingAckPayload(
    val childId: UUID
) {
    fun toJson(): String = JSONObject().apply {
        put("childId", childId.toString())
    }.toString()

    companion object {
        fun parse(raw: String): PairingAckPayload {
            val json = JSONObject(raw)
            return PairingAckPayload(childId = UUID.fromString(json.getString("childId")))
        }
    }
}
