package com.familyremote.app.net

/**
 * Single shared message-type tag used by both roles, per docs/plans/README.md §4.
 * Only [PAIRING_HELLO]/[PAIRING_ACK] are actually implemented (encoded/decoded) by this feature;
 * the live-session values are declared here as the fixed wire-contract other, not-yet-implemented
 * features will bind to, so the byte-tag scheme doesn't drift/duplicate across worktrees.
 */
enum class MessageType(val tag: Byte) {
    // Live-session protocol (README.md §4 table) -- payload (de)serialization owned by the
    // feature that uses each type; not implemented here.
    SESSION_HELLO(1),
    VIDEO_FRAME(2),
    STATUS_HEARTBEAT(3),
    STATUS_EVENT(4),
    ACCESSIBILITY_STATUS(5),
    INPUT_TAP(6),
    INPUT_SWIPE(7),
    INPUT_TEXT(8),
    TEXT_INJECT_FAILED(9),
    SESSION_END(10),

    // Pairing-time-only exchange (README.md §4 "Pairing-time-only messages", PLAN.md §4) --
    // separate, short-lived connection, not part of the live-session protocol above.
    PAIRING_HELLO(100),
    PAIRING_ACK(101);

    companion object {
        fun fromTag(tag: Byte): MessageType =
            entries.firstOrNull { it.tag == tag }
                ?: throw IllegalArgumentException("Unknown MessageType tag: $tag")
    }
}
