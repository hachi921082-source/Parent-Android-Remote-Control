package com.familyremote.app.net

import org.json.JSONObject

/**
 * `INPUT_KEY` (child/PC->parent): a single relayed key press, per
 * docs/plans/pc-key-relay-support/PLAN.md §2. Kept as its own small file since it's introduced by
 * a different plan than `InputMessages.kt`'s original three, even though it follows the same
 * file-per-feature convention.
 */
enum class RelayKey {
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    BACKSPACE, DELETE, ENTER, TAB, ESCAPE
}

data class InputKeyPayload(val key: RelayKey) {
    fun toJson(): String = JSONObject().apply { put("key", key.name) }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.INPUT_KEY, toJson())

    companion object {
        fun parse(raw: String): InputKeyPayload =
            InputKeyPayload(RelayKey.valueOf(JSONObject(raw).getString("key")))
    }
}
