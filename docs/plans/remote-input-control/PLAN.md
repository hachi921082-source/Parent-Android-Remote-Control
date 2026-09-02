# PLAN: Remote Input Control

<!-- Implementation plan. Covers detailed-spec.md §10 (Tap-Position Visualization), §11 (Accessibility Service Permission Monitoring), §15 (Aspect Ratio Mismatch & Tap Coordinate Scaling), §16 (Text Input Mechanism). -->

## Index

1. Scope
2. Components
3. Video Surface, Letterbox/Pillarbox Rendering
4. Coordinate Scaling
5. Tap/Swipe Capture & Marker Visualization
6. Accessibility Service (Parent Side) — Injection
7. Accessibility Permission Monitoring & View-Only Mode
8. Text Input
9. FLAG_SECURE Help Hint (Child-Side UI)
10. Interactions With Other Features

## 1. Scope

Everything about turning child gestures/text into parent-side actions, giving the child visual feedback, and gracefully degrading to view-only when the Accessibility Service permission is unavailable. Depends on an already-`Live` session connection (owned by connection-status-and-resilience) and an already-decoding video stream (owned by screen-sharing-core for encode; this plan owns decode+render).

## 2. Components

| Component | Device | Responsibility |
|---|---|---|
| `VideoDecoderView` | child | `MediaCodec` H.264 decoder feeding a `SurfaceView`/`TextureView`; owns `FrameGeometry` updates from incoming `VIDEO_FRAME` headers. |
| `LetterboxLayoutCalculator` | child | Computes `RenderedContentRect` from view size + current `FrameGeometry` (§3). |
| `TapInputController` | child | `View.OnTouchListener`/gesture detector on the video surface; converts raw touch events to parent-frame-normalized coordinates via `LetterboxLayoutCalculator`, discards out-of-content touches, sends `INPUT_TAP`/`INPUT_SWIPE`, and drives the local marker overlay. |
| `TapMarkerOverlay` | child | Lightweight custom `View` drawn above `VideoDecoderView`; shows/fades a marker per §5. |
| `TextInputBar` | child | Compose/text-entry UI, disabled when `inputAvailable == false` (§7). |
| `ParentAccessibilityService` | parent | `AccessibilityService` subclass; receives `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` from the session channel (via a bound connection to `ScreenShareForegroundService`, same process) and performs `dispatchGesture`/`ACTION_SET_TEXT`. |
| `AccessibilityPermissionMonitor` | parent | Pre-flight + periodic permission checks (§7); shared with `docs/plans/pairing-and-onboarding/PLAN.md`'s onboarding screen (same check logic, different callers). |

## 3. Video Surface, Letterbox/Pillarbox Rendering

Per detailed-spec §15 / Decision #10: preserve aspect ratio, add black bars, never stretch/crop.

- `VideoDecoderView` sizes its internal `Surface` to the view's actual pixel bounds (fills the available layout space), but `LetterboxLayoutCalculator` computes the sub-rectangle within that view where decoded frames are actually drawn without distortion:
  ```
  fun computeContentRect(viewW: Int, viewH: Int, frame: FrameGeometry): RectF {
      val viewAspect = viewW.toFloat() / viewH
      val frameAspect = frame.parentFrameWidth.toFloat() / frame.parentFrameHeight
      return if (frameAspect > viewAspect) {
          // frame relatively wider than view: fit to view width, black bars top/bottom (letterbox)
          val h = viewW / frameAspect
          RectF(0f, (viewH - h) / 2, viewW.toFloat(), (viewH - h) / 2 + h)
      } else {
          // frame relatively taller/narrower than view: fit to view height, black bars left/right (pillarbox)
          val w = viewH * frameAspect
          RectF((viewW - w) / 2, 0f, (viewW - w) / 2 + w, viewH.toFloat())
      }
  }
  ```
- Actual video scaling to that rect is done via the decoder output `Surface`'s transform (`SurfaceView` cropping/scaling via `TextureView.setTransform(Matrix)` if `TextureView` is used, chosen over plain `SurfaceView` specifically because it supports arbitrary matrix transforms needed for the letterbox fit without an extra composition step).
- Recalculated whenever: the view's `onSizeChanged` fires (child device rotation/window resize), or a new `VIDEO_FRAME` header reports different `frameWidth`/`frameHeight` than the current `FrameGeometry` (parent-side rotation, `docs/plans/screen-rotation-handling/PLAN.md`).

## 4. Coordinate Scaling

Per detailed-spec §15:

