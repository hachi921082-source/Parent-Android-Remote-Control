# PLAN: PC Client — Zoom/Magnifier Display

<!-- Implementation plan. Covers pc-client-detailed-spec.md §10 (Zoom/Magnifier Display). Part
of the PC Viewer Client feature. Client-side-only, no wire-protocol change, no Android-side
change — see docs/DECISIONS.md #18. -->

## Index

1. Scope
2. Components
3. Zoom Trigger & Interaction
4. Maximum-Zoom-Fits-Window Cap, Dynamic Reclamp on Resize
5. Coordinate Scaling While Zoomed
6. Interactions With Other Features
7. Open Items

## 1. Scope

A purely PC-client-side display/input-mapping transform: magnifying a sub-region of the already-decoded, already-full-resolution video frame so the operator can tap small UI elements precisely. No wire message, no parent-side awareness (`docs/DECISIONS.md` #18) — this plan only changes what content rect `docs/plans/pc-client/input-relay/PLAN.md`'s coordinate mapping uses, and what `docs/plans/pc-client/video-and-session/decode.PLAN.md`'s `VideoView` visually renders.

## 2. Components

| Component | Responsibility |
|---|---|
| `Zoom/ZoomState` | Holds the current zoom level (1.0 = no zoom) and the zoomed sub-region's center point (in unzoomed content-rect-relative coordinates), exposed as an observable. |
| `Zoom/ZoomController` | Translates operator zoom gestures/actions into `ZoomState` updates, applying the fit-to-window cap (§4). |
| `Zoom/ZoomedContentRectCalculator` | Given `ZoomState` + the current `RenderedContentRect`/`FrameGeometry`, computes the zoomed sub-region's own rect for both rendering and coordinate mapping (§5). |

## 3. Zoom Trigger & Interaction

Per `docs/pc-client-detailed-spec.md` §10 ("exact UX... left to the eventual PLAN.md; this section fixes only the required behavior"): this plan uses **mouse-wheel scroll while hovering over `VideoView`** as the zoom-in/zoom-out gesture (`PreviewMouseWheel` on `VideoView`, each notch stepping the zoom level by a fixed increment, e.g. 10%), with the zoom center following the current mouse cursor position at the moment of scrolling (the point under the cursor stays visually fixed as the zoom level changes — the conventional "zoom toward cursor" behavior familiar from image viewers/browsers, chosen because it requires no separate mode-toggle UI element and matches the ordinary Windows-app mouse-wheel-zoom convention). Zoom level 1.0 (no zoom) is the floor — scrolling down further below 1.0 is a no-op, not an error.

## 4. Maximum-Zoom-Fits-Window Cap, Dynamic Reclamp on Resize

Per `docs/pc-client-detailed-spec.md` §10 (required — "always computed so the zoomed content fits entirely within the current window's rendering area... continuously re-evaluated as the window is resized, not just at the moment zoom is applied"):

- `ZoomController` computes the maximum permissible zoom level as a pure function of the current `VideoView` control size and the parent frame's native resolution (`FrameGeometry`): the zoomed content's on-screen pixel size can never exceed the `VideoView` control's current bounds. This is recomputed, and the current `ZoomState.Level` is clamped down against it, on every `VideoView.SizeChanged` event (window resize) — not only at the moment the operator scrolls — satisfying the "continuously re-evaluated... automatically reduces" requirement exactly.
- The window itself is never prevented from being resized smaller (consistent with `docs/plans/pc-client/app-shell/PLAN.md` §3's "ordinary, freely resizable window" principle) — only `ZoomState.Level` reacts to the resize, never the other way around.
- Because the entire zoomed content is always fully visible by construction, no pan/scroll mechanism exists anywhere in this plan — `docs/pc-client-detailed-spec.md` §10 confirms this is intentional, and `docs/plans/pc-client/input-relay/PLAN.md` §5 relies on this fact to treat every mouse-drag unambiguously as a swipe gesture, never a pan gesture.

## 5. Coordinate Scaling While Zoomed

Per `docs/pc-client-detailed-spec.md` §10:

- `ZoomedContentRectCalculator.Compute(zoomState, baseContentRect, frame)` returns a `Rect` representing, in the same view-local pixel coordinate space `baseContentRect` uses, the on-screen area currently showing the zoomed sub-region. `docs/plans/pc-client/video-and-session/decode.PLAN.md`'s `VideoView` uses this rect (instead of `baseContentRect`) to position/scale its rendered `Image` when `ZoomState.Level > 1.0`.
- `docs/plans/pc-client/input-relay/PLAN.md` §3's `MapPointToParentCoords` is fed this same zoomed rect (via the "current content rect" observable that plan already reads generically) instead of the unzoomed `RenderedContentRect` whenever zoom is active — the mapping math itself (§3 of that plan) is unchanged; only which rect is passed in differs. This is the "layered on top of, not a replacement for" relationship `docs/pc-client-detailed-spec.md` §10 explicitly describes.
- No Android-side change, no new wire message — the parent receives ordinary `INPUT_TAP`/`INPUT_SWIPE` messages with correctly-scaled parent-frame coordinates in every case, indistinguishable from a non-zoomed click, per `docs/DECISIONS.md` #18.

## 6. Interactions With Other Features

- **video-and-session/decode**: this plan's `ZoomedContentRectCalculator` output overrides that plan's `RenderedContentRect` for rendering purposes only when zoomed; that plan owns the underlying decoded frame and the unzoomed rect computation this plan builds on.
- **input-relay**: consumes this plan's current content rect for coordinate mapping (§5); this plan has no dependency on input-relay in the other direction.
- **connection-state-ui**: `ZoomState` is reset to `Level = 1.0` on every fresh entry into `Live` (a prior session's zoom level has no meaning for a newly (re)established connection) — mirrors the reset pattern `docs/plans/pc-client/input-relay/PLAN.md` §8 uses for `InputAvailabilityState`.

## 7. Open Items

None. `docs/pc-client-detailed-spec.md` §10 fixes the required behavior (fit-to-window cap, dynamic reclamp, no pan/scroll, layered coordinate mapping, zero wire-protocol/Android-side change) completely; the only open item it names — exact zoom trigger UX — is resolved directly above (mouse-wheel, zoom-toward-cursor) as an implementation-level choice with no product-facing consequence beyond the interaction itself.
