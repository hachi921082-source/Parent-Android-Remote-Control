package com.familyremote.app.parent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View
import com.familyremote.app.common.Constants

/**
 * Parent-side counterpart to the child's `TapMarkerOverlay` (issue #35): the child's own tap
 * feedback was never visible to the parent, leaving an elderly parent with no way to see where
 * they were being guided to tap. Added as a `TYPE_ACCESSIBILITY_OVERLAY` window by
 * [ParentAccessibilityService] (an `AccessibilityService`-only window type that needs no
 * `SYSTEM_ALERT_WINDOW` permission), sized to the full display so [showMarker]'s points -- already
 * in the same real-screen coordinate space `dispatchGesture` uses (see issue #37) -- can be drawn
 * directly with no further mapping. Never touchable (see [ParentAccessibilityService]'s window
 * flags), same as the child-side original.
 */
class ParentTapMarkerOverlay(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var points: List<PointF> = emptyList()
    private var currentAlpha = 0
    private var animator: ValueAnimator? = null

    /**
     * Shows a marker at [newPoints] (real parent-screen coordinates). A single point draws a
     * circle (tap); multiple points draw a stroked path (swipe). Mirrors the child-side
     * `TapMarkerOverlay.showMarker` behavior/timing exactly.
     *
     * Crashed live the first time this was exercised: `ParentAccessibilityService.handleTap`/
     * `handleSwipe` call this from `serviceScope`'s `Dispatchers.Default` background thread, but
     * `invalidate()`/`ValueAnimator.start()` both require this view's own UI thread --
     * `CalledFromWrongThreadException`. `post {}` is safe to call from any thread once a View is
     * attached to a window (true here for this overlay's whole lifetime, per
     * `ParentAccessibilityService.addTapMarkerOverlay`).
     */
    fun showMarker(newPoints: List<PointF>) {
        post {
            animator?.cancel()
            points = newPoints
            currentAlpha = MAX_ALPHA
            invalidate()
            animator = ValueAnimator.ofInt(MAX_ALPHA, 0).apply {
                duration = Constants.TAP_MARKER_FADE_MS
                addUpdateListener {
                    currentAlpha = it.animatedValue as Int
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() || currentAlpha <= 0) return
        paint.alpha = currentAlpha
        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, MARKER_RADIUS_PX, paint)
        } else {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val MAX_ALPHA = 255
        private const val MARKER_RADIUS_PX = 32f
        private const val STROKE_WIDTH_PX = 8f
    }
}
