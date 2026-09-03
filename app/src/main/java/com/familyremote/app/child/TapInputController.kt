package com.familyremote.app.child

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.familyremote.app.net.InputPathPoint
import com.familyremote.app.net.InputSwipePayload
import com.familyremote.app.net.InputTapPayload
import com.familyremote.app.net.MessageFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Captures tap/swipe gestures on [decoderView] and relays them to the parent as `INPUT_TAP`/
 * `INPUT_SWIPE`, per docs/plans/remote-input-control/PLAN.md §4/§5. Also drives [onValidGesture]
 * (local marker feedback, `TapMarkerOverlay`) for every non-discarded gesture, using the child's
 * own raw touch coordinates -- never anything echoed back from the parent.
 *
 * Discards (no send, no marker) whenever [isInputAllowed] returns false (outer `ConnectionState`
 * not `Live`, or `inputAvailable == false` -- PLAN.md §10/§7), or when the gesture's coordinates
 * fall outside the current rendered video content area (letterbox/pillarbox black bars,
 * Decision #11). For a swipe, if *any* sampled point falls outside the content area the whole
 * gesture is discarded (not just the out-of-bounds points) -- a gesture is never partially valid.
 */
class TapInputController(
    private val decoderView: VideoDecoderView,
    private val scope: CoroutineScope,
    private val isInputAllowed: () -> Boolean,
    private val sendFrame: suspend (MessageFrame) -> Boolean,
    private val onValidGesture: (List<LetterboxLayoutCalculator.PointF>) -> Unit
) : View.OnTouchListener {

    private val touchSlopPx = ViewConfiguration.get(decoderView.context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastSampleTimeMs = 0L

    /** (x, y) samples in child-local view coordinates, in order; first entry is always ACTION_DOWN. */
    private val pathPoints = mutableListOf<Pair<Float, Float>>()

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!isInputAllowed()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTimeMs = System.currentTimeMillis()
                lastSampleTimeMs = downTimeMs
                pathPoints.clear()
                pathPoints.add(downX to downY)
            }
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastSampleTimeMs >= SAMPLE_INTERVAL_MS && pathPoints.size < MAX_PATH_POINTS) {
                    lastSampleTimeMs = now
                    pathPoints.add(event.x to event.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                val durationMs = System.currentTimeMillis() - downTimeMs
                val displacement = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                if (displacement < touchSlopPx) {
                    handleTap(downX, downY)
                } else {
                    pathPoints.add(event.x to event.y) // ensure the released point is the path's end.
                    handleSwipe(pathPoints.toList(), durationMs)
                }
                pathPoints.clear()
            }
            MotionEvent.ACTION_CANCEL -> pathPoints.clear()
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val geometry = decoderView.frameGeometry.value ?: return
        val rect = decoderView.contentRect.value
        val mapped = LetterboxLayoutCalculator.mapTouchToParentCoords(x, y, rect, geometry) ?: return
        onValidGesture(listOf(LetterboxLayoutCalculator.PointF(x, y)))
        scope.launch { sendFrame(InputTapPayload(mapped.x.roundToInt(), mapped.y.roundToInt()).toFrame()) }
    }

    private fun handleSwipe(localPoints: List<Pair<Float, Float>>, durationMs: Long) {
        val geometry = decoderView.frameGeometry.value ?: return
        val rect = decoderView.contentRect.value
        val mappedPoints = mutableListOf<InputPathPoint>()
        for ((x, y) in localPoints) {
            val mapped = LetterboxLayoutCalculator.mapTouchToParentCoords(x, y, rect, geometry) ?: return
            mappedPoints.add(InputPathPoint(mapped.x.roundToInt(), mapped.y.roundToInt()))
        }
        onValidGesture(localPoints.map { (x, y) -> LetterboxLayoutCalculator.PointF(x, y) })
        scope.launch { sendFrame(InputSwipePayload(mappedPoints, durationMs).toFrame()) }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 16L
        private const val MAX_PATH_POINTS = 64
    }
}
