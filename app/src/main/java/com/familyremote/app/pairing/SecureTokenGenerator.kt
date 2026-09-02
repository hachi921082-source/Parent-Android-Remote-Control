package com.familyremote.app.pairing

import android.util.Base64
import java.security.SecureRandom

/**
 * Generates random tokens used both for the short-lived `tempPairingToken` (PLAN.md §4 step 1)
 * and the long-lived, 256-bit `sharedSecret` (PLAN.md §2.3).
 */
object SecureTokenGenerator {

    private val secureRandom = SecureRandom()

    /** 256-bit (32 byte) value, base64url-encoded, per PLAN.md §2.3. */
    fun generateSharedSecret(): String = generateToken(byteCount = 32)

    /** Shorter-lived token used only to authenticate one pairing handshake. */
    fun generateTempPairingToken(): String = generateToken(byteCount = 24)

    private fun generateToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
