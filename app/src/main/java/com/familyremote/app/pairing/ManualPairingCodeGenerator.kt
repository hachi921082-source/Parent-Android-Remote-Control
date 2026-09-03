package com.familyremote.app.pairing

import java.security.SecureRandom

/**
 * Generates the manually-typeable pairing code shown alongside the QR code
 * (docs/plans/pc-pairing-code-support/PLAN.md §2). Uses Crockford Base32 so the code
 * is easy to type/read aloud and case/alias mistakes on the PC-client side can be
 * normalized away (docs/pc-client-detailed-spec.md §3).
 */
object ManualPairingCodeGenerator {
    // Crockford Base32 alphabet, excludes I, L, O, U (docs/pc-client-detailed-spec.md §3).
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_LENGTH = 8
    private val secureRandom = SecureRandom()

    fun generate(): String = (1..CODE_LENGTH)
        .map { ALPHABET[secureRandom.nextInt(ALPHABET.length)] }
        .joinToString("")
}
