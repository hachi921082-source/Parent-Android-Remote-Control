# PLAN: PC Client — Input Relay (Tap/Swipe/Text)

<!-- Implementation plan. Covers pc-client-detailed-spec.md §7 (Input Relay: Tap/Swipe/Text).
Part of the PC Viewer Client feature. Physical key relay (§11) is a separate sibling plan:
docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md. -->

## Index

1. Scope
2. Components
3. Coordinate Mapping (Mouse → Parent-Frame Coordinates)
4. Tap Capture (Mouse Click)
5. Swipe Capture (Mouse Drag)
6. Tap/Swipe Marker Visualization
7. Text Input Box
8. View-Only Mode (`ACCESSIBILITY_STATUS`)
9. Interactions With Other Features
10. Open Items

## 1. Scope

Turning PC mouse gestures and typed text into the same `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` wire messages the Android child already sends, plus the same local-only marker feedback and view-only-mode gating. Depends on an already-`Live` session (`docs/plans/pc-client/video-and-session/connectivity.PLAN.md`) and the current `RenderedContentRect`/`FrameGeometry` (`docs/plans/pc-client/video-and-session/decode.PLAN.md` §6, as further transformed by `docs/plans/pc-client/zoom-magnifier/PLAN.md` when zoomed).

## 2. Components

| Component | Responsibility |
|---|---|
| `Input/PointerInputController` | Mouse event handling on `VideoView`'s surface; converts raw mouse coordinates to parent-frame coordinates via the current content-rect mapping (zoomed or not), discards out-of-content clicks, sends `INPUT_TAP`/`INPUT_SWIPE`, drives the marker overlay. |
| `Input/TapMarkerOverlay` | Lightweight `UserControl`/`Canvas` drawn above `VideoView`; shows/fades a marker, ported from the Android `TapMarkerOverlay`. |
| `Input/TextInputBar` | A `TextBox` + "Send" `Button`, disabled when `InputAvailable == false` (§8). |
| `Input/InputAvailabilityState` | Small observable holding `InputAvailable: bool`, driven by inbound `AccessibilityStatus` frames. |

## 3. Coordinate Mapping (Mouse → Parent-Frame Coordinates)

Per `docs/detailed-spec.md` §15, ported directly (`docs/pc-client-detailed-spec.md` §7):

```csharp
public static (int x, int y)? MapPointToParentCoords(Point p, Rect contentRect, FrameGeometry frame)
{
    if (!contentRect.Contains(p)) return null;   // discard -- black-bar area, never clamp
    var fracX = (p.X - contentRect.X) / contentRect.Width;
    var fracY = (p.Y - contentRect.Y) / contentRect.Height;
    return ((int)(fracX * frame.ParentFrameWidth), (int)(fracY * frame.ParentFrameHeight));
}
```

