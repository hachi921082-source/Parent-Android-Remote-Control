package com.familyremote.app.common

/**
 * Dimensions of the parent's currently-captured screen, as most recently reported by a
 * `VIDEO_FRAME` header (docs/plans/README.md §4/§6). Drives both `VideoDecoderView`'s letterbox/
 * pillarbox layout and `TapInputController`'s coordinate scaling (docs/plans/remote-input-control/
 * PLAN.md §3/§4) -- both consumers must agree on the same value at all times, so this is the single
 * shared representation passed between them rather than each re-deriving it from raw frame headers.
 */
data class FrameGeometry(
    val parentFrameWidth: Int,
    val parentFrameHeight: Int
)
