# PLAN: PC Key Relay Support (Android Side)

<!-- Implementation plan for an EXISTING-Android-app change. This feature exists because of the
PC Viewer Client (see docs/pc-client-spec.md §3, docs/pc-client-detailed-spec.md §11) but its
code changes land entirely in app/ (Kotlin), so per explicit user instruction it lives in this
flat docs/plans/<feature-name>/ layout alongside the five already-merged Android tiers, NOT
under docs/plans/pc-client/ (which is reserved for the new C# pc-client/ codebase). This plan is
intentionally NOT added to docs/plans/README.md's dependency-tier table (§8) or feature list
(§1) -- kept administratively separate from that tracked tier system per the same instruction,
even though it lives in the same flat directory layout, AND is a second explicit exception to
this feature's "no Android-side changes" principle alongside pc-pairing-code-support (see
docs/DECISIONS.md #19). See docs/plans/pc-client/README.md §2 for the full rationale. -->

## Index

1. Scope
2. Wire Protocol Addition: `INPUT_KEY`
3. `ParentAccessibilityService` Handler Wiring
4. Per-Key Injection Mechanism
5. Known Limitations (Already Accepted, Not New)
6. Availability Gating
7. Interactions With Other Features
8. Open Items

## 1. Scope

Adds a new `INPUT_KEY` wire message and a corresponding handler in the already-merged `ParentAccessibilityService` (`app/src/main/java/com/familyremote/app/parent/ParentAccessibilityService.kt`) that injects the nine relayed keys (Arrow Up/Down/Left/Right, Backspace, Delete, Enter, Tab, Escape) into the parent's currently-focused screen element. This is the Android-side half of `docs/pc-client-detailed-spec.md` §11; the PC-side half (focus-scoped capture, sending `INPUT_KEY`) is `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md`.

## 2. Wire Protocol Addition: `INPUT_KEY`

Per `docs/pc-client-detailed-spec.md` §11: this is where the message "is added to `docs/plans/README.md`'s Message Protocol table... when a PLAN.md is eventually written for this feature" — done as part of this plan (see the accompanying edit to `docs/plans/README.md` §4).

`app/src/main/java/com/familyremote/app/net/MessageType.kt`: add `INPUT_KEY(11)` immediately after `SESSION_END(10)` and before the pairing-time-only values, matching the existing ordering convention (live-session values first, pairing values last).

New file `app/src/main/java/com/familyremote/app/net/InputKeyMessage.kt` (grouped with the other input-message payloads' file-per-feature convention, but kept as its own small file since it's introduced by a different plan than `InputMessages.kt`'s original three):

```kotlin
package com.familyremote.app.net

import org.json.JSONObject

/** `INPUT_KEY` (child/PC->parent): a single relayed key press, per PLAN.md §2. */
enum class RelayKey {
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    BACKSPACE, DELETE, ENTER, TAB, ESCAPE
}

data class InputKeyPayload(val key: RelayKey) {
    fun toJson(): String = JSONObject().apply { put("key", key.name) }.toString()

    fun toFrame(): MessageFrame = MessageFrame.of(MessageType.INPUT_KEY, toJson())

    companion object {
        fun parse(raw: String): InputKeyPayload =
            InputKeyPayload(RelayKey.valueOf(JSONObject(raw).getString("key")))
    }
}
```

`RelayKey`'s enum constant names are the wire values (JSON string, via `.name`/`valueOf`) — the PC client's own `RelayKey` C# enum (`docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md` §4) must serialize to these exact same string values; both plans must stay in sync on this set, and neither may add a key beyond this fixed nine without updating the other and `docs/pc-client-detailed-spec.md` §11.

## 3. `ParentAccessibilityService` Handler Wiring

`app/src/main/java/com/familyremote/app/parent/ParentAccessibilityService.kt`:

- `onInputFrameReceived`'s `when (frame.type)` gains one new branch: `MessageType.INPUT_KEY -> handleKey(InputKeyPayload.parse(frame.payloadAsUtf8String()))`.
- New `private fun handleKey(payload: InputKeyPayload)` — pre-flight `AccessibilityPermissionMonitor.isGranted(this)` check first, same as every other handler in this file (§4 below covers the per-key logic).
- No other change to this file's structure — `handleTap`/`handleSwipe`/`handleText` and the class's existing lifecycle methods are untouched.

## 4. Per-Key Injection Mechanism

Per `docs/pc-client-detailed-spec.md` §11's flagged (not resolved there) injection constraint: `AccessibilityService` has no true key-event injection API, only `dispatchGesture`, `performGlobalAction`, and `AccessibilityNodeInfo` node actions. This section resolves the "exact mechanism per key" the spec explicitly leaves to this PLAN.md, using only documented, public `AccessibilityNodeInfo`/`AccessibilityService` APIs — no private/reflection-based workarounds.

```kotlin
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
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_IME_ENTER }) {
        node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
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
```

Notes on the design above:

- **`focusNextEditableNode`'s node-identity check is heuristic** (`viewIdResourceName` + `text` match, since `AccessibilityNodeInfo` has no stable cross-query identity/equality guarantee — a fresh `rootInActiveWindow` query can return new node instances even for the same underlying view). This is an accepted best-effort approximation, not a reliability-critical path: if the heuristic fails to locate the current node in the freshly-collected list, `Tab` simply no-ops for that one press (per the fallback `if (currentIndex == -1 ...) return`) rather than moving focus to the wrong place — consistent with this feature's already-accepted "standard text fields only, best-effort" posture (`docs/pc-client-detailed-spec.md` §11).
- **No clipboard usage anywhere in this handler** (`docs/DECISIONS.md` #9, same rule as `handleText`).
- All node references obtained mid-method (`rootInActiveWindow`, `findFocus`, child traversal) are used and discarded within the same call — no `AccessibilityNodeInfo` instance is cached across calls/frames, avoiding the well-known Android pitfall of operating on a stale/recycled node.

## 5. Known Limitations (Already Accepted, Not New)

Per `docs/pc-client-detailed-spec.md` §11 — this plan does not attempt to solve, and is not expected to solve, the following (already flagged and accepted at the spec level before this plan existed):

- **Standard text fields only**: all key handling except Escape/Back depends on the focused node exposing standard Accessibility text/selection properties. Custom-drawn input surfaces (games, Canvas/OpenGL text fields) will not respond — same limitation `docs/pc-client-detailed-spec.md` §11 already documents.
- **Enter's IME-action semantics are unavailable below API 30** (§4's `injectEnter` note) — a platform-version constraint, not a design gap.
- **Tab has no reliable node-identity mechanism** (§4's note) — best-effort only, silently no-ops when it can't determine the next target confidently.

None of these are escalated as open questions — `docs/pc-client-detailed-spec.md` §11 already explicitly anticipates and accepts exactly this class of limitation ("The exact mechanism per key is left to the eventual PLAN.md; this section only flags that the constraint exists so a future PLAN.md is not surprised by it").

## 6. Availability Gating

Per `docs/pc-client-detailed-spec.md` §11's last content bullet: `INPUT_KEY` requires the same Accessibility Service permission as the other input types, gated by the same `AccessibilityPermissionMonitor.isGranted()` pre-flight check §3/§4 already show — no independent permission/availability flag. The existing `ACCESSIBILITY_STATUS` push mechanism (`docs/plans/remote-input-control/PLAN.md` §7) is entirely unchanged by this plan; the child/PC side already disables `INPUT_KEY` sending together with the rest of input when `inputAvailable == false` (Android child: no UI change needed since it never had key relay; PC client: `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md` §6).

## 7. Interactions With Other Features

- **`docs/plans/remote-input-control/PLAN.md`**: this plan's `handleKey` sits alongside that plan's `handleTap`/`handleSwipe`/`handleText` in the same already-merged `ParentAccessibilityService` file — same class, same permission-check pattern, same in-process routing from `ScreenShareForegroundService` (that plan's §6's `onInputFrameReceived` dispatch, unchanged).
- **`docs/plans/README.md` §4**: this plan adds the `INPUT_KEY` row to the shared Message Protocol table (edited alongside this plan, not deferred) — see that table for the final row wording.
- **`docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md`**: that plan's `KeyRelayController` is the exact sending counterpart to this plan's handler. Both plans must stay in sync on the nine-key `RelayKey` set and the `MessageType.INPUT_KEY = 11` tag — do not let either drift without updating the other.
- **`docs/plans/screen-rotation-handling/PLAN.md`**: not affected — key relay targets whatever node currently has input focus regardless of the parent device's current orientation; no interaction with that plan's `FrameGeometry`/dimension-change logic.

## 8. Open Items

None. `docs/pc-client-detailed-spec.md` §11 explicitly delegates "exact mechanism per key" to this PLAN.md as an implementation-level decision (not a product-level one) and already accepts the resulting "standard text fields only" limitation — §4/§5 above resolve that delegation directly, consistent with how `docs/plans/remote-input-control/PLAN.md` §6 resolved the equivalent `INPUT_TEXT`-via-`ACTION_SET_TEXT` mechanism for the already-merged tier this plan extends.
