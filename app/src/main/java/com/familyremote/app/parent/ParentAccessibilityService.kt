package com.familyremote.app.parent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familyremote.app.common.AccessibilityPermissionMonitor
import com.familyremote.app.net.InputKeyPayload
import com.familyremote.app.net.InputSwipePayload
import com.familyremote.app.net.InputTapPayload
import com.familyremote.app.net.InputTextPayload
import com.familyremote.app.net.MessageFrame
import com.familyremote.app.net.MessageType
import com.familyremote.app.net.RelayKey
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

    /** Issue #35: echoes the child's tap/swipe feedback onto the parent's own real screen, so an
     * elderly parent can see where they're being guided to tap. A `TYPE_ACCESSIBILITY_OVERLAY`
     * window -- the one overlay type an `AccessibilityService` may add without the
     * `SYSTEM_ALERT_WINDOW` permission -- added for this service's whole lifetime rather than
     * per-tap, so a burst of taps never races window add/remove. */
    private var tapMarkerOverlay: ParentTapMarkerOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenShareForegroundService.attachAccessibilityService(this)
        addTapMarkerOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used -- gesture/text injection is driven entirely by inbound INPUT_* network frames
        // (see [onInputFrameReceived]), not by observed accessibility events.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        ScreenShareForegroundService.detachAccessibilityService(this)
        removeTapMarkerOverlay()
        serviceJob.cancel()
    }

    private fun addTapMarkerOverlay() {
        val overlay = ParentTapMarkerOverlay(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching { getSystemService(WindowManager::class.java).addView(overlay, params) }
            .onSuccess { tapMarkerOverlay = overlay }
        // A failure here (e.g. no display available yet) just means no marker echo this session --
        // dispatchGesture itself doesn't depend on this overlay, so input control still works.
    }

    private fun removeTapMarkerOverlay() {
        tapMarkerOverlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        tapMarkerOverlay = null
    }

    /** Called by [ScreenShareForegroundService] for every `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT`
     * frame received from a connected child, per PLAN.md §6. */
    fun onInputFrameReceived(frame: MessageFrame) {
        serviceScope.launch {
            when (frame.type) {
                MessageType.INPUT_TAP -> handleTap(InputTapPayload.parse(frame.payloadAsUtf8String()))
                MessageType.INPUT_SWIPE -> handleSwipe(InputSwipePayload.parse(frame.payloadAsUtf8String()))
                MessageType.INPUT_TEXT -> handleText(InputTextPayload.parse(frame.payloadAsUtf8String()))
                MessageType.INPUT_KEY -> handleKey(InputKeyPayload.parse(frame.payloadAsUtf8String()))
                else -> Unit // Not this service's concern.
            }
        }
    }

    private fun handleTap(payload: InputTapPayload) {
        // Pre-flight check (§7) -- catches the split-second-revocation race between an inbound
        // frame arriving and the dispatch call below.
        if (!AccessibilityPermissionMonitor.isGranted(this)) return
        tapMarkerOverlay?.showMarker(listOf(PointF(payload.x.toFloat(), payload.y.toFloat())))
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
        tapMarkerOverlay?.showMarker(points.map { PointF(it.x.toFloat(), it.y.toFloat()) })
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

    /**
     * Issue #38: whether the parent currently has a focused editable field -- the same check
     * [handleText] already makes before injecting text, exposed for
     * [ScreenShareForegroundService]'s periodic `ACCESSIBILITY_STATUS` ticker so the child can show
     * its message-input bar only when the parent could actually receive text right now.
     */
    fun hasFocusedInputField(): Boolean =
        AccessibilityPermissionMonitor.isGranted(this) &&
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null

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

    /**
     * `INPUT_KEY` handler (docs/plans/pc-key-relay-support/PLAN.md §3/§4): injects the nine
     * relayed keys into the parent's currently-focused screen element, using only documented,
     * public `AccessibilityNodeInfo`/`AccessibilityService` APIs -- `AccessibilityService` has no
     * true key-event injection API.
     */
    private fun handleKey(payload: InputKeyPayload) {
        if (!AccessibilityPermissionMonitor.isGranted(this)) return

        if (payload.key == RelayKey.ESCAPE) {
            // Not node/focus-scoped -- always available, per detailed-spec §11.
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        when (payload.key) {
            RelayKey.ARROW_LEFT -> moveCursor(focused, forward = false, granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER)
            RelayKey.ARROW_RIGHT -> moveCursor(focused, forward = true, granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER)
            RelayKey.ARROW_UP -> moveCursor(focused, forward = false, granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
            RelayKey.ARROW_DOWN -> moveCursor(focused, forward = true, granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
            RelayKey.BACKSPACE -> deleteAdjacentCharacter(focused, beforeCursor = true)
            RelayKey.DELETE -> deleteAdjacentCharacter(focused, beforeCursor = false)
            RelayKey.ENTER -> injectEnter(focused)
            RelayKey.TAB -> focusNextEditableNode(focused)
            RelayKey.ESCAPE -> Unit // handled above
        }
    }

    /** Arrow keys: uses the standard "granular text navigation" action set (the same mechanism
     * TalkBack uses to move a live cursor character/line at a time within a focused editable node),
     * not text reconstruction -- moves the node's actual text selection when the node supports the
     * requested granularity. */
    private fun moveCursor(node: AccessibilityNodeInfo, forward: Boolean, granularity: Int) {
        if (node.movementGranularities and granularity == 0) return // node doesn't support it -- silent no-op
        val action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                     else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
        }
        node.performAction(action, args)
    }

    /** Backspace/Delete: no direct "delete one character" action exists, so this reconstructs the
     * text -- read the current string + cursor position, remove one character before/after it, then
     * ACTION_SET_TEXT the new string and ACTION_SET_SELECTION to restore the cursor at the edit
     * point. Only fires when the cursor is a single collapsed position or a selection is present (a
     * present selection is simply removed instead, matching ordinary text-editor Backspace/Delete
     * behavior with an active selection). */
    private fun deleteAdjacentCharacter(node: AccessibilityNodeInfo, beforeCursor: Boolean) {
        val text = node.text?.toString() ?: return
        val start = node.textSelectionStart.takeIf { it >= 0 } ?: return
        val end = node.textSelectionEnd.takeIf { it >= 0 } ?: return
        val (newText, newCursor) = if (start != end) {
            // Active selection -- Backspace/Delete both just remove it.
            (text.removeRange(minOf(start, end), maxOf(start, end))) to minOf(start, end)
        } else if (beforeCursor) {
            if (start == 0) return
            (text.removeRange(start - 1, start)) to (start - 1)
        } else {
            if (start >= text.length) return
            (text.removeRange(start, start + 1)) to start
        }
        setTextAndSelection(node, newText, newCursor)
    }

    /** Enter: prefers the platform's own IME-enter action (API 30+, ACTION_IME_ENTER -- semantically
     * correct for single-line fields with a "search"/"send"/"go" IME action, since it triggers
     * whatever that field's actual editor action is, not just a literal newline). Falls back to
     * inserting a literal newline via the same text-reconstruction approach as Backspace/Delete on
     * older API levels, which is correct for genuinely multi-line fields but will not trigger a
     * single-line field's submit semantics there -- an accepted, platform-version-driven limitation,
     * not a design gap (this app's min SDK is 26, ACTION_IME_ENTER requires 30). */
    private fun injectEnter(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return
        }
        val text = node.text?.toString() ?: return
        val start = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        val newText = text.substring(0, start) + "\n" + text.substring(start)
        setTextAndSelection(node, newText, start + 1)
    }

    /** Tab: best-effort move of actual input focus (not just accessibility/TalkBack focus) to the
     * next focusable node in the active window's traversal order. Android's AccessibilityService API
     * has no built-in "next focusable field" action, so this walks rootInActiveWindow's node tree in
     * depth-first order collecting isFocusable() nodes, finds the currently-focused node's position
     * in that list, and calls ACTION_FOCUS on the next one. Silently no-ops if no next focusable node
     * is found (last field on screen) or if the active window exposes no addressable tree -- an
     * accepted best-effort limitation given the platform provides no direct API for this. */
    private fun focusNextEditableNode(current: AccessibilityNodeInfo) {
        val root = rootInActiveWindow ?: return
        val focusable = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isFocusable) focusable.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        val currentIndex = focusable.indexOfFirst { it.viewIdResourceName == current.viewIdResourceName && it.text == current.text }
        if (currentIndex == -1 || currentIndex + 1 >= focusable.size) return
        focusable[currentIndex + 1].performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun setTextAndSelection(node: AccessibilityNodeInfo, newText: String, cursor: Int) {
        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val MIN_SWIPE_DURATION_MS = 1L
    }
}
