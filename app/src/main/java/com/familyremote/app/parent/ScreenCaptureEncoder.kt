package com.familyremote.app.parent

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Wraps `MediaCodec` (H.264, `MediaFormat.MIMETYPE_VIDEO_AVC`) fed by a `Surface`-backed
 * `VirtualDisplay` input (the encoder's own input surface, avoiding an extra `ImageReader` copy
 * step -- docs/plans/screen-sharing-core/PLAN.md §5). Emits Annex-B NAL units via [onNalUnit] for
 * [ParentSessionServer] to frame as `VIDEO_FRAME` messages.
 */
class ScreenCaptureEncoder(
    private val onNalUnit: (data: ByteArray, presentationTimeUs: Long) -> Unit
) {
    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null

    lateinit var inputSurface: Surface
        private set

    fun start(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SEC)
        }

        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = newCodec.createInputSurface()

        val thread = HandlerThread("ScreenCaptureEncoderCallback").apply { start() }
        callbackThread = thread

        newCodec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface-input mode -- the codec pulls frames from [inputSurface] itself,
                    // there is nothing to feed here.
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        outputBuffer.get(data)
                        onNalUnit(data, info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    // Surfaced to the child as a stalled/lost video stream via the normal
                    // connection-monitoring path (PLAN.md §7) -- nothing session-specific to do
                    // here beyond letting the encoder itself fail silently for this frame.
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    // No-op -- SPS/PPS NAL units are forwarded like any other Annex-B output
                    // buffer via [onOutputBufferAvailable], no separate config-data handling
                    // needed.
                }
            },
            Handler(thread.looper)
        )

        newCodec.start()
        codec = newCodec
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        callbackThread?.quitSafely()
        callbackThread = null
    }

    companion object {
        /** Implementation default, tunable -- no spec-mandated value (PLAN.md §5, "~4-6 Mbps"). */
        private const val BITRATE_BPS = 5_000_000
        private const val FRAME_RATE_FPS = 30
        private const val KEY_FRAME_INTERVAL_SEC = 2
    }
}
