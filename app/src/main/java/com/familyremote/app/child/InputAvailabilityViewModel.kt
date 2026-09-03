package com.familyremote.app.child

import com.familyremote.app.net.AccessibilityStatusPayload
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks `inputAvailable` (view-only mode), per docs/plans/remote-input-control/PLAN.md §7
 * (detailed-spec §11) -- a flag orthogonal to `ConnectionState` (docs/plans/README.md §5), layered
 * only on top of `Live`/`Reconnecting`. Kept as a small sibling to `ConnectionStateViewModel`
 * rather than folded into it, since it is driven entirely by frame content
 * (`ACCESSIBILITY_STATUS`, `TEXT_INJECT_FAILED`) that `ConnectionStateViewModel` already hands off
 * unparsed via its `onOtherMessage` callback.
 */
class InputAvailabilityViewModel {

    private val _inputAvailable = MutableStateFlow(true)
    /** Starts `true` (§7); flips on `ACCESSIBILITY_STATUS` receipt. */
    val inputAvailable: StateFlow<Boolean> = _inputAvailable.asStateFlow()

    private val _textInjectFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once per `TEXT_INJECT_FAILED {reason: NO_FOCUSED_FIELD}` receipt -- a transient,
     * per-attempt notice (PLAN.md §8), not a standing state like [inputAvailable]. */
    val textInjectFailed: SharedFlow<Unit> = _textInjectFailed.asSharedFlow()

    /** Feed every frame `ConnectionStateViewModel` doesn't itself parse (its `onOtherMessage`
     * callback); ignores any frame type it doesn't own (e.g. `VIDEO_FRAME`, handled separately by
     * the caller). */
    fun onFrame(frame: MessageFrame) {
        when (frame.type) {
            MessageType.ACCESSIBILITY_STATUS ->
                _inputAvailable.value = AccessibilityStatusPayload.parse(frame.payloadAsUtf8String()).granted
            MessageType.TEXT_INJECT_FAILED -> _textInjectFailed.tryEmit(Unit)
            else -> Unit
        }
    }

    /** Resets to the default assumption ("available") -- called at the start of a fresh `Live`
     * session, since the previous value belonged to a now-superseded connection. */
    fun reset() {
        _inputAvailable.value = true
    }
}
