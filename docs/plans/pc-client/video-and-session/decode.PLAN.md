# PLAN: PC Client — Video & Session: Decode

<!-- Implementation plan. Covers pc-client-detailed-spec.md §5 (Video Transport & Decode). Part
of the PC Viewer Client feature. Sibling plan docs/plans/pc-client/video-and-session/connectivity.PLAN.md
covers session establishment (§6) — split per explicit user authorization given this area's size. -->

## Index

1. Scope
2. Components
3. NuGet Dependency: Media Foundation Interop
4. Decode Pipeline (Annex-B NAL Feed → Decoded Frames)
5. Low-Latency Configuration
6. Rendering Surface & Letterbox/Pillarbox
7. Rotation / Dimension-Change Handling
8. No Pause/Resume on Minimize or Occlusion
9. FLAG_SECURE Help Hint
10. Interactions With Other Features
11. Open Items

## 1. Scope

Turning the raw H.264 Annex-B byte stream arriving over the session connection (owned by `docs/plans/pc-client/video-and-session/connectivity.PLAN.md`) into displayed, correctly-scaled video frames. Does not own the network connection itself (only consumes already-deframed `VIDEO_FRAME` payloads handed to it), and does not own tap/swipe capture (that reads this plan's `RenderedContentRect` output but is owned by `docs/plans/pc-client/input-relay/PLAN.md`).

## 2. Components

| Component | Responsibility |
|---|---|
| `Video/MediaFoundationDecoder` | Wraps the Media Foundation H.264 decoder MFT; accepts Annex-B NAL access units, emits decoded frames as `IMFSample`/converted bitmap data. |
| `Video/VideoView` (WPF `UserControl`, hosting an `Image`/`D3DImage` or `WriteableBitmap`-backed `Image`) | Renders the latest decoded frame, computes `RenderedContentRect` (letterbox/pillarbox), owns `FrameGeometry` updates from incoming `VIDEO_FRAME` headers. |
| `Video/LetterboxLayoutCalculator` | Computes `RenderedContentRect` from the control's current size + current `FrameGeometry` — ported directly from the Android `LetterboxLayoutCalculator` (`docs/plans/remote-input-control/PLAN.md` §3). |
| `Common/FrameGeometry`, `Common/RenderedContentRect` | Plain data types, ported from `docs/plans/README.md` §6. |

## 3. NuGet Dependency: Media Foundation Interop

Per `docs/pc-client-detailed-spec.md` §5 / `docs/DECISIONS.md` #16: Windows Media Foundation (OS-native), not FFmpeg. Raw COM interop against Media Foundation's C++ interfaces (`IMFTransform`, `IMFMediaBuffer`, `IMFSample`, `ICodecAPI`) from C# is realistic but verbose; this plan uses **Vortice.MediaFoundation** (a maintained, MIT-licensed, source-generated .NET wrapper over the Media Foundation COM API — part of the broader Vortice.Windows project family already commonly used for exactly this kind of Windows-native interop in .NET) as the interop layer, rather than hand-rolling COM interop or pulling in a third-party *codec* library. This does not conflict with `docs/DECISIONS.md` #16's rationale: Vortice.MediaFoundation contains no decoder of its own and bundles no native codec binary — it is purely a typed interop/binding layer over the OS's own Media Foundation DLLs, so it introduces no LGPL-style redistribution obligation (the concern #16 was specifically avoiding). If, during implementation, direct P/Invoke interop proves preferable for some reason, that is a same-scope substitution (still "Media Foundation via typed C# interop, no third-party codec") and does not require revisiting this decision.

## 4. Decode Pipeline (Annex-B NAL Feed → Decoded Frames)

Per `docs/pc-client-detailed-spec.md` §5 — the parent's H.264 stream is unchanged (Annex-B, in-band SPS/PPS, no `csd-0`/`csd-1`, `docs/plans/remote-input-control/PLAN.md` §11's "Implementation Notes" confirms this exact framing on the sender side):

