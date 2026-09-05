package com.familyremote.app.pairing

/**
 * Manual pairing code normalization/validation (issue #49) -- ported line-for-line from the PC
 * client's `Pairing/CrockfordBase32.cs` so both sides accept exactly the same typed input.
 * [ManualPairingCodeGenerator] only ever generates codes (the parent-side display case); this is
 * the receiving side's counterpart, needed now that the child can type a code in too.
 */
object CrockfordBase32 {
    // Same alphabet as ManualPairingCodeGenerator (Crockford Base32, excludes I, L, O, U).
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_LENGTH = 8

    /**
     * Strips separators/whitespace, uppercases, then aliases the common look-alike mistakes
     * (I/L -> 1, O -> 0). 'U' has no alias -- Crockford's alphabet simply excludes it rather than
     * mapping it to anything, so a mistyped 'U' is left as-is and will not match a real code
     * (intentional, matches the PC-side behavior exactly).
     */
    fun normalize(input: String): String =
        input.trim().replace("-", "").replace(" ", "").uppercase().map { c ->
            when (c) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> c
            }
        }.joinToString("")

    /**
     * Client-side pre-flight format check only (exactly [CODE_LENGTH] chars, all in [ALPHABET]) --
     * the parent's [PairingServer] remains the sole authority on whether a code actually matches.
     */
    fun isValidFormat(normalized: String): Boolean =
        normalized.length == CODE_LENGTH && normalized.all { it in ALPHABET }
}
