# PLAN: PC Client — Connection-State-Driven UI

<!-- Implementation plan. Covers pc-client-detailed-spec.md §6 (pairing-entry-selection flow on
launch, exclusive-session note) and §8 (Connection-State-Driven UI). Part of the PC Viewer
Client feature. -->

## Index

1. Scope
2. Components
3. PC-Side Connection State Machine
4. Pairing-Entry Selection Flow on Launch
5. WaitingForParent Screen
6. Live Screen (Status Panel)
7. Reconnecting / Disconnected UI
8. Window-Close Behavior
9. Interactions With Other Features
10. Open Items

## 1. Scope

Owns the PC client's connection lifecycle end-to-end — from launch/pairing-selection through waiting, live, reconnecting, and disconnected — plus the parent-device status display shown during a live session, and which screen is hosted in `MainWindow`'s content root at any given time. Does not own video decode (`docs/plans/pc-client/video-and-session/decode.PLAN.md`) or input capture (`docs/plans/pc-client/input-relay/PLAN.md`) — this plan owns the state machine and screen routing those features render into.

## 2. Components

| Component | Responsibility |
|---|---|
| `Session/ConnectionStateStore` | Single source of truth for `ConnectionState` (§3). Consumed by every content view and by `input-relay`'s enable/disable logic. |
| `Session/HeartbeatWatchdog` | Tracks time since last `StatusHeartbeat`/`StatusEvent`/`VideoFrame` receipt; drives `Live → Reconnecting → Disconnected` transitions (§7). |
| `Pairing/PairingListView`, `Pairing/ManualPairingView` | Reused from `docs/plans/pc-client/pairing-pc-side/PLAN.md` §5/§7 — this plan decides *when* each is shown, not what they contain. |
| `Session/WaitingForParentView` | UI for the `WaitingForParent` state (§5). |
| `Session/LiveView` | Hosts `video-and-session/decode`'s `VideoView`, `input-relay`'s `TextInputBar`, and this plan's status panel (§6), composed together as the `Live`/`Reconnecting` content. |
| `Session/ReconnectingBanner`, `Session/DisconnectedView` | UI for §7's two states. |
| `Session/ParentStatusPanel` | Renders the latest `StatusHeartbeat`/`StatusEvent` payload (Wi-Fi connected, network type, signal strength, SSID) — display-only. |

## 3. PC-Side Connection State Machine

Ported case-for-case from `docs/plans/README.md` §5 / `docs/plans/connection-status-and-resilience/PLAN.md` §3, per `docs/pc-client-detailed-spec.md` §8 ("no new states, no different timing for this client"):

```csharp
public abstract record ConnectionState
{
    public sealed record Idle : ConnectionState;
    public sealed record WaitingForParent(PcPairingEntry Pairing) : ConnectionState;
    public sealed record Live(PcPairingEntry Pairing) : ConnectionState;
    public sealed record Reconnecting(PcPairingEntry Pairing, DateTimeOffset Since) : ConnectionState;
    public sealed record Disconnected(PcPairingEntry Pairing) : ConnectionState;
}
```

```
Idle/PairingSelect
   │  operator selects a PcPairingEntry (auto if only one, §4)
   ▼
WaitingForParent            (untimed, cancellable)
   │  parent dials in, SESSION_HELLO validated (video-and-session/connectivity §4)
   ▼
Live                        (heartbeat active; watchdog active)
   │  3 missed heartbeats (~15s)
   ▼
Reconnecting                (≤60s, video view stays, banner shown)
   │  either: reconnected → back to Live
   │      or: 60s elapsed  → Disconnected
   ▼
Disconnected                (directs back to Idle/PairingSelect)
```

`InputAvailable` (view-only mode, `docs/plans/pc-client/input-relay/PLAN.md` §8) is not encoded here — orthogonal flag layered only within `Live`/`Reconnecting`, same separation as the Android app.

## 4. Pairing-Entry Selection Flow on Launch

Per `docs/pc-client-detailed-spec.md` §6 — mirrors the Android child's existing behavior exactly, no PC-specific deviation:

- `MainWindow.Loaded` (`docs/plans/pc-client/app-shell/PLAN.md` §6 step 4) reads `PcPairingRepository.GetAll()` (`docs/plans/pc-client/pairing-pc-side/PLAN.md` §3):
  - Zero entries → show `ManualPairingView` (`pairing-pc-side` §5) directly.
  - Exactly one entry → auto-select it, transition straight to `WaitingForParent(entry)` — no selection list shown.
  - More than one entry → show `PairingListView` (`pairing-pc-side` §7) in "select to connect" mode (tapping a row starts a connection to that entry, distinct from its rename/delete affordances, which stay on their own icon buttons — mirrors the Android `PairingListFragment`'s dual role, `docs/plans/connection-status-and-resilience/PLAN.md` §9's "as-built" note).