```
fun mapTouchToParentCoords(touchX: Float, touchY: Float, contentRect: RectF, frame: FrameGeometry): Pair<Float, Float>? {
    if (!contentRect.contains(touchX, touchY)) return null   // discard — black-bar area, never clamp
    val fracX = (touchX - contentRect.left) / contentRect.width()
    val fracY = (touchY - contentRect.top) / contentRect.height()
    return Pair(fracX * frame.parentFrameWidth, fracY * frame.parentFrameHeight)
}
```

- Returns `null` for out-of-content-area touches → `TapInputController` drops the event entirely (no message sent, no marker shown — detailed-spec §10's exclusion note and §15's discard-over-clamp, Decision #11).
- `INPUT_TAP`/`INPUT_SWIPE` payloads carry the already-scaled `{xNormalized, yNormalized}` (normalized 0..1 against `frame.parentFrameWidth/Height`, or sent as raw parent-pixel coordinates — implementation choice: send raw parent-pixel integer coordinates directly, since the parent already knows its own current frame dimensions and normalization adds a round-trip precision concern with no benefit here).
- Recalculation triggers identical to §3's list — both consumers (render, input mapping) subscribe to the same `FrameGeometry`/view-size observable so they never disagree about the current `RenderedContentRect`.

## 5. Tap/Swipe Capture & Marker Visualization

Per detailed-spec §10:

- `TapInputController` uses `GestureDetector`/raw `MotionEvent` handling on `VideoDecoderView`'s overlay touch target: `ACTION_DOWN`+`ACTION_UP` close together (no significant movement) → tap (`INPUT_TAP`); `ACTION_DOWN` → `ACTION_MOVE`* → `ACTION_UP` with meaningful displacement → swipe (`INPUT_SWIPE`, path sampled at a fixed interval, e.g. every 16ms, capped to a reasonable max point count).
- Every valid (non-discarded) tap/swipe immediately draws on `TapMarkerOverlay` **using the child's own local touch coordinates** (not anything echoed back from the parent) — per detailed-spec §10, this is deliberately local-only feedback, independent of network round-trip.
- `TapMarkerOverlay.showMarker(point)`: draws a circle (or short path stroke for swipes) at the given point, starts a `300ms` fade-out `ValueAnimator` (`TAP_MARKER_FADE_MS`, `docs/plans/README.md` §7). If a new marker arrives before the fade completes, the animator is cancelled and restarted at full opacity for the new point — never two markers simultaneously.
- Discarded (black-bar) touches never call `showMarker` — handled by construction since `mapTouchToParentCoords` returning `null` short-circuits before reaching the marker/send logic.

## 6. Accessibility Service (Parent Side) — Injection

- `ParentAccessibilityService` declares `canPerformGestures="true"`, `canRetrieveWindowContent="true"` in its `accessibility_service_config.xml` (needed for `dispatchGesture` and `ACTION_SET_TEXT`/`findFocus` respectively).
- Runs as a normal system-managed `AccessibilityService` component (same process as the rest of the app, no `android:process` isolation — simplifies IPC with `ScreenShareForegroundService`, which is where inbound `INPUT_*` messages actually arrive from the network layer; the service exposes a simple in-process callback interface, e.g. a `SharedFlow<InputCommand>` collected by `ParentAccessibilityService`, or the reverse — `ScreenShareForegroundService` holds a nullable reference to the currently-connected `ParentAccessibilityService` instance via a companion object, set in `onServiceConnected()`/cleared in `onDestroy()`).
- `INPUT_TAP {x,y}` → `dispatchGesture(GestureDescription.Builder().addStroke(StrokeDescription(Path().apply { moveTo(x,y) }, 0, 50)).build(), null, null)` (short duration tap gesture).
- `INPUT_SWIPE {pathPoints, durationMs}` → same `dispatchGesture` API with a multi-point `Path` and the given duration.
- `INPUT_TEXT {text}` → find the focused editable node (`rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`); if non-null, build a `Bundle` with `AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` and call `performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)` (detailed-spec §16). If the focused-node lookup returns null, send `TEXT_INJECT_FAILED {reason: "NO_FOCUSED_FIELD"}` back over the session channel (detailed-spec §16's explicit failure-feedback path) rather than failing silently.
- Clipboard is never read or written anywhere in this path (detailed-spec §16 / Decision #9) — no `ClipboardManager` usage exists in this component at all, by construction.

## 7. Accessibility Permission Monitoring & View-Only Mode

Per detailed-spec §11:

- `AccessibilityPermissionMonitor.isGranted()`: checks `Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)` for this app's service component name (durable check, works even if `ParentAccessibilityService`'s own `onServiceConnected`/`onDestroy` callbacks were never reliably observed due to a kill — this is the check used by both the pre-flight and periodic paths below, and reused as-is by `docs/plans/pairing-and-onboarding/PLAN.md`'s onboarding screen).
- **Pre-flight**: `ParentAccessibilityService`'s injection methods (§6) call `AccessibilityPermissionMonitor.isGranted()` immediately before each `dispatchGesture`/`performAction` call. (In practice, if the service component itself is invoked at all, the permission is inherently granted at that instant — this check exists primarily to catch the split-second-revocation race and, more importantly, feeds the *periodic* check's shared code path; the meaningful pre-flight risk is really "was it revoked since the last periodic check," covered by keeping both checks on the same helper.)
- **Periodic**: `ScreenShareForegroundService` runs a `30000ms` ticker (`ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS`, `docs/plans/README.md` §7) while a session is `Live`, calling `AccessibilityPermissionMonitor.isGranted()` and sending `ACCESSIBILITY_STATUS {granted}` over the session channel whenever the value **changes** (not every tick — avoids redundant traffic; child only needs to know on transitions).
- Child side: `ConnectionStateViewModel` (or a small sibling `InputAvailabilityViewModel`) tracks `inputAvailable: Boolean`, starts `true`, flips on `ACCESSIBILITY_STATUS` receipt. When `false`: `TapInputController` stops sending `INPUT_TAP`/`INPUT_SWIPE` (video continues rendering normally — detailed-spec §11's independence note) and `TextInputBar` (§8) is disabled — both driven by the same single flag, satisfying detailed-spec §11's "scope of view-only" requirement that ALL input UI is disabled together, not just tap/swipe.
- A visible banner (`ViewOnlyBanner`, e.g. "Input unavailable — ask them to check accessibility settings") is shown over the video while `inputAvailable == false`, clears automatically on the next `ACCESSIBILITY_STATUS {granted: true}`.
- Secondary/best-effort parent-side surfacing (detailed-spec §11's "system notification" bullet): rely on the OS's own accessibility-revoked system notification where the platform provides one; no additional custom parent-side notification is built for this, since it's explicitly documented as best-effort/secondary and the primary path (relay to child) is already covered above.

## 8. Text Input

Per detailed-spec §16:

- `TextInputBar`: a text field + send button. Child composes full text locally; nothing is transmitted until "send" is tapped (no per-keystroke streaming, Decision #9).
- On send: `INPUT_TEXT {text}` sent once over the session channel; field clears locally.
- Disabled entirely (field + send button both non-interactive) whenever `inputAvailable == false` (§7) — since the UI itself prevents the attempt, there is no "send attempted while permission missing" error path to build (detailed-spec §16 explicitly notes this).
- `TEXT_INJECT_FAILED {reason: "NO_FOCUSED_FIELD"}` receipt → a lightweight, non-blocking transient notice (e.g. a Snackbar/toast-equivalent: "No active text field on their screen right now") — does not disable `TextInputBar` (this failure mode is per-attempt, not a standing state, unlike view-only mode).

## 9. FLAG_SECURE Help Hint (Child-Side UI)

Per detailed-spec §5: a small persistent static help hint (info icon/tooltip), always available (not dynamically triggered), placed near the video surface — e.g. a small `?`/info icon in a corner of `VideoDecoderView`'s container, tapping it shows a short explanatory dialog/tooltip: "A black screen may mean a protected app (like banking or a password manager) is open on their device — this isn't necessarily a connection problem." Exact icon choice/placement is a free implementation choice (detailed-spec explicitly leaves this open); no other logic needed since the app cannot distinguish this case from any other black frame (detailed-spec §5).

## 10. Interactions With Other Features

- **connection-status-and-resilience**: `inputAvailable` (this plan) is layered strictly within `ConnectionState.Live`/`Reconnecting` (that plan, `docs/plans/README.md` §5) — when the connection itself drops to `Disconnected`, input is moot regardless of `inputAvailable`'s last value; `TapInputController`/`TextInputBar` should also check the outer `ConnectionState` is `Live` before allowing input, not just `inputAvailable`.
- **screen-rotation-handling**: `FrameGeometry` changes originate there; this plan only consumes them (§3/§4's recalculation triggers) — see that plan for how/when a new `VIDEO_FRAME` header with different dimensions is produced.
- **screen-sharing-core**: `ParentAccessibilityService` binds to `ScreenShareForegroundService` (that plan) to receive inbound `INPUT_*` messages already deframed by that service's `ParentSessionServer`; this plan does not open its own separate network connection.
