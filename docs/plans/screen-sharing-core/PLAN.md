# PLAN: Screen Sharing Core

<!-- Implementation plan. Covers detailed-spec.md §4 (Parent-Side Operation Flow / Tap Count), §5 (Screen Sharing Behavior), §6 (Background-Kill Mitigation), §7 (Connectivity/Tailscale), §8 (Screen-Sleep Prevention). -->

## Index

1. Scope
2. Components
3. Widget & Trampoline Flow (2-Tap Design)
4. Duplicate-Launch Guard
5. Capture & Encode Pipeline
6. Network: Session Establishment (Parent Dials Child)
7. Background-Kill Mitigation
8. Wake Lock (Screen-Sleep Prevention)
9. FLAG_SECURE Black-Screen Help Hint
10. Process-Kill Detection & No-Resume Behavior

## 1. Scope

Everything on the parent side needed to go from "widget/app tap" to "live H.264 video reaching the child device," plus keeping that session alive across app switches, screen rotations (delegated to `docs/plans/screen-rotation-handling/PLAN.md`), and background-kill risk. Does not cover the child-side viewer rendering (remote-input-control) or the status heartbeat content (connection-status-and-resilience) — only the transport connection they ride on.

## 2. Components

| Component | Responsibility |
|---|---|
| `ShareStartTrampolineActivity` | Invisible/translucent activity, the single common entry point from both the widget and the in-app "Show Screen" button. Requests the MediaProjection grant and starts `ScreenShareForegroundService`. |
| `ShowScreenWidgetProvider` (`AppWidgetProvider`) | Home-screen widget; its click `PendingIntent` launches `ShareStartTrampolineActivity` directly (detailed-spec §4 target design). |
| `ScreenShareForegroundService` | The single long-lived foreground service for an active session: owns the `MediaProjection` object, `VirtualDisplay`, encoder, network session connection, wake lock, and reconnect logic. Foreground service type: `mediaProjection` (+ `connectedDevice`/`dataSync` not needed). |
| `ScreenCaptureEncoder` | Wraps `MediaCodec` (H.264, `MediaFormat.MIMETYPE_VIDEO_AVC`) + an `ImageReader`-backed or `Surface`-backed `VirtualDisplay` input; emits Annex-B NAL units via callback for the network layer to frame as `VIDEO_FRAME` messages (`docs/plans/README.md` §4). |
| `ParentSessionServer` | Client role in this direction (see §6) — despite the name, the parent is the one *dialing out*; kept in `parent/` package since it's the parent-role's network component. Wraps `net.SessionChannel`, sends `SESSION_HELLO`, then streams `VIDEO_FRAME`s and reads back `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT`. |
| `ParentWakeLockController` | Acquires/releases the `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` (or `PARTIAL_WAKE_LOCK` — see §8) tied to `ScreenShareForegroundService`'s lifecycle. |
| `SessionKeepAliveScheduler` | Registers the WorkManager/AlarmManager defense-in-depth check (§7). |

## 3. Widget & Trampoline Flow (2-Tap Design)

