package com.familyremote.app.net

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Payload layout of a `VIDEO_FRAME` message (parent->child), per docs/plans/README.md §4:
 * `[4-byte frameWidth][4-byte frameHeight][8-byte presentationTimeUs][Annex-B NAL bytes]`.
 * Dimensions are repeated on every frame (cheap) so the child can detect a dimension change
 * (e.g. after a parent-side rotation -- see docs/plans/screen-rotation-handling/PLAN.md) without
 * a separate signaling message. See docs/plans/screen-sharing-core/PLAN.md §5.
 */
data class VideoFrameMessage(
    val frameWidth: Int,
    val frameHeight: Int,
    val presentationTimeUs: Long,
    val nalData: ByteArray
) {
    fun toFrame(): MessageFrame {
        val out = ByteArrayOutputStream(HEADER_SIZE_BYTES + nalData.size)
        DataOutputStream(out).apply {
            writeInt(frameWidth)
            writeInt(frameHeight)
            writeLong(presentationTimeUs)
            write(nalData)
        }
        return MessageFrame(MessageType.VIDEO_FRAME, out.toByteArray())
    }

    // Data class auto-generated equals/hashCode uses ByteArray reference identity for [nalData];
    // acceptable here since instances are never compared for content equality, only constructed
    // and immediately serialized via [toFrame].

    companion object {
        private const val HEADER_SIZE_BYTES = 4 + 4 + 8

        fun parse(frame: MessageFrame): VideoFrameMessage {
            require(frame.type == MessageType.VIDEO_FRAME) { "Not a VIDEO_FRAME message: ${frame.type}" }
            val buffer = ByteBuffer.wrap(frame.payload)
            val frameWidth = buffer.int
            val frameHeight = buffer.int
            val presentationTimeUs = buffer.long
            val nalData = ByteArray(buffer.remaining())
            buffer.get(nalData)
            return VideoFrameMessage(frameWidth, frameHeight, presentationTimeUs, nalData)
        }
    }
}