- `contentRect` here is whichever content rect is current — the plain `RenderedContentRect` from `decode.PLAN.md` §6 when not zoomed, or the zoomed sub-region rect from `docs/plans/pc-client/zoom-magnifier/PLAN.md` §5 when zoomed; `PointerInputController` reads a single "current content rect" observable that `zoom-magnifier` overrides, so this plan has no zoom-awareness of its own beyond consuming whatever rect is current (same layering `docs/pc-client-detailed-spec.md` §10 describes: zoom is "layered on top of the existing letterbox/coordinate-scaling logic," not a fork of it).
- Returns `null` for out-of-content clicks → dropped entirely (no message sent, no marker shown) — `docs/DECISIONS.md` #11's discard-over-clamp rule, ported unchanged.
- `INPUT_TAP`/`INPUT_SWIPE` payloads carry raw parent-pixel integer coordinates (not normalized 0..1) — matches the Android wire format exactly (`app/src/main/java/com/familyremote/app/net/InputMessages.kt`'s documented choice), required for wire compatibility since the parent-side handler makes no distinction between client types.

## 4. Tap Capture (Mouse Click)

- `PointerInputController` handles `VideoView.PreviewMouseLeftButtonDown`/`PreviewMouseLeftButtonUp` (tunneling events, chosen for the same reason `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md` uses `PreviewKeyDown` — ensures this handler sees the event before any child control's own default handling, though `VideoView` itself has no competing default mouse behavior; used here mainly for consistency).
- `MouseDown` → `MouseUp` with no meaningful pointer movement in between (a small pixel-distance threshold, e.g. `SystemParameters.MinimumHorizontalDragDistance`, WPF's own platform drag-threshold constant — mirrors the Android `ViewConfiguration.scaledTouchSlop` choice, `docs/plans/remote-input-control/PLAN.md` §11) → tap: map the `MouseUp` position (§3), and if non-null, send `INPUT_TAP {x, y}`.

## 5. Swipe Capture (Mouse Drag)

- `MouseDown` → one or more `MouseMove` events past the drag threshold → `MouseUp`: sampled path, same interval/cap as the Android implementation (every 16ms, capped at 64 points, `docs/plans/remote-input-control/PLAN.md` §11) via a `DispatcherTimer` or elapsed-time check inside the `MouseMove` handler.
- Mapping every sampled point through §3; if **any** sampled point maps to `null` (outside the content rect), the **entire** gesture is discarded, not just the out-of-bounds points — ported directly from the Android `TapInputController`'s "as-built" rule (`docs/plans/remote-input-control/PLAN.md` §11), for the same reason (a partially-registered swipe is as confusing as a clamped one).
- On `MouseUp` with a valid (non-discarded) path: send `INPUT_SWIPE {pathPoints, durationMs}` (duration = elapsed wall-clock time from first sample to last).
- Because `docs/pc-client-detailed-spec.md` §10 confirms zoom has no pan/scroll mechanism, a mouse-drag while zoomed is unambiguously a swipe-input gesture in every case — no gesture-type disambiguation is needed between "pan the zoomed view" and "send a swipe" (the zoom feature itself has no drag-to-pan interaction to conflict with this one, per that plan's own design).

## 6. Tap/Swipe Marker Visualization

Per `docs/detailed-spec.md` §10 / `docs/pc-client-detailed-spec.md` §7's "tap-marker rendering coordinate space" note:

- Every valid (non-discarded) tap/swipe immediately draws on `TapMarkerOverlay` using the **PC operator's own raw mouse coordinates on `VideoView`'s surface** — i.e. already in whatever zoomed/letterboxed on-screen space is currently rendered, not translated back to unzoomed frame-relative coordinates. This is a direct consequence of the existing "local overlay using the coordinates the operator just used" principle (no change needed vs. the Android design) — `docs/pc-client-detailed-spec.md` §7 confirms this explicitly, calling out that it "makes the zoom interaction explicit" rather than introducing new behavior.
- `TapMarkerOverlay.ShowMarker(point)`: draws a circle/short stroke via a `DrawingVisual`/`Canvas` shape, starts a `300ms` fade-out animation (`Common.Constants.TapMarkerFadeMs`, via a WPF `DoubleAnimation` on the shape's `Opacity`). A new marker before the previous fade completes cancels and restarts the animation at full opacity — never two markers simultaneously (same rule as the Android `TapMarkerOverlay`).
- Discarded (black-bar) inputs never call `ShowMarker` — handled by construction since §3's `MapPointToParentCoords` returning `null` short-circuits before reaching send/marker logic.

## 7. Text Input Box

Per `docs/detailed-spec.md` §16 / `docs/pc-client-detailed-spec.md` §7, ported directly (`docs/DECISIONS.md` #9 — `ACTION_SET_TEXT` on the parent side, never clipboard, never per-keystroke):

- `TextInputBar`: `TextBox` + "Send" `Button`. Operator composes full text locally; nothing transmitted until "Send" — no per-keystroke streaming (this is separate from, and does not conflict with, `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md`'s `INPUT_KEY`, since that plan's focus-scoping rule guarantees a keypress routes to either this `TextBox` or the video pane's key handler, never both — see that plan's §3).
- On "Send": `INPUT_TEXT {text}` sent once; field clears.
- Disabled entirely (both `TextBox` and `Button`, via a shared `IsEnabled` binding to `InputAvailabilityState.InputAvailable`) whenever input is unavailable (§8) — no "send attempted while unavailable" error path exists, mirroring the Android design.
- `TextInjectFailed {reason: "NO_FOCUSED_FIELD"}` receipt → a lightweight, non-blocking transient notice (e.g. a `Snackbar`-equivalent toast control, or a temporary `InfoBar`/status-strip message that auto-dismisses after a few seconds — WPF has no built-in Snackbar, so this plan uses a small custom `Border`+`TextBlock` overlay with a `DispatcherTimer`-driven auto-hide, the simplest control that achieves the same "transient, non-blocking, per-attempt" behavior as the Android `Snackbar`) — does not disable `TextInputBar` (per-attempt, not a standing state).

## 8. View-Only Mode (`ACCESSIBILITY_STATUS`)

Per `docs/detailed-spec.md` §11 / `docs/pc-client-detailed-spec.md` §7 ("no new logic, same wire message drives the same behavior on this client too"):

- `InputAvailabilityState.InputAvailable` starts `true`, flips on `AccessibilityStatus {granted}` receipt (dispatched via `docs/plans/pc-client/video-and-session/connectivity.PLAN.md`'s `Net.SessionChannel`).
- When `false`: `PointerInputController` stops sending `INPUT_TAP`/`INPUT_SWIPE` (video continues rendering normally — independence from decode preserved, same as the Android design) and `TextInputBar` is disabled (§7) — both driven by the same single flag, satisfying the "all input UI disabled together" requirement.
- A visible banner over the video (e.g. "Input unavailable — ask them to check accessibility settings", mirroring the Android `ViewOnlyBanner` wording pattern) is shown while `InputAvailable == false`, clears automatically on the next `AccessibilityStatus {granted: true}`.
- Reset on every fresh entry into `Live` (a prior session's `InputAvailable` value belongs to a superseded connection) — mirrors the Android `InputAvailabilityViewModel`'s reset rule (`docs/plans/remote-input-control/PLAN.md` §11).
- Also gated on the outer `ConnectionState` being `Live` (not just `InputAvailable`) — when the connection drops to `Reconnecting`/`Disconnected`, input is moot regardless of the last-known `InputAvailable` value, per the same cross-feature note the Android plan makes (`docs/plans/remote-input-control/PLAN.md` §10).

## 9. Interactions With Other Features

- **video-and-session/decode**: reads `RenderedContentRect`/`FrameGeometry` for coordinate mapping (§3); does not modify them.
- **video-and-session/connectivity**: sends `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` frames out over, and receives `AccessibilityStatus`/`TextInjectFailed` frames in from, that plan's `Net.SessionChannel`.
- **zoom-magnifier**: supplies the "current content rect" this plan's §3 mapping consumes when zoomed; this plan has no dependency in the other direction (zoom does not read anything from this plan).
- **physical-key-relay-pc-side**: a sibling input mechanism on the same video pane; focus-scoping (that plan's §3) ensures no double-handling between this plan's `TextInputBar` and that plan's key capture.
- **connection-state-ui**: this plan's controls are only shown/enabled during `ConnectionState.Live`/`Reconnecting`, per §8's last bullet.

## 10. Open Items

None. `docs/pc-client-detailed-spec.md` §7 fixes every product-level requirement by direct reference to the already-decided Android behavior (`docs/detailed-spec.md` §10/§11/§15/§16); the only choices this plan makes are WPF-specific UI-control substitutions (Snackbar-equivalent, drag-threshold constant) with no product-facing consequence.
