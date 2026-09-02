# PLAN: Screen Rotation Handling

<!-- Implementation plan. Covers detailed-spec.md §12 (Screen Rotation Handling). Kept standalone rather than folded into screen-sharing-core or remote-input-control, since it touches both symmetrically (parent-side capture reconfiguration + child-side render/coordinate recalculation) and neither plan should need to read deep into the other's PLAN.md to get the full picture. -->

## Index

1. Scope
2. Parent Side: Capture Reconfiguration
3. Wire Signaling
4. Child Side: Render/Coordinate Recalculation
5. Continuity Requirements
6. Interactions With Other Features

## 1. Scope

Defines what happens, on both devices, when the parent physically rotates their device during an active session — without stopping/restarting `MediaProjection` or the Tailscale/session connection. Does not redefine the letterbox math or coordinate transform themselves (owned by `docs/plans/remote-input-control/PLAN.md` §3/§4) — only how/when those get re-triggered, and how the parent-side capture pipeline reconfigures.

## 2. Parent Side: Capture Reconfiguration

- `ScreenShareForegroundService` registers a `DisplayManager.DisplayListener` (`onDisplayChanged`) on the default display, or equivalently observes `Configuration` changes via a headless `ComponentCallbacks2`/`OrientationEventListener` — chosen approach: `DisplayManager.DisplayListener.onDisplayChanged(displayId)`, since it fires reliably on rotation independent of any Activity lifecycle (the service has no Activity to hang a `Configuration` callback off during background operation).
- On a display-dimension change (compare new `Display.getRealSize()`/`DisplayMetrics` against the currently configured `VirtualDisplay` dimensions — width/height swapped for a 90°/270° rotation):
  - **API 31+**: call `VirtualDisplay.resize(newWidth, newHeight, densityDpi)` — in-place resize of the existing `VirtualDisplay`, same `MediaProjection` grant, no new capture session (matches detailed-spec §12's "internal adjustment to the existing capture surface").
  - **API 26–30** (no `VirtualDisplay.resize()` API available): release the current `VirtualDisplay` and immediately call `mediaProjection.createVirtualDisplay(...)` again with the new dimensions, reusing the **same** `MediaProjection` object (not re-requesting the grant — `MediaProjection` supports creating a new `VirtualDisplay` any number of times after the original grant, this is a normal supported pattern, not equivalent to Decision #1's one-time dialog). The encoder's input `Surface` is likewise recreated to match (`MediaCodec` reconfigured via `stop()`/`configure()`/`start()` with the new dimensions, or a fresh `MediaCodec` instance — implementation detail, either is acceptable; a fresh instance is simpler to reason about and avoids `MediaCodec`'s more error-prone in-place-reconfigure edge cases).
- This reconfiguration is symmetric — portrait→landscape and landscape→portrait both go through the same code path (detailed-spec §12's "applies symmetrically" requirement is satisfied by construction, since there's no orientation-specific branching, only a dimension comparison).

## 3. Wire Signaling

No dedicated "rotation happened" message type is needed. Per `docs/plans/README.md` §4, every `VIDEO_FRAME` message already carries a `{frameWidth, frameHeight, presentationTimeUs}` header alongside the NAL payload. The first frame encoded after a reconfiguration (§2) naturally carries the new dimensions — the child detects the change by simple comparison against its last-known `FrameGeometry`, with no separate signaling round-trip required and no risk of the video-dimension-change and the "rotation event" ever going out of sync (they are the same event, by construction).

## 4. Child Side: Render/Coordinate Recalculation

- `VideoDecoderView` (from `docs/plans/remote-input-control/PLAN.md`) compares each incoming `VIDEO_FRAME` header's `{frameWidth, frameHeight}` against its current `FrameGeometry`; on a change, updates `FrameGeometry` and reconfigures the `MediaCodec` decoder if needed (a genuine dimension change typically requires the decoder's output surface/format to adapt — handled via the decoder's `onOutputFormatChanged` callback, standard `MediaCodec` decode-side handling, not rotation-specific).
- The `FrameGeometry` update triggers `LetterboxLayoutCalculator`/`mapTouchToParentCoords` recalculation exactly as already specified in `docs/plans/remote-input-control/PLAN.md` §3/§4's "Recalculation trigger" bullets — this plan does not duplicate that logic, only confirms a parent-side rotation is one of the two trigger conditions listed there (the other being the child's own view-size changes, which is independent of this plan).

## 5. Continuity Requirements

Per detailed-spec §12: no interruption to the underlying session/connection. Concretely:
- `ParentSessionServer`'s TCP connection (`docs/plans/screen-sharing-core/PLAN.md` §6) is untouched by §2's reconfiguration — only the `VirtualDisplay`/encoder are recreated, not the network socket.
- The child's `ConnectionState` (`docs/plans/connection-status-and-resilience/PLAN.md` §3) does not change during a rotation — no transition through `Reconnecting`/`Disconnected`; the brief gap between the old `VirtualDisplay` being released and the new one producing its first frame (API 26–30 path) is short enough (well under the 15s missed-heartbeat threshold, `docs/plans/README.md` §7) that it is not expected to trip `HeartbeatWatchdog`, especially since `STATUS_HEARTBEAT` messages (unrelated to video frames, sent on their own 5s ticker) continue uninterrupted throughout — the session channel itself is never touched by this reconfiguration.

## 6. Interactions With Other Features

- **screen-sharing-core**: owns `ScreenShareForegroundService`/`MediaProjection`/encoder that this plan's §2 reconfigures in place — implemented as an addition to that service, not a separate component/service.
- **remote-input-control**: owns the actual letterbox/coordinate-scaling math this plan's §4 re-triggers — see that plan's §3/§4 for the formulas; this plan does not redefine them.
