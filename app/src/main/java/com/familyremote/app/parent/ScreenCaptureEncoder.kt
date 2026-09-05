package com.familyremote.app.parent

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps `MediaCodec` (H.264, `MediaFormat.MIMETYPE_VIDEO_AVC`) fed via buffer-input (YUV420,
 * converted in software from an `ImageReader`-backed `VirtualDisplay` capture -- issue #30).
 *
 * The original implementation used `COLOR_FormatSurface` (the encoder's own zero-copy input
 * surface). That is the platform-documented, normally-preferred approach, but real-device testing
 * found multiple Qualcomm hardware AVC encoders (three different chips/driver generations
 * confirmed: `c2.qti.avc.encoder`'s V4L2 HAL logging "OPAQUE is not a supported pixel format!", and
 * the legacy `OMX.qcom.video.encoder.avc` path logging a matching `C2DColorConvert` warning) that
 * silently mishandle that opaque/private buffer format -- the encoder starts and runs with no
 * error ever surfaced to the app, but the decoded stream is solid black. Buffer-input with an
 * explicit YUV420 format is not vendor-opaque, so it sidesteps that whole class of bug; the actual
 * H.264 compression is still hardware-accelerated, only the RGBA->YUV420 color conversion is done
 * in software (in [convertRgbaToI420]).
 */
class ScreenCaptureEncoder(
    private val onNalUnit: (data: ByteArray, presentationTimeUs: Long) -> Unit
) {
    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private var imageReaderThread: HandlerThread? = null
    private var imageReader: ImageReader? = null

    private val latestFrame = AtomicReference<Yuv420Frame?>(null)

    lateinit var inputSurface: Surface
        private set

    fun start(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SEC)
        }

        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val readerThread = HandlerThread("ScreenCaptureImageReader").apply { start() }
        imageReaderThread = readerThread
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        reader.setOnImageAvailableListener({ onImageAvailable(reader) }, Handler(readerThread.looper))
        imageReader = reader
        inputSurface = reader.surface

        val thread = HandlerThread("ScreenCaptureEncoderCallback").apply { start() }
        callbackThread = thread

        newCodec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    feedInputBuffer(codec, index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    // Same stop()-vs-in-flight-callback race as feedInputBuffer above.
                    runCatching {
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

    private fun onImageAvailable(reader: ImageReader) {
        // stop() can close() the ImageReader (and release its images) from a different thread
        // while this callback is still mid-read -- same class of race as feedInputBuffer/
        // onOutputBufferAvailable above, just on the reader side this time.
        runCatching {
            val image = reader.acquireLatestImage() ?: return
            try {
                latestFrame.set(convertRgbaToI420(image))
            } finally {
                image.close()
            }
        }
    }

    /** Feeds the most recently captured frame into [index], or a single black frame if the
     * display hasn't produced one yet -- every offered input index must be queued or the codec
     * eventually stalls with no free buffers left. */
    private fun feedInputBuffer(codec: MediaCodec, index: Int) {
        // stop() runs on a different thread and can transition the codec out of the Executing
        // state while a callback for an already-offered index is still in flight -- both calls
        // below then throw IllegalStateException, which must not crash the app.
        runCatching {
            val image = codec.getInputImage(index) ?: return
            val frame = latestFrame.get() ?: Yuv420Frame.black(image.width, image.height)
            writeI420ToImage(frame, image)
            codec.queueInputBuffer(index, 0, frame.y.size + frame.u.size + frame.v.size, System.nanoTime() / 1000, 0)
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        callbackThread?.quitSafely()
        callbackThread = null
        imageReader?.close()
        imageReader = null
        imageReaderThread?.quitSafely()
        imageReaderThread = null
        latestFrame.set(null)
    }

    private class Yuv420Frame(val y: ByteArray, val u: ByteArray, val v: ByteArray, val width: Int, val height: Int) {
        companion object {
            fun black(width: Int, height: Int): Yuv420Frame {
                val uvWidth = (width + 1) / 2
                val uvHeight = (height + 1) / 2
                return Yuv420Frame(
                    ByteArray(width * height),
                    ByteArray(uvWidth * uvHeight) { 128.toByte() },
                    ByteArray(uvWidth * uvHeight) { 128.toByte() },
                    width,
                    height
                )
            }
        }
    }

    companion object {
        /** Implementation default, tunable -- no spec-mandated value (PLAN.md §5, "~4-6 Mbps"). */
        private const val BITRATE_BPS = 5_000_000
        private const val FRAME_RATE_FPS = 30
        private const val KEY_FRAME_INTERVAL_SEC = 2
        private const val IMAGE_READER_MAX_IMAGES = 2

        /** BT.601 full-swing RGB->YUV420 (I420: full-size Y, quarter-size U/V), reading the
         * `ImageReader`'s single interleaved RGBA plane (respecting its own row/pixel stride,
         * which is not always `width * 4`). */
        private fun convertRgbaToI420(image: Image): Yuv420Frame {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val uvWidth = (width + 1) / 2
            val uvHeight = (height + 1) / 2
            val yPlane = ByteArray(width * height)
            val uPlane = ByteArray(uvWidth * uvHeight)
            val vPlane = ByteArray(uvWidth * uvHeight)
            val rowBytes = ByteArray(rowStride)

            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowBytes, 0, minOf(rowStride, buffer.remaining()))
                for (col in 0 until width) {
                    val offset = col * pixelStride
                    val r = rowBytes[offset].toInt() and 0xFF
                    val g = rowBytes[offset + 1].toInt() and 0xFF
                    val b = rowBytes[offset + 2].toInt() and 0xFF

                    val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yPlane[row * width + col] = yValue.coerceIn(0, 255).toByte()

                    if (row % 2 == 0 && col % 2 == 0) {
                        val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        val uvIndex = (row / 2) * uvWidth + (col / 2)
                        uPlane[uvIndex] = uValue.coerceIn(0, 255).toByte()
                        vPlane[uvIndex] = vValue.coerceIn(0, 255).toByte()
                    }
                }
            }
            return Yuv420Frame(yPlane, uPlane, vPlane, width, height)
        }

        /** Writes [frame] into the encoder's own `Image` planes, respecting their row/pixel
         * stride -- these commonly differ from [frame]'s tightly-packed arrays, and the concrete
         * planar-vs-semiplanar layout is intentionally left to the encoder (that's what makes this
         * `COLOR_FormatYUV420Flexible`-safe rather than assuming one fixed byte layout). */
        private fun writeI420ToImage(frame: Yuv420Frame, image: Image) {
            val uvWidth = (frame.width + 1) / 2
            val uvHeight = (frame.height + 1) / 2
            writePlane(frame.y, frame.width, frame.height, image.planes[0])
            writePlane(frame.u, uvWidth, uvHeight, image.planes[1])
            writePlane(frame.v, uvWidth, uvHeight, image.planes[2])
        }

        private fun writePlane(src: ByteArray, width: Int, height: Int, plane: Image.Plane) {
            val dst: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var srcIndex = 0
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    dst.put(rowStart + col * pixelStride, src[srcIndex + col])
                }
                srcIndex += width
            }
        }
    }
}
