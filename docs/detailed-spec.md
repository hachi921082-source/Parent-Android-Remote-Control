# Detailed Specification

<!-- Elaboration of spec.md to a level usable for implementation plans (docs/plans/<feature-name>/PLAN.md). Created/updated by designer-lite as features are planned. -->

## Index

1. Target Devices
2. Parent-Side Device Setup (One-Time)
3. Pairing Data Model
4. Parent-Side Operation Flow (Tap Count)
5. Screen Sharing Behavior
6. Background-Kill Mitigation
7. Connectivity (Tailscale)
8. Screen-Sleep Prevention
9. Parent Device Status Summary
10. Tap-Position Visualization
11. Accessibility Service Permission Monitoring
12. Screen Rotation Handling
13. Initial Pairing & Permission Onboarding Flow
14. Connection-Loss Detection Timeout & Reconnect UI
15. Aspect Ratio Mismatch & Tap Coordinate Scaling
16. Text Input Mechanism
17. Waiting-for-Parent State (Pre-Session)

<!-- Sections 13-16 were added after the first external review (2026-09-02); numbering of 1-12 is preserved as-is (not renumbered) to avoid invalidating existing §-references elsewhere in this doc and in DECISIONS.md. Section 17 was added after a second external review (2026-09-02), same preservation rule applies. -->

## 1. Target Devices

| Role | Model | Notes |
|------|-------|-------|
| Mother | AQUOS Wish4 | Background-behavior not yet verified. |
| Father | ZTE Nubia S2 (Unisoc Tanggula T760/T8100) | Runs ZTE's proprietary OS "MyOS", confirmed to aggressively force-close background apps. |

## 2. Parent-Side Device Setup (One-Time)

