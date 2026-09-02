package com.familyremote.app.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * A single framed message: `[4-byte big-endian length][1-byte MessageType][payload bytes]`,
 * per docs/plans/README.md §4. `length` covers the type byte plus the payload.
 */
data class MessageFrame(
    val type: MessageType,
    val payload: ByteArray
) {
    fun writeTo(out: OutputStream) {
        val dataOut = DataOutputStream(out)
        val length = 1 + payload.size
        dataOut.writeInt(length)
        dataOut.writeByte(type.tag.toInt())
        dataOut.write(payload)
        dataOut.flush()
    }

    companion object {
        fun readFrom(input: InputStream): MessageFrame {
            val dataIn = DataInputStream(input)
            val length = dataIn.readInt()
            require(length in 1..MAX_FRAME_LENGTH) { "Invalid frame length: $length" }
            val typeTag = dataIn.readByte()
            val payload = ByteArray(length - 1)
            dataIn.readFully(payload)
            return MessageFrame(MessageType.fromTag(typeTag), payload)
        }

        /** Sanity ceiling to avoid allocating huge buffers on a malformed/garbage stream. */
        private const val MAX_FRAME_LENGTH = 16 * 1024 * 1024

        fun of(type: MessageType, payloadJson: String) =
            MessageFrame(type, payloadJson.toByteArray(StandardCharsets.UTF_8))
    }

    fun payloadAsUtf8String(): String = String(payload, StandardCharsets.UTF_8)
}
