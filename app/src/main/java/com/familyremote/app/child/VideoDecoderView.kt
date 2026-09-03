package com.familyremote.app.child

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.familyremote.app.common.FrameGeometry
import com.familyremote.app.net.VideoFrameMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decodes the incoming Annex-B H.264 `VIDEO_FRAME` stream and renders it letterboxed/pillarboxed
 * within this view's bounds, per docs/plans/remote-input-control/PLAN.md §3. `TextureView` (not
 * plain `SurfaceView`) specifically because it supports the arbitrary matrix transform
 * ([setTransform]) needed for the letterbox fit without an extra composition step.
 *
 * [onVideoFrame] is expected to be called from a background thread (the session-channel read
 * loop) -- decoder feeding happens synchronously on that same thread for simplicity; the one View
 * API call Android requires to run on the UI thread ([setTransform]) is explicitly posted there.
 */
class VideoDecoderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = null
    private var configuredGeometry: FrameGeometry? = null

    private val _frameGeometry = MutableStateFlow<FrameGeometry?>(null)
    /** Latest [FrameGeometry] reported by the video stream -- consumed by `TapInputController` for
     * coordinate scaling (PLAN.md §4), kept in lockstep with [contentRect] below. */
    val frameGeometry: StateFlow<FrameGeometry?> = _frameGeometry.asStateFlow()

    private val _contentRect = MutableStateFlow(RectF())
    /** Current `RenderedContentRect` (view-local pixel coordinates) -- see
     * [LetterboxLayoutCalculator]. Recomputed on view resize and on frame-dimension change. */
    val contentRect: StateFlow<RectF> = _contentRect.asStateFlow()

    init {
        surfaceTextureListener = this
    }

    /** Feeds one decoded video frame; call from a background thread (see class doc). */
    fun onVideoFrame(message: VideoFrameMessage) {
        val newGeometry = FrameGeometry(message.frameWidth, message.frameHeight)
        if (_frameGeometry.value != newGeometry) {
            _frameGeometry.value = newGeometry
            reconfigureDecoder(newGeometry)
            recomputeContentRect(newGeometry)
        }
        feedDecoder(message.nalData, message.presentationTimeUs)
    }

    private fun reconfigureDecoder(geometry: FrameGeometry) {
        if (configuredGeometry == geometry && decoder != null) return
        val surface = outputSurface ?: return // not ready yet -- retried from onSurfaceTextureAvailable.
        releaseDecoder()
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                geometry.parentFrameWidth,
                geometry.parentFrameHeight
            )
            val newDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // No csd-0/csd-1 supplied -- SPS/PPS NAL units arrive in-band (Annex-B start codes) as
            // part of the ordinary NAL stream, the same way ScreenCaptureEncoder emits them on the
            // parent side; Android's AVC decoder accepts in-band parameter sets this way.
            newDecoder.configure(format, surface, null, 0)
            newDecoder.start()
            decoder = newDecoder
            configuredGeometry = geometry
        } catch (e: Exception) {
            // Decoder setup failed for this geometry (e.g. transient codec resource contention) --
            // leave decoder null; the next onVideoFrame call retries since configuredGeometry
            // stays null here (releaseDecoder() below already cleared it via the catch path).
            releaseDecoder()
        }
    }

    private fun feedDecoder(nalData: ByteArray, presentationTimeUs: Long) {
        val activeDecoder = decoder ?: return
        try {
            val inputIndex = activeDecoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = activeDecoder.getInputBuffer(inputIndex)
                buffer?.clear()
                buffer?.put(nalData)
                activeDecoder.queueInputBuffer(inputIndex, 0, nalData.size, presentationTimeUs, 0)
            }
            drainOutput(activeDecoder)
        } catch (e: IllegalStateException) {
            // Decoder was released/reconfigured concurrently (e.g. a dimension change landed
            // mid-feed) -- the next frame's onVideoFrame call reconfigures and retries.
        }
    }

    private fun drainOutput(activeDecoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = activeDecoder.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) break
            activeDecoder.releaseOutputBuffer(outputIndex, true) // true = render directly to the Surface.
        }
    }

    private fun recomputeContentRect(geometry: FrameGeometry) {
        if (width == 0 || height == 0) return
        val rect = LetterboxLayoutCalculator.computeContentRect(width, height, geometry)
        _contentRect.value = rect
        applyTransform(rect)
    }

    private fun applyTransform(rect: RectF) {
        if (width == 0 || height == 0) return
        val matrix = Matrix()
        val scaleX = rect.width() / width.toFloat()
        val scaleY = rect.height() / height.toFloat()
        matrix.setScale(scaleX, scaleY)
        matrix.postTranslate(rect.left, rect.top)
        // setTransform must run on this View's UI thread; onVideoFrame may be called from a
        // background (session-channel read loop) thread, so this hop is deliberate.
        post { setTransform(matrix) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        _frameGeometry.value?.let { recomputeContentRect(it) }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        outputSurface = Surface(st)
        _frameGeometry.value?.let { reconfigureDecoder(it) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        _frameGeometry.value?.let { recomputeContentRect(it) }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releaseDecoder()
        outputSurface?.release()
        outputSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private fun releaseDecoder() {
        decoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        decoder = null
        configuredGeometry = null
    }

    /** Called by the hosting screen (`SessionActivity`) when the session ends -- releases decoder
     * resources without waiting for [onSurfaceTextureDestroyed] (the View itself may outlive a
     * single session, e.g. re-used across a Reconnecting -> Live transition). */
    fun release() {
        releaseDecoder()
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
