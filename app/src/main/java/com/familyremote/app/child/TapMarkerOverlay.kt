package com.familyremote.app.child

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.familyremote.app.common.Constants

/**
 * Lightweight overlay drawn above `VideoDecoderView`, showing local tap/swipe feedback per
 * docs/plans/remote-input-control/PLAN.md §5 (detailed-spec §10). Never intercepts touches itself
 * (not clickable, no touch listener) so events pass through to `VideoDecoderView` beneath it.
 */
class TapMarkerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var points: List<LetterboxLayoutCalculator.PointF> = emptyList()
    private var currentAlpha = 0
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Shows a marker at [newPoints] (child-local view coordinates -- the child's own raw touch
     * points, never anything echoed back from the parent, per PLAN.md §5). A single point draws a
     * circle (tap); multiple points draw a stroked path (swipe). If a marker is already fading, it
     * is cancelled and restarted at full opacity for the new point(s) -- never two markers shown
     * simultaneously.
     */
    fun showMarker(newPoints: List<LetterboxLayoutCalculator.PointF>) {
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