For the ZTE Nubia S2 (father's device) only, perform once during initial setup:
1. Settings → App Management → App List → [Target App] → Battery → Enable "Allow Background Use" → Change Mode to "No Restrictions".
2. Settings → App Management → Auto Start Management → Enable Target App.

No equivalent steps are currently defined for the AQUOS Wish4 (mother's device); see `docs/QA.md` verification-TODO note if this needs revisiting.

## 3. Pairing Data Model

- Stored on the child's device: a list of pairing entries.
- Each entry contains: device ID/key (identifies the paired parent device) and a label. The label is arbitrary, free-form text chosen by the child when the pairing is created — not restricted to a fixed set of role names. "Father"/"Mother" are typical examples, but any string (e.g. "Guest01") is valid.
- When the child starts a connection, if more than one pairing entry exists, the child is shown a selection screen listing each entry's label to choose which device to connect to. If exactly one pairing entry exists, this selection step is skipped automatically.
- Each parent device is paired once, during a one-time setup performed by the child on that parent's device (performed independently for father's device and mother's device).
- The label is purely child-side bookkeeping: it exists only in this pairing-entry list on the child's device. The parent device does not store, transmit, or need to know its own label — it only needs to recognize/accept a connection from its paired child device via the shared device ID/key. There is no role-label field or concept on the parent side at all.
- The label is editable after creation: the child can rename any pairing entry's label at any time from within their own app, without affecting or re-doing the underlying device ID/key pairing (spec.md §5).

## 4. Parent-Side Operation Flow (Tap Count)

### Baseline (3 taps)
1. Open the app (single large icon on the parent's home screen).
2. Tap the "Show Screen" button (parent-mode tab, shown by default on launch).
3. Tap "Start Now" on the OS's MediaProjection confirmation dialog (unavoidable OS behavior, see spec.md §6/§3 non-negotiable constraint).

### Target design (2 taps, via home-screen widget)
- A home-screen widget merges steps 1 and 2 above into a single tap: the widget directly invokes the share-start routine (equivalent to "open app" + "press Show Screen button" combined).
- Resulting flow: widget tap (1) → OS confirmation dialog "Start Now" (2, unavoidable).
- This widget-based design is the target for parent-facing operation; see `docs/DECISIONS.md` for why it was chosen over the 3-tap baseline.

### Duplicate-launch guard
- If a sharing session is already active when the parent taps the widget again (or reopens the app and presses "Show Screen" again), this is treated as a no-op for starting a new session — the app does not attempt to start a second concurrent MediaProjection session. At most, it may bring the existing session's running state/notification to the foreground. This applies regardless of how many times the widget is tapped in quick succession while a session is already active.

## 5. Screen Sharing Behavior

- MediaProjection mirrors the parent device's entire display; per-app sharing is not possible.
- Once started, sharing continues automatically across any app the parent switches to — no restart or extra implementation required, this is native OS behavior.
- Exception: any app on the parent's device that sets `FLAG_SECURE` (e.g. banking apps, password managers) always renders as a black screen in the child's shared view. This is an OS-level protection and cannot be bypassed.
  - The app cannot programmatically distinguish this case (a `FLAG_SECURE` blackout) from an actual connection problem or an actual black screen on the parent's device — Android provides no API for a viewing app to detect the reason behind a black frame. Because of this, the child's viewer UI includes a small persistent static help hint (e.g. an info icon/tooltip, always available rather than dynamically triggered) explaining that a black screen may mean the parent has a protected app open (e.g. banking, password manager) rather than indicating a connection problem. Exact icon/placement/styling is left to implementation/PLAN.md.
- Manual OS-level stop: if the parent stops screen sharing directly from the OS's own status-bar/notification control (rather than through the app's own UI), the app treats this identically to an app-initiated stop — the wake lock is released (§8) and the child is notified the session ended, the same as any other normal session-end path. No special-case handling is needed; this is detected the same way as any other MediaProjection-session-ended callback.

## 6. Background-Kill Mitigation

- Risk: on the father's device (MyOS), the OS may force-close the app's background service. It is unverified whether the kill trigger is idle-time-based or foreground-display-based (see `docs/DECISIONS.md` for the open-risk note).
- Mitigation: the app implements automatic reconnection when its service is killed. This "brief interruption, no need to redo the tap flow" outcome is achievable only for kills where the app's process itself survives (e.g. the service is stopped but the process keeps running) or for a transient network/Tailscale disconnect — in those cases the existing session and its network connection can be silently restored. It is **not** achievable when MyOS performs a full process-kill: Android's MediaProjection grant is bound to the process holding it, so once that process is killed, the grant is invalidated and cannot be silently restored — the parent must physically re-tap the OS's own confirmation dialog (the same unavoidable tap described in `docs/DECISIONS.md` #1). In that case, no special in-app affordance is provided for resuming; the parent (typically prompted by the child, already on the call) simply redoes the existing 2-tap widget flow (§4) — see `docs/DECISIONS.md` for why no dedicated "resume" UI was added for this case.
- Defense in depth: in addition to the foreground service's own in-process reconnect logic above, the app also schedules a periodic self-check via WorkManager (or AlarmManager) that runs independently of the foreground service's lifecycle. This external check detects whether the foreground service has been killed and attempts to restart/reconnect it even if the service's own in-process reconnect logic never got a chance to run before being killed. This exists specifically because MyOS is known to be an aggressive task-killer (§1/§2), and a purely in-service reconnect mechanism could itself be killed before it acts — see `docs/QA.md` Open Verification Items for the unverified question of whether this scheduled-wake-up mechanism itself survives MyOS in practice.
- Scope note: this mitigation applies to an already-active sharing session (a foreground service that is running and needs to survive/reconnect). It does not require, and this app does not implement, an always-on background listener on the parent's device during the pre-session §17 waiting period — see §17's connection-establishment bullet for why that scenario is deliberately avoided rather than also mitigated here.

## 7. Connectivity (Tailscale)

- The app uses Tailscale for device-to-device connectivity.
- Tailscale's Android app requires Android 8.0 or higher; this sets the effective minimum supported Android version for this app (see spec.md §6).
- Not expected to be a practical constraint for the father's Nubia S2.

## 8. Screen-Sleep Prevention

- Purpose: while a sharing session is active, prevent the parent's device screen from auto-locking due to normal idle-timeout, so the child's tap/swipe/text input never silently stops reaching the parent's screen (see `docs/DECISIONS.md` #8).
- Trigger (acquire): a wake lock is acquired as soon as the sharing session actually starts — i.e. immediately after the parent confirms "Start Now" on the MediaProjection dialog and screen capture begins. It is held by the same foreground service that runs the MediaProjection capture (a foreground service is required by the OS for MediaProjection regardless, so this piggybacks on that lifecycle rather than requiring a separate long-running component).
- Trigger (release): the wake lock stays held for the entire duration of the "reconnecting" phase described in §14 (up to 60 seconds) — it is not released the instant the connection/service is lost, since releasing it immediately would risk the parent's device sleeping mid-reconnect-attempt, undermining §6's auto-reconnect mitigation. It is released only when either: (a) the session ends through a normal path — the parent manually stops sharing (including stopping directly from the OS's own status-bar/notification control — see §5, this is treated identically to an app-initiated stop), or the child intentionally disconnects; or (b) §14's 60-second reconnect window expires without success and the state transitions to "disconnected" — at that point the wake lock is released since there is no longer an active or recoverable session. No parent-side action is required to release it — this stays a zero-extra-step feature, consistent with the app's top design priority (spec.md §2).
- Scope: only prevents the device's *automatic* idle-timeout screen lock. It does not override or block a manual power-button press by the parent — if the parent deliberately locks their device mid-session, that is treated as an intentional interruption, not a bug.
- Not held outside an active sharing session (e.g. not held simply because the app is installed or backgrounded without a live session) — this is scoped strictly to session-active time, to avoid unnecessary battery drain on the parent's device.

## 9. Parent Device Status Summary

- Purpose: let the child diagnose connection problems remotely without needing the parent to describe what they see (spec.md §3).
- Content sent: primarily connectivity-related state —
  - Wi-Fi connected / disconnected.
  - Network type currently in use (Wi-Fi vs. mobile data), when determinable.
  - Signal strength, where obtainable from the OS (e.g. Wi-Fi RSSI/bars).
  - Optionally the connected Wi-Fi network's SSID, to help the child confirm the parent is on the expected home network (acceptable to include given the minimal-security, family-only posture of spec.md §7).
- Delivery mechanism: sent over the same live connection channel used for the video stream, as a lightweight periodic heartbeat every 5 seconds, plus an immediate push whenever a monitored value changes (e.g. Wi-Fi drops, signal strength crosses a threshold).
- Handling of full disconnection: if the parent's device loses network connectivity entirely, there is by definition no channel left to explicitly transmit a "disconnected" status message. In that case the child's app infers a "connection lost" state locally, by detecting that no heartbeat/video frames have arrived within an expected timeout window — this is a client-side inference, not a message received from the parent. This is distinct from, and complementary to, the explicit status values above (which cover degraded-but-still-connected states, e.g. weak signal, that precede or explain an eventual drop). See §14 for the exact timeout thresholds and the resulting reconnect UI states.
- Scope note: this heartbeat only exists once a live session is established — there is no heartbeat to send or expect during §17's pre-session waiting state, since no connection/session exists yet at that point. See §14 for the explicit statement that its missed-heartbeat disconnect logic does not apply during that waiting period.
- This status summary is display-only feedback for the child; it does not itself trigger the reconnection behavior in §6, which is driven independently by the connection/service lifecycle.

## 10. Tap-Position Visualization

- Purpose: give the child immediate visual feedback that their tap registered, as operation feedback (spec.md §3).
- Marker source: drawn locally on the child's own viewer overlay, using the coordinates the child's device just sent — not a confirmation echoed back from the parent device. This avoids depending on a network round-trip (and on the reliability risks already tracked in §6/`docs/DECISIONS.md` #6) for basic UI feedback, and keeps the feedback instantaneous.
  - Note: this marker therefore confirms the child's tap was captured and sent, not that it was necessarily received or acted on by the parent's device — actual delivery reliability continues to be handled by the auto-reconnect mitigation in §6, independently of this visual feedback.
- Persistence: the marker is shown for 300ms and then fades out, or is immediately replaced if another tap arrives before it finishes fading — whichever happens first. It never accumulates multiple simultaneous markers from a single tap stream.
- Applies to taps; for swipes, the same local-overlay approach applies to the swipe's start/end (or path), scaled to the same short-lived feedback treatment.
- Exclusion for discarded black-bar taps: this marker is only shown for taps/swipes that land within the valid rendered video content area. Taps/swipes discarded per §15 (because they fall in the letterbox/pillarbox black-bar regions) never trigger this marker — they were never treated as valid input in the first place, so showing the marker for one would misleadingly suggest it registered when it was in fact dropped.

## 11. Accessibility Service Permission Monitoring

- Purpose: catch the case where the Accessibility Service permission (required on the parent's device for the app to inject tap/swipe/text input) has been revoked — by the OS, by the parent accidentally, or otherwise — before the child wastes time sending input that silently does nothing (spec.md §3).
- Check trigger/interval: two checks, both parent-side:
  - Pre-flight check: immediately before dispatching each injected input action (tap/swipe/text), the parent-side service verifies the permission is still granted.
  - Periodic check: additionally, every 30 seconds while a sharing session is active, so a revocation is caught proactively even if the child hasn't attempted new input since it happened. This interval can be relatively relaxed because the pre-flight check above already catches revocation on every actual input attempt — the periodic check only exists to surface it during idle stretches, not as the primary safety net.
- What "guides recovery" means: Android does not allow this permission to be granted programmatically — like the MediaProjection dialog (`docs/DECISIONS.md` #1), (re-)enabling an Accessibility Service requires a physical tap by a human on the device that holds the permission, i.e. the parent's device. This makes it structurally different from the other monitoring items in this document: the app cannot fully resolve it hands-off, only guide the human through it.
- Given spec.md §2's established constraints — the parent's tech proficiency is assessed as too low to reliably self-navigate even basic phone features, and any parent-side action beyond the core share-start flow is treated as high-risk — the primary recovery path is **not** an in-app prompt aimed at the parent navigating Settings alone. Instead, the detected-revoked state is relayed to the child's device (via the same status channel described in §9), so the tech-comfortable child can talk the parent through re-enabling it over the phone call already in progress, the same way the app's overall design assumes the child mediates anything beyond the core flow.
- As a secondary, best-effort layer, a basic parent-side system notification (using the OS's own accessibility-revoked notification, or a minimal deep link to the Accessibility settings screen if the app adds one) may also be surfaced on the parent's device — but this is not relied upon as the primary recovery mechanism, only as an additional signal in case the child isn't immediately available to help.
- Independence from screen sharing: MediaProjection screen capture/sharing is entirely independent of the Accessibility Service permission. Losing the Accessibility permission does **not** stop or interrupt the video/screen-sharing stream — it only stops input injection (tap/swipe/text) from working. When the app detects (via the pre-flight or periodic check above) that Accessibility permission has been revoked, the child's viewer UI switches to an explicit "view-only" state (e.g. a visible banner/indicator saying input is currently unavailable) while continuing to show the live video normally. This state clears automatically once a subsequent pre-flight or periodic check detects the permission has been restored. Exact banner/indicator visual design is left to implementation/PLAN.md.
- Scope of "view-only": entering view-only mode disables ALL input UI on the child's viewer, not just tap/swipe — both the tap/swipe pointer input and the text-input compose/send control (§16) are disabled, since none of them can function without the Accessibility Service permission on the parent's device.

## 12. Screen Rotation Handling

- Purpose: when the parent physically rotates their device, the child's view should automatically fit the new aspect ratio without requiring the sharing session to be stopped and restarted (spec.md §3).
- What re-fitting entails: a rotation changes the dimensions/aspect ratio of the frames MediaProjection captures on the parent's device (e.g. width/height swap for a 90-degree rotation). The parent-side capture reconfigures its output to match the new dimensions (an internal adjustment to the existing capture surface, not a new capture session), and the new frame dimensions are communicated to the child's device alongside the video stream. The child's rendering surface detects the dimension change and reflows/resizes its display view to match the new aspect ratio.
- Continuity requirement: this must not interrupt the underlying sharing session or connection — consistent with the full-screen-sharing continuity behavior already established in §5 (sharing continues across app switches with no restart). Rotation is handled as an in-session reconfiguration of the capture/render pipeline, not a stop/restart of MediaProjection or of the Tailscale connection.
- Applies symmetrically whenever the parent rotates back (e.g. portrait → landscape → portrait); each transition is handled the same way.
- Interaction with tap/swipe input: a rotation event is one of the cases where the child's rendered video area and/or the parent's frame dimensions change size, requiring tap/swipe coordinate scaling to be recalculated — see §15.

## 13. Initial Pairing & Permission Onboarding Flow

- Purpose: describe the one-time setup flow that establishes a working parent-child pair, performed by the child while physically operating the parent's device — consistent with spec.md §5's pairing model ("one-time setup of each pairing is performed by the child").
- Steps, performed once per parent device:
  1. Install the app on the parent's device.
  2. The child walks through enabling the Accessibility Service permission on the parent's device. This is a persistent, one-time grant — once enabled it stays enabled (unless revoked, see §11), unlike the MediaProjection confirmation dialog in §4/`docs/DECISIONS.md` #1, which is **not** a one-time/onboarding step: it reappears every session and stays part of the daily share-start flow regardless of onboarding.
  3. For the ZTE Nubia S2 (father's device) specifically, perform the battery/autostart settings changes described in §2.
  4. Establish the pairing itself: exchange the device ID/key between the parent's device and the child's device so the parent device can recognize and accept connections from that child device (§3). The exact mechanism (e.g. QR code scan, or another pairing-code method) is an implementation detail left to developer-lite/PLAN.md — not specified here.
  5. The child assigns a label (e.g. "Father"/"Mother") to the newly added pairing entry, stored locally in their own app's pairing list (§3). Consistent with §3's clarification, this label is never sent to or stored on the parent device — it exists solely for the child's own bookkeeping when multiple parents are paired.
- This flow is repeated independently for each parent device being paired (e.g. once for father's device, once for mother's device), consistent with `docs/DECISIONS.md` #3.

## 14. Connection-Loss Detection Timeout & Reconnect UI

- Purpose: give the child a clear, bounded UI state when the connection to the parent's device drops, instead of the app's local "connection lost" inference (§9) being silent or open-ended.
- Detection timeout: the child's device considers the connection "lost" — triggering the local client-side inference already described in §9 — after 3 consecutive missed heartbeats. Given the 5-second heartbeat interval (§9), this is approximately 15 seconds of silence with no heartbeat/video frames received.
- Reconnecting state: once connection loss is detected, the child's viewer shows a "reconnecting..." indicator while §6's auto-reconnect mitigation continues working in the background. The live video view need not be cleared immediately; the indicator is additive feedback that a reconnect is in progress. The parent-side wake lock (§8) remains held throughout this entire reconnecting phase, so the parent's screen cannot sleep and interrupt an in-progress reconnect attempt.
- Disconnected state: if reconnection has not succeeded within 60 seconds total (measured from the point connection loss was first detected), the viewer switches from the transient "reconnecting" indicator to an explicit "disconnected" state that directs the child back to the idle/waiting screen — rather than spinning/waiting indefinitely. From the disconnected state, the child would need to explicitly restart the connect flow (e.g. re-select the parent to connect to, per §3), the same as any fresh connection attempt.
- Exact visual design of the "reconnecting" and "disconnected" indicators (placement, styling, wording) is left to implementation/PLAN.md; this section specifies only the timing thresholds and the two distinct UI states involved.
- Scope boundary with §17: this section's missed-heartbeat detection (3 missed heartbeats, ~15s) and the resulting "reconnecting"/"disconnected" states apply only once a session has actually gone live — i.e. after the first frame/heartbeat has been exchanged following the connection established per §17. They do not run during §17's pre-session waiting state itself, since there is no prior session to monitor for loss at that point. A parent who simply has not yet completed the share-start flow (§4) must never be misclassified as "disconnected" through this timeout logic — that scenario is covered by §17's own (untimed) waiting state, not by this section.

## 15. Aspect Ratio Mismatch & Tap Coordinate Scaling

- Purpose: define correct behavior when the child's viewer area and the parent's captured frame do not share the same aspect ratio — including immediately after a rotation event (§12) — and how tap/swipe coordinates are kept accurate under that mismatch.
- Video display (letterbox/pillarbox): when the child's viewer display area and the parent's captured frame aspect ratio don't match, the child's viewer letterboxes or pillarboxes the video — it preserves the parent's actual aspect ratio and adds black bars, rather than stretching or cropping the video to fill the viewer area. See `docs/DECISIONS.md` for why this approach was chosen over stretch-to-fill.
- Tap/swipe coordinate scaling: tap and swipe coordinates the child sends are calculated relative to the actual rendered video content area only (i.e. excluding any letterbox/pillarbox black bars), then normalized/scaled to the parent's actual frame resolution before being sent for injection. Coordinates are never calculated relative to the child's full screen area — only relative to the rendered video content region within it.
- Recalculation trigger: this scaling calculation applies to every tap/swipe, and must be recalculated whenever either of the following changes: the size of the rendered video content area on the child's screen (e.g. child device rotation, window resize), or the parent's actual frame dimensions (e.g. after a parent-side rotation event, §12).
- Letterbox/pillarbox (black-bar) area taps: tap/swipe input events whose coordinates fall outside the rendered video content area — i.e. within the letterbox/pillarbox black-bar regions — are discarded entirely. They are not sent to the parent device at all, and are not clamped/snapped to the nearest valid in-video coordinate. This prevents an accidental tap near the screen edge (where black bars are more likely to be touched) from being misapplied to the edge of the parent's actual screen content. See `docs/DECISIONS.md` for why discard was chosen over clamping. See also §10: a discarded black-bar tap never shows the tap-position marker, since it was never treated as valid input.

## 16. Text Input Mechanism

- Purpose: define how text the child types is delivered to and entered on the parent's device (spec.md §3 text input requirement).
- Transport: text is sent as a complete string, not streamed per-keystroke — the child composes the full text locally, then sends it as a single message once complete (e.g. on submit/send), to the parent device over the same live connection channel used for other input (§9's channel).
- Injection mechanism: on the parent side, the app injects the received text via the Accessibility Service's `ACTION_SET_TEXT` action, applied to the currently-focused editable node on the parent's screen.
- Clipboard: the clipboard is not used at any point in this flow. The parent's actual clipboard contents are never read, touched, or overwritten by this feature.
- Availability: the text-input UI is disabled whenever the viewer is in view-only mode (§11), since text injection requires the Accessibility Service permission the same as tap/swipe does. Because the UI itself prevents the attempt in that state, there is no separate "send attempted while permission is missing" error case to handle.
- Failure feedback (no focused field): if the parent-side `ACTION_SET_TEXT` injection fails because no editable node currently has input focus on the parent's screen (as opposed to the permission being missing, covered above), this failure is relayed back to the child via the existing status channel (§9) as a lightweight, non-blocking notice (e.g. a brief indication that the parent's screen has no active text field right now) — it is not failed silently. This failure mode is specific to text input; it does not apply to tap/swipe input, which doesn't require a focused node.
- See `docs/DECISIONS.md` for why per-keystroke streaming and clipboard-based pasting were considered and rejected in favor of this approach.

## 17. Waiting-for-Parent State (Pre-Session)

- Purpose: define the child's viewer state for the window between "child initiates a connection" and "parent has actually started sharing" — distinct from the "reconnecting" state in §14, which covers a session that existed and then dropped. This state covers a session that has not been established yet at all.
- Trigger: the child initiates a connection to a paired parent device — either auto-selected because only one pairing entry exists, or after choosing one from a list (§3) — but the parent has not yet completed the share-start flow (§4). In this case the child's viewer shows a distinct "waiting for parent to start sharing" state, worded/treated differently from "reconnecting" (§14), since no session has been established yet and this is not a dropped-connection scenario.
- No forced timeout: unlike §14's 60-second reconnect window, this waiting state is not subject to a forced timeout — the child can wait indefinitely while the parent gets around to completing the share-start flow (spec.md §2, §4).
- Cancellation: the child can manually cancel this waiting state at any time and return to the pairing-selection/idle screen (§3), rather than being forced to wait.
- Transition to live: once the parent completes the share-start flow (§4), the child's view transitions directly into the live video state — there is no intermediate "reconnecting" step for this first-connection case, since nothing was ever connected before.
- Connection establishment mechanism (parent-initiated, not child-polled): the app does not rely on an always-on background listener on the parent's device during this waiting period. Instead, connection establishment is parent-initiated: at the moment the parent completes the §4 share-start flow — i.e. right after confirming "Start Now" on the MediaProjection dialog, when the app's foreground service begins running — the parent's device, whose foreground service is now guaranteed to be running, initiates the connection to the already-waiting child device over Tailscale (§7). The child, already sitting in this waiting state, receives that incoming connection and transitions directly to the live video state described above. No persistent/background connection attempt or listener is required on the parent's side before this point, and the child does not poll for reachability while waiting. This is why an always-on listener would be strictly riskier than necessary — see §6's Background-Kill Mitigation scope note, and `docs/DECISIONS.md` for the full rationale.
- Exact visual design of the "waiting for parent" indicator (placement, styling, wording) is left to implementation/PLAN.md; this section specifies only the trigger, the no-timeout behavior, and the transition condition.
