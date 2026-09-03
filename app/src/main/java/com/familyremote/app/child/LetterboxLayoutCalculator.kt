package com.familyremote.app.child

import android.graphics.RectF
import com.familyremote.app.common.FrameGeometry

/**
 * Pure geometry helpers for letterbox/pillarbox layout and tap-coordinate scaling, per
 * docs/plans/remote-input-control/PLAN.md §3/§4 (detailed-spec §15, Decision #10/#11). No Android
 * View/MediaCodec dependency -- kept pure so `VideoDecoderView` (rendering) and `TapInputController`
 * (input mapping) can both call the exact same math and never disagree about the current
 * `RenderedContentRect`.
 */
object LetterboxLayoutCalculator {

    /**
     * The sub-rectangle (in view-local pixel coordinates) within a [viewW]x[viewH] view where
     * [frame] is actually drawn without distortion, preserving [frame]'s aspect ratio and adding
     * black bars on the other axis (letterbox if [frame] is relatively wider than the view,
     * pillarbox if relatively narrower).
     */
    fun computeContentRect(viewW: Int, viewH: Int, frame: FrameGeometry): RectF {
        if (viewW <= 0 || viewH <= 0 || frame.parentFrameWidth <= 0 || frame.parentFrameHeight <= 0) {
            return RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        }
        val viewAspect = viewW.toFloat() / viewH
        val frameAspect = frame.parentFrameWidth.toFloat() / frame.parentFrameHeight
        return if (frameAspect > viewAspect) {
            // Frame relatively wider than view: fit to view width, black bars top/bottom.
            val h = viewW / frameAspect
            RectF(0f, (viewH - h) / 2, viewW.toFloat(), (viewH - h) / 2 + h)
        } else {
            // Frame relatively taller/narrower than view: fit to view height, black bars left/right.
            val w = viewH * frameAspect
            RectF((viewW - w) / 2, 0f, (viewW - w) / 2 + w, viewH.toFloat())
        }
    }

    /**
     * Maps a raw touch point (view-local pixel coordinates) to parent-frame pixel coordinates,
     * or `null` if the touch falls outside [contentRect] (the letterbox/pillarbox black-bar
     * regions) -- discarded rather than clamped, per Decision #11.
     */
    fun mapTouchToParentCoords(touchX: Float, touchY: Float, contentRect: RectF, frame: FrameGeometry): PointF? {
        if (!contentRect.contains(touchX, touchY)) return null
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return null
        val fracX = (touchX - contentRect.left) / contentRect.width()
        val fracY = (touchY - contentRect.top) / contentRect.height()
        return PointF(fracX * frame.parentFrameWidth, fracY * frame.parentFrameHeight)
    }

    /** Minimal float-pair holder -- avoids depending on `android.graphics.PointF`'s mutability
     * for what is otherwise a pure value here (kept as a small local type instead of a `Pair` for
     * named-field readability at call sites). */
    data class PointF(val x: Float, val y: Float)
}