1. `MediaFoundationDecoder` is instantiated once per session (constructed when `ConnectionState` first becomes `Live`, disposed when it leaves `Live`/`Reconnecting` for `Disconnected` — see `docs/plans/pc-client/connection-state-ui/PLAN.md` §3).
2. Configures the H.264 Decoder MFT (`MFTEnumEx` with `MFT_CATEGORY_VIDEO_DECODER` + `MFVideoFormat_H264` input type, or the well-known CLSID for the Microsoft H.264 decoder MFT directly) with an output type of `MFVideoFormat_NV12` or `MFVideoFormat_RGB32` — `RGB32` chosen as the output format specifically to avoid a separate CPU-side color-space-conversion step before handing pixels to WPF's `WriteableBitmap` (trades a small amount of MFT-internal conversion cost for simpler downstream code — an implementation-level choice with no product-facing consequence).
3. Each inbound `VIDEO_FRAME` message (already deframed by `connectivity.PLAN.md`'s `Net.SessionChannel`) hands its Annex-B NAL payload to the decoder as one `IMFSample` (`ProcessInput`), tagged with the header's `presentationTimeUs` (converted to Media Foundation's 100ns units).
4. Decoded output is pulled via `ProcessOutput` in a loop (an MFT may buffer internally and not always produce one output per input) and each resulting frame is copied into a `WriteableBitmap` (`VideoView`'s backing store), then `VideoView.InvalidateVisual()`/`Dispatcher.Invoke` marks it for redraw on the UI thread — mirrors the Android `VideoDecoderView`'s synchronous, single-thread-driven decode pattern (`docs/plans/remote-input-control/PLAN.md` §11's "Implementation Notes": decode runs on the network read-loop's thread, only the final UI-affecting call hops to the appropriate thread — here, `Dispatcher.Invoke`/`BeginInvoke` for the WPF UI thread, playing the same role as Android's `post {}` hop to the main thread).
5. Malformed/undecodable input (a corrupt or out-of-order NAL, extremely unlikely given TCP's in-order delivery but not impossible if a session reconnects mid-stream) is handled by simply dropping the frame and continuing — the next IDR (key frame, sent by the parent at a 2s interval per `docs/plans/screen-sharing-core/PLAN.md` §5) self-heals the decoder state; no explicit error-recovery/restart logic is needed beyond what the MFT already does internally for a single bad sample.

## 5. Low-Latency Configuration

Per `docs/pc-client-detailed-spec.md` §5 (required, firm product-level requirement): the decoder MFT is configured via its `ICodecAPI` interface, setting `CODECAPI_AVLowLatencyMode = true` (and, if the specific decoder instance exposes it, `CODECAPI_AVDecVideoAcceleration_H264` left at its hardware-accelerated default) immediately after MFT creation, before the first `ProcessInput` call. This is not optional/deferred — verified as part of this plan's own sanity check (observable via decode-to-display latency during manual testing, not a unit-testable property) before the feature is considered done, since the product requirement is explicit that default buffering (which can introduce 1-2s of latency) defeats the real-time remote-assistance purpose of this app.

## 6. Rendering Surface & Letterbox/Pillarbox

Per `docs/detailed-spec.md` §15 / `docs/DECISIONS.md` #10, reused as-is (`docs/pc-client-detailed-spec.md` §5):

- `LetterboxLayoutCalculator.ComputeContentRect(controlWidth, controlHeight, frame)` — identical algorithm to the Android `LetterboxLayoutCalculator` (`docs/plans/remote-input-control/PLAN.md` §3's `computeContentRect`, ported to C# with `Rect`/`double` in place of `RectF`/`Float`): compares `controlWidth/controlHeight` against `frame.ParentFrameWidth/ParentFrameHeight`, computes a centered content sub-rectangle with black bars on the wider or taller axis, never stretching.
- `VideoView`'s `Image` element is stretched to fill the computed content rect only (via `Margin`/explicit `Width`/`Height` binding on the `Image`, with the surrounding `VideoView` background painted black) — WPF's own layout system does the positioning, no manual pixel compositing of black bars is needed (simpler than the Android `TextureView.setTransform(Matrix)` approach, since WPF's layout/measure/arrange pass already does this natively).
- Recalculated whenever: `VideoView.SizeChanged` fires (window resize — §1's "PC window size is independent of parent-side rotation" rule from `docs/pc-client-detailed-spec.md` §5 applies here: only the content rect reflows, the window itself is never auto-resized), or a new `VIDEO_FRAME` header reports different `FrameWidth`/`FrameHeight` than the current `FrameGeometry` (parent-side rotation, mirroring `docs/detailed-spec.md` §12's Android behavior).

## 7. Rotation / Dimension-Change Handling

Per `docs/pc-client-detailed-spec.md` §5: identical trigger/recalculation model to §6 above — a dimension change is just another `FrameGeometry` update, no special-cased "rotation event" exists at the wire level (the Android parent doesn't send one either, per `docs/plans/screen-rotation-handling/PLAN.md`). `docs/plans/pc-client/zoom-magnifier/PLAN.md` and `docs/plans/pc-client/input-relay/PLAN.md` both subscribe to the same `RenderedContentRect`/`FrameGeometry` observable this plan exposes, so they never disagree about the current mapping — same "single source of truth" principle as the Android app's `docs/plans/README.md` §6.

## 8. No Pause/Resume on Minimize or Occlusion

Per `docs/DECISIONS.md` #23: `MediaFoundationDecoder` keeps decoding continuously regardless of `MainWindow`'s `WindowState` (minimized or not) or Z-order occlusion — no `IsVisible`/`WindowState` event handler exists anywhere in this plan that pauses or resumes the decode loop. This is a deliberate simplicity choice (documented in the decision, not an oversight) — implementing pause/resume was evaluated and rejected given the restore-time glitch risk and the marginal CPU savings not being worth the added complexity at this project's scale.

## 9. FLAG_SECURE Help Hint

Per `docs/pc-client-detailed-spec.md` §5: a small info-icon `Button`/`Image` near `VideoView`'s corner, showing a `ToolTip` (WPF's native hover-tooltip mechanism — the desktop-appropriate equivalent of the Android tap-to-open-dialog interaction, per `docs/pc-client-spec.md` §3) with the same informational text as the Android child's hint (`docs/plans/remote-input-control/PLAN.md` §9): "A black screen may mean a protected app (like banking or a password manager) is open on their device — this isn't necessarily a connection problem." No detection logic exists (or can exist) for this case — the app cannot distinguish a `FLAG_SECURE` blackout from any other black frame, so this is purely a static, always-available hint, not a dynamically-triggered one.

## 10. Interactions With Other Features

- **video-and-session/connectivity**: that plan's `Net.SessionChannel` hands already-deframed `VIDEO_FRAME` payloads to this plan's `MediaFoundationDecoder`; this plan does not open or read from the socket itself.
- **connection-state-ui**: `MediaFoundationDecoder`'s construction/disposal is tied to that plan's `ConnectionState` transitions (§4 above) — this plan does not independently decide when a session is live.
- **input-relay / zoom-magnifier**: both consume this plan's `RenderedContentRect`/`FrameGeometry` for coordinate mapping; this plan has no dependency in the other direction.

## 11. Open Items

None on the product-facing requirements (Media Foundation, low-latency mode, letterbox/no-stretch, no pause/resume — all fixed by `docs/pc-client-detailed-spec.md` §5 / `docs/DECISIONS.md` #16/#23). The exact Media Foundation API surface (§3) and output pixel format (§4 step 2) are implementation-level choices resolved directly above, per `docs/pc-client-detailed-spec.md` §5's explicit note that this level of detail is "left to the eventual PLAN.md."