Per detailed-spec §4: widget tap (1) → OS "Start Now?" dialog (2, unavoidable, Decision #1).

1. Widget tap fires `ShareStartTrampolineActivity` with `theme = Theme.Translucent.NoTitleBar` (never visibly renders anything of its own).
2. `onCreate()`: if `ScreenShareForegroundService.isRunning` (companion `MutableStateFlow`/static flag) is true, skip straight to step 4 (duplicate-launch guard, §4 below) instead of requesting a new grant.
3. Otherwise, call `mediaProjectionManager.createScreenCaptureIntent()` via `registerForActivityResult(StartActivityForResult())`. This surfaces the OS dialog (tap 2). On result:
   - Granted: start `ScreenShareForegroundService` via `startForegroundService()`, passing the `resultCode`/`resultData` Intent as service-start extras; the service calls `mediaProjectionManager.getMediaProjection(resultCode, resultData)` once running and immediately calls `startForeground()` (must happen within the OS's 5s window).
   - Denied: finish the trampoline with no further action (parent simply sees nothing happened; acceptable per spec — a denial is a deliberate parent action, out of scope to handle specially).
4. `ShareStartTrampolineActivity.finish()` immediately after handing off to the service (or after the duplicate-launch no-op) — it is never visible for more than a frame.
5. The in-app "Show Screen" button (parent-mode tab, default launch tab per spec.md §4/Decision #5) invokes the exact same `ShareStartTrampolineActivity` rather than duplicating this logic — this is also how the 3-tap baseline flow (detailed-spec §4) is realized on devices/setups without the widget placed.

## 4. Duplicate-Launch Guard

Per detailed-spec §4 "Duplicate-launch guard": `ScreenShareForegroundService` exposes a companion `isRunning: Boolean` (backed by a process-wide flag set `true` in `onCreate()`/cleared in `onDestroy()`). `ShareStartTrampolineActivity` checks this before requesting a new capture intent:
- If already running: do not call `createScreenCaptureIntent()` again (never attempt a second concurrent `MediaProjection` session). Instead, send `ScreenShareForegroundService` an existing-session "bring to front" signal — since there is no parent-side UI screen to "bring to front" beyond the persistent foreground notification itself (which is already visible whenever the service runs), this is a no-op beyond ensuring the notification is not swipe-dismissed (foreground service notifications are already non-dismissible while the service runs, so no extra action is actually needed here — documented as a no-op by construction, not because it was skipped).
- Applies regardless of how many times the widget is tapped in quick succession (`isRunning` check is synchronous and set before the first `createScreenCaptureIntent()` call returns, so a rapid double-tap on the widget itself — which fires two separate trampoline instances — is still guarded, since Android widget `PendingIntent` delivery is sequential per-process and the flag is set as early as possible in the first instance's `onCreate()`).

## 5. Capture & Encode Pipeline

- `MediaProjection.createVirtualDisplay(name, width, height, dpi, flags, surface, callback, handler)` — `surface` is the `MediaCodec` encoder's input surface (`createInputSurface()`), avoiding an extra `ImageReader` copy step.
- Initial `width`/`height`/`dpi` read from `DisplayMetrics` of the default display at capture start.
- Encoder config: `MediaFormat.MIMETYPE_VIDEO_AVC`, bitrate ~4–6 Mbps (implementation default, tunable, no spec-mandated value), `COLOR_FormatSurface`, key-frame interval 2s, `IFrameInterval` set so a freshly (re)connecting child can start decoding promptly.
- Encoder output callback (`MediaCodec.Callback.onOutputBufferAvailable`) reads Annex-B NAL units, forwards each to `ParentSessionServer` as a `VIDEO_FRAME` message with the current `{frameWidth, frameHeight, presentationTimeUs}` header (dimensions repeated per-frame, cheap, and lets the child detect a dimension change — e.g. after a rotation, §12/screen-rotation-handling — without a separate signaling message).
- Rotation reconfiguration (VirtualDisplay resize/recreate) is specified in `docs/plans/screen-rotation-handling/PLAN.md` — this plan's encoder must accept a new input surface size without tearing down `MediaProjection` itself (same grant reused, consistent with detailed-spec §12's "internal adjustment... not a new capture session").

## 6. Network: Session Establishment (Parent Dials Child)

Per detailed-spec §17 / Decision #12: **the parent initiates the live-session connection**, not the child. Direction and timing:

1. The moment `ScreenShareForegroundService.onCreate()` runs and capture successfully starts (i.e. right after step 3 of §3 above completes), the service looks up the `AuthorizedChild` record(s) via `PairingRepository` (`docs/plans/pairing-and-onboarding/PLAN.md` §2.2).
   - If there is exactly one `AuthorizedChild`, dial it directly.
   - If there is more than one (multi-child, not the current real-world scenario but not precluded by the data model), dial all of them — each independently becomes a session peer; out of scope to design a "which child is actively waiting" negotiation beyond attempting all known children, since spec.md only anticipates a single child device in practice.
2. `ParentSessionServer` opens a TCP connection to `childHost:SESSION_PORT` (`docs/plans/README.md` §7 constants), sends `SESSION_HELLO {parentAck: childId, sharedSecret}`.
3. Child (already in its `WaitingForParent` state per `docs/plans/connection-status-and-resilience/PLAN.md` §17-flow, listening on `SESSION_PORT`) accepts, validates `sharedSecret` against its stored `PairingEntry`, and on success transitions to `Live` — full handshake and child-side listener lifecycle detail is owned by `docs/plans/connection-status-and-resilience/PLAN.md`, referenced here only to establish which side dials.
4. If the dial attempt itself fails (child unreachable — e.g. child app not currently in the waiting state, or network issue), the service retries with backoff for as long as the session remains active in principle — this is folded into the same reconnect loop described in §7 below, not a separate mechanism.

## 7. Background-Kill Mitigation

Two layers, per detailed-spec §6:

**Layer 1 — in-process reconnect.** `ParentSessionServer` monitors its own TCP connection (read-loop failure / `SocketException`) and, on disconnect, attempts to re-dial the child (§6 above) with exponential backoff (e.g. 1s, 2s, 4s... capped at 10s) for up to the same 60s window the child observes for its own `Reconnecting` state (`docs/plans/connection-status-and-resilience/PLAN.md` §14) — keeping both sides' timeout expectations aligned. This layer only helps when `ScreenShareForegroundService`'s process survives (e.g. the Service was stopped/restarted by the OS under `START_STICKY` while other app state persists, or a purely network-level Tailscale blip) — see §10 below for the process-death case, which is handled differently (no silent resume, by design).

**Layer 2 — defense-in-depth external check.** `SessionKeepAliveScheduler` registers a periodic check independent of the foreground service's own lifecycle:
- Uses `AlarmManager.setExactAndAllowWhileIdle()` on a short interval (implementation default: 60s while a session is believed active — chosen over `WorkManager`'s `PeriodicWorkRequest` specifically because WorkManager enforces a 15-minute minimum repeat interval, which detailed-spec §6 does not mandate but which would be too coarse to meaningfully catch a mid-session kill; detailed-spec §6 explicitly lists AlarmManager as an equally acceptable alternative to WorkManager, so this is a same-spec-scope implementation choice, not a deviation) rather than `WorkManager`, given the granularity requirement.
- A `BroadcastReceiver` (`SessionKeepAliveReceiver`) fires on each alarm and checks `ScreenShareForegroundService.isRunning`. If the app process itself was killed, `isRunning`'s backing flag is gone too (process memory is gone) — so the receiver instead checks durable state: a `SharedPreferences` flag `sessionWasActive` (written `true` on service start, cleared on a clean/intentional stop) plus a timestamp of the last-known-active moment. If `sessionWasActive == true` and the service is not actually running (checked via `ActivityManager.getRunningServices` or attempting to bind), the receiver restarts `ScreenShareForegroundService` — restoring the process and, where possible (§10), Layer 1's reconnect loop. It never attempts to silently restore a `MediaProjection` grant (see §10 — not possible, and not attempted).
- Whether this scheduled wake-up mechanism itself reliably survives MyOS in practice is an open, tracked verification item (already listed in `docs/QA.md` "Open Verification Items" prior to this plan — not new).

## 8. Wake Lock (Screen-Sleep Prevention)

Per detailed-spec §8:
- Acquire: `ScreenShareForegroundService` acquires `PowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, TAG)`. `PARTIAL_WAKE_LOCK` alone is not sufficient here since it only keeps the CPU running, not the screen on — this feature specifically needs to prevent the *screen* from auto-locking (detailed-spec §8). `SCREEN_BRIGHT_WAKE_LOCK` is deprecated in the Android SDK but remains the only direct Service-level API that achieves this without keeping a foreground Activity alive (which this app has no other reason to do — the parent may background the app entirely during a session); use of a deprecated API here is acceptable given this app's minimal-security, personal-use posture (spec.md §7) and no stated constraint against deprecated APIs. Acquired immediately when capture successfully starts (right after §3 step 3's grant).
- Release: held through the entire `Reconnecting` phase (`docs/plans/connection-status-and-resilience/PLAN.md` §14, up to 60s), released when either (a) the session ends via a normal path (parent-initiated stop, OS-status-bar stop — detected via `MediaProjection.Callback.onStop()`, §9 below — or child-initiated disconnect signaled via `SESSION_END`), or (b) the 60s reconnect window in Layer 1 (§7) expires without success, transitioning to `Disconnected`. Both release paths call `ParentWakeLockController.release()` from `ScreenShareForegroundService.onDestroy()`/its stop-session method, never left dangling.
- Not acquired at any other time (app installed/backgrounded without a live session) — scoped strictly to `ScreenShareForegroundService`'s active lifetime.

## 9. FLAG_SECURE Black-Screen Help Hint

Per detailed-spec §5: the app cannot detect `FLAG_SECURE` blackouts programmatically — no parent-side handling needed for this specific case beyond normal frame delivery (a black frame is just... a black frame, sent normally). The static help hint UI (info icon/tooltip on the child's viewer, always available) is a **child-side** UI element — specified in `docs/plans/remote-input-control/PLAN.md` (co-located with the viewer surface) rather than here, since nothing on the parent side needs to change to support it.

Manual OS-level stop (parent stops sharing from the OS status-bar/notification control): handled via `MediaProjection.registerCallback(object : MediaProjection.Callback() { override fun onStop() {...} }, handler)` — `onStop()` fires identically whether the app or the OS UI initiated the stop, so no special-case branch is needed (detailed-spec §5 confirms this is intentional). `onStop()` triggers the same session-end path as any other: send `SESSION_END`, release wake lock (§8), stop the foreground service, clear `sessionWasActive`.

## 10. Process-Kill Detection & No-Resume Behavior

Resolved via `docs/QA.md` "MediaProjection Resume-After-Process-Kill UX" (RESOLVED) → `docs/DECISIONS.md` #13: option (A/C) chosen — **no dedicated "resume sharing" UI/notification is built.** `detailed-spec.md` §6 now scopes its "brief interruption, no need to redo the tap flow" promise to process-survives / transient-network cases only; a full MyOS process-kill requires the parent to manually redo the existing 2-tap widget flow (§3), with no special in-app affordance for this moment.

**How the service tells the two cases apart (no separate detection code needed — the distinction falls out of process memory state):**

`ScreenShareForegroundService` holds its live `MediaProjection` object only in memory (never persisted to disk — persisting the grant's `resultData` would not help even if done, since a killed-and-restarted process cannot reuse it to resume capture regardless, per §10's platform constraint; so this plan does not attempt to persist it at all).

- **Case 1 — process survives** (e.g. `START_STICKY` restart of just the Service component, or a transient Tailscale/network blip): the in-memory `MediaProjection` reference is still valid because the process never actually died. `onStartCommand()` sees its existing `MediaProjection`/`VirtualDisplay` state is intact (or, for a `START_STICKY` null-`Intent` restart, the Service object is recreated but the *process* and any process-scoped singletons survive) → Layer 1 (§7) reconnects the network session; capture itself was never interrupted.
- **Case 2 — full process-kill**: when `SessionKeepAliveReceiver` (§7 Layer 2) restarts `ScreenShareForegroundService` after finding it not running, the new process has no in-memory `MediaProjection` object and no `resultData` Intent (the restart is triggered by the alarm, not by a fresh trampoline/`ShareStartTrampolineActivity` grant — see §3). `ScreenShareForegroundService.onCreate()` checks for this: if started without a valid `MediaProjection` grant available, it does **not** attempt `mediaProjectionManager.getMediaProjection(...)` (there is nothing valid to call it with) and does **not** attempt any capture/encode/network-dial work. It simply clears the `sessionWasActive` flag (§7) — preventing a restart loop — logs the outcome, and calls `stopSelf()`. No notification is posted; no new UI component exists for this path.
- This distinction requires no dedicated "was this a full kill?" boolean or extra plumbing — the mere absence of a valid, in-memory `MediaProjection` grant at `onCreate()` time (for a service instance not started via the normal trampoline path) is definitionally Case 2, since that grant cannot exist any other way after real process death.

**Child-side implication** (`docs/plans/connection-status-and-resilience/PLAN.md` §3/§6 state machine): unaffected by which case occurred. The child's `HeartbeatWatchdog` has no way to distinguish Case 1 from Case 2 either (both simply present as "no heartbeat/video frames arriving"), and per the resolved decision, it does not need to — both cases converge on the same child-side `Reconnecting` → (if not resolved within 60s) `Disconnected` path already specified in that plan. See that plan's §8 for the corresponding update.

This closes the item that previously blocked this section — the rest of this PLAN (capture/encode, the 2-tap widget flow, network session establishment, wake lock, Layer 1/2 reconnect infrastructure) was already fully specified above and unaffected by this resolution.
