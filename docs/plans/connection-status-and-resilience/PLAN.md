# PLAN: Connection Status & Resilience

<!-- Implementation plan. Covers detailed-spec.md §9 (Parent Device Status Summary), §14 (Connection-Loss Detection Timeout & Reconnect UI), §17 (Waiting-for-Parent State). -->

## Index

1. Scope
2. Components
3. Child-Side Connection State Machine
4. Waiting-for-Parent Flow (§17)
5. Live Session: Status Heartbeat (§9)
6. Missed-Heartbeat Detection & Reconnecting/Disconnected UI (§14)
7. Interactions With Other Features
8. Disconnected-State Messaging After a Process-Kill

## 1. Scope

Owns the child-side connection lifecycle end-to-end: from "child picked/auto-selected a pairing" through waiting, live, reconnecting, and disconnected — plus the parent-device status content shown to the child during a live session. Does not own video rendering or input (remote-input-control) or the parent-side dial-out mechanics (screen-sharing-core §6) — this plan owns the **child-side listener** half of that handshake and everything the child UI shows about connection health.

## 2. Components

| Component | Device | Responsibility |
|---|---|---|
| `ConnectionStateViewModel` | child | Single source of truth for `ConnectionState` (§3). Consumed by the viewer Activity/Fragment and by remote-input-control's input-enable logic. |
| `ChildSessionListener` | child | Owns the child's listening `ServerSocket` on `SESSION_PORT` during `WaitingForParent`; accepts the parent's incoming `SESSION_HELLO`, validates `sharedSecret` against the selected `PairingEntry`, transitions state to `Live` on success. |
| `HeartbeatWatchdog` | child | Tracks time since last `STATUS_HEARTBEAT`/`STATUS_EVENT`/`VIDEO_FRAME` receipt; drives the `Live → Reconnecting → Disconnected` transitions (§6). |
| `ParentStatusMonitor` | parent | Samples Wi-Fi/connectivity state, emits `STATUS_HEARTBEAT` every 5s and `STATUS_EVENT` on change, over the already-open session connection (owned by screen-sharing-core's `ParentSessionServer`; this component only produces the payload and hands it off). |
| `PairingSelectionScreen` | child | Reused from `docs/plans/pairing-and-onboarding/PLAN.md` §3 (`PairingListScreen`'s "select to connect" mode) — listed here too since it's the concrete entry point into this feature's state machine. |
| `WaitingForParentScreen` | child | UI for the `WaitingForParent` state (§4 below). |
| `ReconnectingBanner` / `DisconnectedScreen` | child | UI for §6's two states. |

## 3. Child-Side Connection State Machine

Canonical definition (referenced from `docs/plans/README.md` §5):

```
Idle/PairingSelect
   │  child selects a PairingEntry (auto if only one, per detailed-spec §3)
   ▼
WaitingForParent            (§17, untimed, cancellable)
   │  parent dials in, SESSION_HELLO validated
   ▼
Live                        (§9 heartbeat active; §14 watchdog active)
   │  3 missed heartbeats (~15s)
   ▼
Reconnecting                (§14, ≤60s, video view stays, banner shown)
   │  either: reconnected → back to Live
   │      or: 60s elapsed  → Disconnected
   ▼
Disconnected                (§14, directs back to Idle/PairingSelect)
   │  child dismisses / navigates back
   ▼
Idle/PairingSelect
```

`ConnectionStateViewModel` exposes this as a sealed class:

```
sealed class ConnectionState {
    object Idle : ConnectionState()
    data class WaitingForParent(val pairing: PairingEntry) : ConnectionState()
    data class Live(val pairing: PairingEntry) : ConnectionState()
    data class Reconnecting(val pairing: PairingEntry, val since: Long) : ConnectionState()
    data class Disconnected(val pairing: PairingEntry) : ConnectionState()
}
```

No state here encodes `inputAvailable` (view-only mode) — that is an orthogonal flag owned by `docs/plans/remote-input-control/PLAN.md`, layered on top of `Live`/`Reconnecting` only.

## 4. Waiting-for-Parent Flow (§17)

1. Child selects a `PairingEntry` (directly if the list has exactly one entry, per detailed-spec §3 — `PairingSelectionScreen` is skipped entirely in that case) → `ConnectionStateViewModel` transitions to `WaitingForParent(pairing)`.
2. `ChildSessionListener.start(pairing)`: resolves this device's own Tailscale address (reuses `TailscaleAddressResolver` from pairing-and-onboarding), opens a `ServerSocket(SESSION_PORT)` bound to the Tailscale interface, and calls `accept()` on a background thread/coroutine (`Dispatchers.IO`).
   - Note: this listener is opened fresh each time the child enters `WaitingForParent` — it is **not** an always-on background listener that exists before this point (consistent with detailed-spec §17's "no persistent/background connection attempt or listener is required on the parent's side before this point" — the child-side listener here is scoped to this explicit, foreground, user-initiated waiting state, not a background service, so it carries none of the MyOS-style background-kill risk discussed in §6/Decision #12).
3. `WaitingForParentScreen` shows the "waiting for parent to start sharing" indicator (no timeout — blocks on `accept()` indefinitely until cancelled).
4. On accept: read `SESSION_HELLO`, compare `sharedSecret` to `pairing.sharedSecret`. Mismatch → close the socket, remain in `WaitingForParent` (do not surface an error to the child for a single bad/spoofed attempt — just keep listening; this is a low-security-posture app per spec.md §7, so no lockout/rate-limiting is implemented). Match → transition directly to `Live(pairing)`, hand the now-established `SessionChannel` to the video-render/input path (`docs/plans/remote-input-control/PLAN.md`).
5. **Cancellation**: `WaitingForParentScreen` has a "Cancel" action; triggers `ChildSessionListener.stop()` (closes the `ServerSocket`, unblocking `accept()`) and `ConnectionStateViewModel` transitions back to `Idle/PairingSelect`. Per detailed-spec §17, this can happen at any time, no confirmation needed.
6. Transition to `Live` goes directly from `WaitingForParent` — there is no intermediate `Reconnecting` step for this first-connection case (detailed-spec §17), matching the state machine in §3 (no edge from `WaitingForParent` to `Reconnecting`).

## 5. Live Session: Status Heartbeat (§9)

- `ParentStatusMonitor` (parent-side), started alongside `ScreenShareForegroundService`:
  - Registers a `ConnectivityManager.NetworkCallback` to detect Wi-Fi/mobile transitions immediately (drives `STATUS_EVENT`).
  - A `5000ms` ticker (`docs/plans/README.md` §7 `HEARTBEAT_INTERVAL_MS`) samples current state and sends `STATUS_HEARTBEAT` regardless of whether anything changed (keeps the child's `HeartbeatWatchdog`, §6, fed even when nothing is different).
  - Payload fields: `wifiConnected` (`ConnectivityManager.getNetworkCapabilities` has `TRANSPORT_WIFI`), `networkType` (`"WIFI"` / `"CELLULAR"` / `"NONE"`), `signalStrength` (`WifiManager.getConnectionInfo().rssi`, nullable if unobtainable), `ssid` (`WifiManager.getConnectionInfo().ssid`, nullable — requires location permission on some API levels for SSID visibility; if unavailable at runtime, field is simply omitted/null rather than blocking the feature).
- Child-side: `ConnectionStateViewModel` (or a small `ParentStatusDisplay` sub-component) just renders the latest received payload — no logic beyond display. This is display-only feedback per detailed-spec §9, does not itself drive reconnection (that's §6 in screen-sharing-core, entirely independent).
- Not sent/expected during `WaitingForParent` (§17 scope note) — `HeartbeatWatchdog` (§6 below) is only active once `ConnectionState` is `Live`.

## 6. Missed-Heartbeat Detection & Reconnecting/Disconnected UI (§14)

- `HeartbeatWatchdog` starts when `ConnectionState` becomes `Live`, stops when it leaves `Live`/`Reconnecting` (i.e. on `Disconnected` or a clean `SESSION_END`).
- Tracks `lastFrameOrHeartbeatReceivedAt`, updated on **any** inbound message on the session channel (not just `STATUS_HEARTBEAT` — `VIDEO_FRAME` receipt also counts, since detailed-spec §14 says "no heartbeat/video frames received").
- A `5000ms` check loop: if `now - lastFrameOrHeartbeatReceivedAt >= 3 * HEARTBEAT_INTERVAL_MS` (i.e. `MISSED_HEARTBEAT_THRESHOLD`, `docs/plans/README.md` §7), transition `Live → Reconnecting(pairing, since = now)`.
- `Reconnecting` state: `ReconnectingBanner` shown as additive UI over the still-visible last video frame (per detailed-spec §14, "the live video view need not be cleared immediately"). Underlying: screen-sharing-core's parent-side Layer 1 reconnect (§7 of that plan) is what actually re-establishes the socket; this plan's `ChildSessionListener` re-`accept()`s a fresh incoming connection the same way it did in `WaitingForParent` (the same listener code path is reused — conceptually `Reconnecting` re-arms a short-lived accept loop rather than a brand-new `ServerSocket`, since the child's listening socket itself was never closed on a mere network blip; if the socket was actually closed by the OS mid-blip, `ChildSessionListener` re-opens it).
- On successful reconnect (new `SESSION_HELLO` validated) while in `Reconnecting`: transition back to `Live`, `lastFrameOrHeartbeatReceivedAt` reset.
- If `now - since >= RECONNECT_WINDOW_MS` (60000ms) with no successful reconnect: transition `Reconnecting → Disconnected`. `DisconnectedScreen` replaces the video view (explicit terminal state, not spinning indefinitely), with a single action ("Back to device list") that transitions to `Idle/PairingSelect` — a fresh connection attempt from there is a normal new `WaitingForParent` flow (§4), not a special "resume" path.
- This logic never runs during `WaitingForParent` (§4's scope note / detailed-spec §14's explicit boundary) — a parent who simply hasn't started sharing yet is never misclassified as `Disconnected`.

## 7. Interactions With Other Features

- **screen-sharing-core**: that plan's `ScreenShareForegroundService`/`ParentSessionServer` is the dial-out counterpart to this plan's `ChildSessionListener` (§4 step 2-4 here = screen-sharing-core §6). Both plans must keep `SESSION_PORT` and the `SESSION_HELLO` payload shape in sync via `docs/plans/README.md` §4/§7 — do not let either plan's PLAN.md diverge on that framing without updating README.md too.
- **remote-input-control**: reads `ConnectionStateViewModel`'s state to decide whether to show/enable the video surface and input controls at all (distinct from that plan's own `inputAvailable`/view-only flag, which is layered only within `Live`/`Reconnecting`).
- **screen-rotation-handling**: independent of this plan — rotation reconfiguration happens within an already-`Live` session and does not touch the state machine here.

## 8. Disconnected-State Messaging After a Process-Kill

Resolved via `docs/QA.md` "MediaProjection Resume-After-Process-Kill UX" (RESOLVED) → `docs/DECISIONS.md` #13: option (A/C) — no dedicated recovery UI is added anywhere in the app, including here. This settles the question this section previously left open (whether `Disconnected` messaging should distinguish "genuine Wi-Fi outage, waiting may still resolve it" from "parent's process was fully killed, only a fresh tap can resolve it").

- `HeartbeatWatchdog`/`ConnectionStateViewModel` have no way to distinguish the two causes from the child side either way (both present identically as "no heartbeat/video frames arriving," per `docs/plans/screen-sharing-core/PLAN.md` §10's child-side-implication note) — so no new detection logic is added here to try to tell them apart.
- `DisconnectedScreen` keeps the single generic message already specified in §6 ("Connection to `<label>` was lost. Ask them to try again if this continues.") — this wording already correctly covers both causes without overclaiming a specific fix, and needs no change now that the question is resolved.
- No "tap to resume" affordance, deep link, or distinct wording branch is added for the process-kill case. The child's only recourse in either case is the same as already specified in §6: dismiss to `Idle/PairingSelect` and, once the parent has restarted sharing (manually, via the 2-tap widget flow, per `docs/plans/screen-sharing-core/PLAN.md` §10), start a fresh `WaitingForParent` flow (§4) — there is no special "resume" path distinct from a normal new connection attempt.

This closes the item that previously blocked this section — §1 through §7 above were already fully specified and unaffected by this resolution.
