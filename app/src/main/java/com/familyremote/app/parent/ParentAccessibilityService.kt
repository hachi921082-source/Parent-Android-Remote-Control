package com.familyremote.app.parent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familyremote.app.common.AccessibilityPermissionMonitor
import com.familyremote.app.net.InputSwipePayload
import com.familyremote.app.net.InputTapPayload
import com.familyremote.app.net.InputTextPayload
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.TextInjectFailedPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Parent-side `AccessibilityService` (docs/plans/remote-input-control/PLAN.md §6): receives
 * `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` frames (already deframed by `ParentSessionServer`, routed
 * here via [ScreenShareForegroundService]'s companion reference -- see [onServiceConnected]/
 * [onDestroy]) and performs `dispatchGesture`/`ACTION_SET_TEXT`. Runs in the same process as the
 * rest of the app, no `android:process` isolation, to simplify this in-process hand-off.
 */
class ParentAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenShareForegroundService.attachAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used -- gesture/text injection is driven entirely by inbound INPUT_* network frames
        // (see [onInputFrameReceived]), not by observed accessibility events.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        ScreenShareForegroundService.detachAccessibilityService(this)
        serviceJob.cancel()
    }

    /** Called by [ScreenShareForegroundService] for every `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT`
     * frame received from a connected child, per PLAN.md §6. */
    fun onInputFrameReceived(frame: MessageFrame) {
        serviceScope.launch {
            when (frame.type) {
                MessageType.INPUT_TAP -> handleTap(InputTapPayload.parse(frame.payloadAsUtf8String()))
                MessageType.INPUT_SWIPE -> handleSwipe(InputSwipePayload.parse(frame.payloadAsUtf8String()))
                MessageType.INPUT_TEXT -> handleText(InputTextPayload.parse(frame.payloadAsUtf8String()))
                else -> Unit // Not this service's concern.
            }
        }
    }

    private fun handleTap(payload: InputTapPayload) {
        // Pre-flight check (§7) -- catches the split-second-revocation race between an inbound
        // frame arriving and the dispatch call below.
        if (!AccessibilityPermissionMonitor.isGranted(this)) return
        val path = Path().apply { moveTo(payload.x.toFloat(), payload.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleSwipe(payload: InputSwipePayload) {
        if (!AccessibilityPermissionMonitor.isGranted(this)) return
        val points = payload.pathPoints
        if (points.isEmpty()) return
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        val durationMs = payload.durationMs.coerceAtLeast(MIN_SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleText(payload: InputTextPayload) {
        if (!AccessibilityPermissionMonitor.isGranted(this)) return
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            // Failure feedback path (§6/§8/detailed-spec §16) -- not failed silently.
            ScreenShareForegroundService.sendFrameToConnectedChildren(
                TextInjectFailedPayload(TextInjectFailedPayload.REASON_NO_FOCUSED_FIELD).toFrame()
            )
            return
        }
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                payload.text
            )
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        // Clipboard is never read or written anywhere in this path (Decision #9) -- no
        // ClipboardManager usage exists in this class at all, by construction.
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val MIN_SWIPE_DURATION_MS = 1L
    }
}