- After a fresh pairing completes (`pairing-pc-side` §5's handshake success path), the newly-created entry is used directly for `WaitingForParent` rather than re-reading the list and re-applying the auto-select rule — avoids a redundant round-trip through the now-stale "how many entries existed before this one was added" logic.

## 5. WaitingForParentScreen

Per `docs/detailed-spec.md` §17, ported directly:

1. `ConnectionStateStore` transitions to `WaitingForParent(pairing)`; `Session/PcSessionListener.Start(pairing)` (`docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §4) begins listening.
2. `WaitingForParentView` shows an indeterminate "waiting for parent to start sharing" indicator (no timeout) plus a "Cancel" button.
3. On accept+handshake success (connectivity plan §4 step 2): transition directly to `Live(pairing)` — no intermediate `Reconnecting` step for this first-connection case, matching the Android state machine's lack of a `WaitingForParent → Reconnecting` edge.
4. "Cancel": `PcSessionListener.Stop()`, transition back to `Idle`/pairing-selection (§4).
5. Listener failure (e.g. `PcTailscaleAddressResolver` returns null — Tailscale not detected on this PC): shown as a dismissible inline error + "Retry" button, mirroring the Android `ChildSessionListener.Result.Failed` handling (`docs/plans/connection-status-and-resilience/PLAN.md` §9's "as-built" note) rather than left silent, since a total listen failure has no other path to recovery without a visible signal.

## 6. Live Screen (Status Panel)

Per `docs/detailed-spec.md` §9 / `docs/pc-client-detailed-spec.md` §8:

- `LiveView` composes: `video-and-session/decode`'s `VideoView` (main content), `input-relay`'s `TapMarkerOverlay` (layered above `VideoView`) and `TextInputBar` (below/beside it), and this plan's `ParentStatusPanel` (a small side/bottom panel).
- `ParentStatusPanel` renders the latest `StatusHeartbeat`/`StatusEvent` payload fields — Wi-Fi connected/disconnected, network type (Wi-Fi/Cellular), signal strength (nullable), SSID (nullable) — display-only, no logic beyond rendering (does not itself drive reconnection, that's §7). Exact panel placement (side vs. bottom, always-visible vs. collapsible) is left to implementation — `docs/pc-client-detailed-spec.md` §8 fixes only the content, not the layout.
- Not sent/expected during `WaitingForParent` — `HeartbeatWatchdog` only runs once `ConnectionState` is `Live`, same scope boundary as the Android `HeartbeatWatchdog`.

## 7. Reconnecting / Disconnected UI

Per `docs/detailed-spec.md` §14 / `docs/pc-client-detailed-spec.md` §8, ported directly:

- `HeartbeatWatchdog` starts on entering `Live`, tracks `LastFrameOrHeartbeatReceivedAt` (updated on **any** inbound frame, not just heartbeats — `VideoFrame` receipt counts too). A `5000ms` check: if `now - LastFrameOrHeartbeatReceivedAt >= 3 * HeartbeatIntervalMs` (`Common.Constants.MissedHeartbeatThreshold`), transition `Live → Reconnecting(pairing, since: now)`.
- `Reconnecting`: `ReconnectingBanner` shown as additive UI over the still-visible last video frame (video view is not cleared) — `video-and-session/connectivity`'s `PcSessionListener` re-arms `AcceptTcpClientAsync()` underneath, per that plan's §4 step 4.
- Reconnect succeeds (new `SESSION_HELLO` validated while `Reconnecting`) → back to `Live`, `LastFrameOrHeartbeatReceivedAt` reset.
- `now - Since >= ReconnectWindowMs` (60000ms) with no reconnect → `Reconnecting → Disconnected`. `DisconnectedView` replaces the video view, single action ("Back to pairing list") → `Idle`/pairing-selection (§4); a fresh attempt from there is a normal new `WaitingForParent` flow, not a "resume" path.
- **Parent screen-sleep is not a PC-specific scenario** (`docs/pc-client-detailed-spec.md` §8): frames simply stop, the same ~15s threshold drives `Reconnecting` — no distinct "parent may be asleep" indicator.
- **No dedicated recovery UI after a parent-side process-kill** (`docs/detailed-spec.md` §6 / `docs/DECISIONS.md` #13, extended by `docs/pc-client-detailed-spec.md` §8): `DisconnectedView` uses one generic message (mirroring the Android `DisconnectedScreen`'s wording pattern, `docs/plans/connection-status-and-resilience/PLAN.md` §6/§8) — "Connection to `<label>` was lost. Ask them to try again if this continues." No cause-specific branch, no "tap to resume" affordance; the PC client has no more visibility into the disconnect cause than the Android child does.

## 8. Window-Close Behavior

Per `docs/pc-client-detailed-spec.md` §8's last bullet / `docs/plans/pc-client/app-shell/PLAN.md` §3: closing `MainWindow` at any `ConnectionState` simply ends the session and the app — `MainWindow.Closing` calls a single `ConnectionStateStore.Teardown()` method (stops `PcSessionListener`, disposes the `Net.SessionChannel` if connected, disposes `MediaFoundationDecoder` if constructed) before the process exits. No "minimize to tray, keep session alive" branch exists anywhere in this plan.

## 9. Interactions With Other Features

- **pairing-pc-side**: reused screens (§4), read-only access to `PcPairingRepository`.
- **video-and-session/connectivity**: that plan's `PcSessionListener`/`Net.SessionChannel` is the dial-in counterpart to this plan's state machine — §4 step 2-3 here corresponds to `connectivity.PLAN.md` §4. Both plans must keep `SessionPort` and the `SESSION_HELLO` payload shape in sync via `docs/plans/pc-client/README.md` §5/§6, mirroring the equivalent cross-reference note in `docs/plans/connection-status-and-resilience/PLAN.md` §7.
- **video-and-session/decode, input-relay**: hosted inside `LiveView` (§6); this plan drives their construction/disposal lifecycle (tied to `Live`/`Reconnecting` vs. not) but does not own their internal logic.
- **zoom-magnifier**: also hosted inside `LiveView`, independent of this plan's state machine (zoom state is reset whenever `LiveView` is torn down/recreated on a fresh `Live` entry, per that plan's own scope).

## 10. Open Items

None. `docs/pc-client-detailed-spec.md` §6/§8 fix every product-level requirement by direct reference to already-decided Android behavior; remaining choices (status panel placement, exact wording strings) are implementation-level, resolved directly above or explicitly left to string-resource authoring (`docs/plans/pc-client/app-shell/PLAN.md` §5).
