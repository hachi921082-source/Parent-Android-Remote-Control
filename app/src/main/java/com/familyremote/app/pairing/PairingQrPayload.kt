package com.familyremote.app.pairing

import org.json.JSONObject

/**
 * The JSON payload encoded into the QR code shown by [ParentPairingActivity] and read by
 * [ChildPairingScanActivity]. See PLAN.md §4 step 1.
 */
data class PairingQrPayload(
    val v: Int,
    val parentHost: String,
    val port: Int,
    val tempToken: String
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v)
        put("parentHost", parentHost)
        put("port", port)
        put("tempToken", tempToken)
    }.toString()

    companion object {
        const val VERSION = 1

        /** Returns null (rather than throwing) on any malformed/unexpected QR content. */
        fun parse(raw: String): PairingQrPayload? = runCatching {
            val json = JSONObject(raw)
            PairingQrPayload(
                v = json.getInt("v"),
                parentHost = json.getString("parentHost"),
                port = json.getInt("port"),
                tempToken = json.getString("tempToken")
            )
        }.getOrNull()
    }
}
