package com.familyremote.app.pairing

import org.json.JSONObject
import java.util.UUID

/**
 * Child-side persisted pairing record. See PLAN.md §2.1.
 * One row = one paired parent device, as seen from this child device.
 */
data class PairingEntry(
    val id: UUID,
    var label: String,
    val parentHost: String,
    val sharedSecret: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("label", label)
        put("parentHost", parentHost)
        put("sharedSecret", sharedSecret)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): PairingEntry = PairingEntry(
            id = UUID.fromString(json.getString("id")),
            label = json.getString("label"),
            parentHost = json.getString("parentHost"),
            sharedSecret = json.getString("sharedSecret"),
            createdAt = json.getLong("createdAt")
        )
    }
}
