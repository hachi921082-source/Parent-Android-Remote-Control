package com.familyremote.app.pairing

import org.json.JSONObject
import java.util.UUID

/**
 * Parent-side persisted record of a child device allowed to connect to this parent.
 * See PLAN.md §2.2 / §8.1 (parent must know the child's address ahead of time so it can dial
 * out at share-start, per README.md §5 / Decision #12).
 */
data class AuthorizedChild(
    val childId: UUID,
    val childHost: String,
    val sharedSecret: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("childId", childId.toString())
        put("childHost", childHost)
        put("sharedSecret", sharedSecret)
    }

    companion object {
        fun fromJson(json: JSONObject): AuthorizedChild = AuthorizedChild(
            childId = UUID.fromString(json.getString("childId")),
            childHost = json.getString("childHost"),
            sharedSecret = json.getString("sharedSecret")
        )
    }
}
