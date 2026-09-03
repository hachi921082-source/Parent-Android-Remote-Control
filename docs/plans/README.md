# Implementation Plans

Each feature's implementation plan lives in its own directory: `docs/plans/<feature-name>/PLAN.md`.

## Index

1. Feature Map
2. Shared Foundations (app-wide)
3. Shared Data Models
4. Shared Message Protocol
5. Shared Session/Connection State Machine
6. Shared Geometry / Coordinate-Scaling Concepts
7. Shared Constants
8. Implementation Order & Progress

## 1. Feature Map

| Feature | Directory | detailed-spec.md sections covered |
|---|---|---|
| Pairing & Onboarding | `docs/plans/pairing-and-onboarding/` | §1 (Target Devices), §2 (Parent-Side Device Setup), §3 (Pairing Data Model), §13 (Initial Pairing & Permission Onboarding Flow) |
| Screen Sharing Core | `docs/plans/screen-sharing-core/` | §4 (Parent-Side Operation Flow / Tap Count), §5 (Screen Sharing Behavior), §6 (Background-Kill Mitigation), §7 (Connectivity/Tailscale), §8 (Screen-Sleep Prevention) |
| Remote Input Control | `docs/plans/remote-input-control/` | §10 (Tap-Position Visualization), §11 (Accessibility Service Permission Monitoring), §15 (Aspect Ratio Mismatch & Tap Coordinate Scaling), §16 (Text Input Mechanism) |
| Connection Status & Resilience | `docs/plans/connection-status-and-resilience/` | §9 (Parent Device Status Summary), §14 (Connection-Loss Detection Timeout & Reconnect UI), §17 (Waiting-for-Parent State) |
| Screen Rotation Handling | `docs/plans/screen-rotation-handling/` | §12 (Screen Rotation Handling) |

Not organized by build/dependency order — see each PLAN.md's own "Interactions with Other Features" notes for how they connect. An Implementation Order & Progress table will be added here once implementation actually begins (per dev-workflow.md §6), not at planning time.

## 2. Shared Foundations (app-wide)

- Single Gradle Android app module. Kotlin. Application ID: `com.familyremote.app` (placeholder, low-stakes — can be renamed freely before first release since it has no product-visible consequence).
- Min SDK 26 (Android 8.0), per spec.md §6 / detailed-spec §7.
- Role switching: a single `MainActivity` hosting two top-level tabs — Parent mode (default, spec.md §4/Decision #5) and Child mode — implemented as a simple two-destination bottom nav or top tab bar; no separate app variants/flavors.
- Third-party dependency: the official Tailscale Android app is a required, separately-installed prerequisite (detailed-spec §7) — this app does not embed a VPN/mesh client itself, it only talks over the IP connectivity Tailscale already provides once both devices are in the same tailnet. See `docs/plans/pairing-and-onboarding/PLAN.md` §8.2 for how the onboarding flow surfaces this prerequisite.
- Package layout:
  - `pairing/` — see `docs/plans/pairing-and-onboarding/PLAN.md`.
  - `parent/` — parent-mode screens, `ScreenShareForegroundService`, capture/encode, `ParentAccessibilityService`, parent-side network server/client, parent-side status monitor, keep-alive scheduling. See `docs/plans/screen-sharing-core/PLAN.md`, `docs/plans/remote-input-control/PLAN.md`.
  - `child/` — child-mode screens/viewer, decoder/render view, input capture, connection-state view model. See `docs/plans/remote-input-control/PLAN.md`, `docs/plans/connection-status-and-resilience/PLAN.md`.
  - `net/` — §4 message protocol shared by both roles.
  - `common/` — shared data classes, the session state machine, geometry helpers, app-wide constants (§3–§7 below).

## 3. Shared Data Models

Defined fully in `docs/plans/pairing-and-onboarding/PLAN.md` §2 (`PairingEntry`, `AuthorizedChild`) — all other features that need to look up "which parent to dial" or "which child is authorized" read/write through `PairingRepository` rather than redefining these types.

`FrameGeometry` (used by remote-input-control and screen-rotation-handling — full definition in §6 below).

`ConnectionState` (used by connection-status-and-resilience, remote-input-control, screen-sharing-core — full definition in §5 below).

## 4. Shared Message Protocol (`net` package)

All live-session traffic (video, control input, status, text) rides a **single bidirectional TCP connection per session**, established as described in `docs/plans/connection-status-and-resilience/PLAN.md` §17-flow and `docs/plans/screen-sharing-core/PLAN.md`. This is a deliberate simplification given the minimal-security, family-only posture (spec.md §7) and the fact Tailscale already provides transport-level encryption/authentication (WireGuard) between the two devices — the app layer only needs light framing and one shared-secret check, not its own TLS/session crypto.

**Framing**: `[4-byte big-endian length][1-byte MessageType][payload bytes]`. `length` covers `MessageType` + `payload`.

**MessageType** (single enum, shared by both roles — not two separate enums, so a message's direction is documented per-value below rather than by which enum it belongs to):

| Value | Direction | Payload | Used by |
|---|---|---|---|
| `SESSION_HELLO` | child→parent (session start) or parent→child (session start) — see connection-status-and-resilience PLAN.md for which side sends first | `{childId or parentAck, sharedSecret}` | screen-sharing-core, connection-status-and-resilience |
| `VIDEO_FRAME` | parent→child | H.264 Annex-B NAL unit(s) + `{frameWidth, frameHeight, presentationTimeUs}` header | screen-sharing-core, screen-rotation-handling |
| `STATUS_HEARTBEAT` | parent→child, every 5s | `{wifiConnected, networkType, signalStrength?, ssid?, timestampMs}` | connection-status-and-resilience |
| `STATUS_EVENT` | parent→child, on change | same shape as `STATUS_HEARTBEAT`, sent immediately instead of waiting for the next tick | connection-status-and-resilience |
| `ACCESSIBILITY_STATUS` | parent→child | `{granted: Boolean}` | remote-input-control |
| `INPUT_TAP` | child→parent | `{xNormalized, yNormalized}` (already scaled to parent frame resolution per §15) | remote-input-control |
| `INPUT_SWIPE` | child→parent | `{pathPoints: [{x,y}], durationMs}` | remote-input-control |
| `INPUT_TEXT` | child→parent | `{text: String}` | remote-input-control |
| `TEXT_INJECT_FAILED` | parent→child | `{reason: "NO_FOCUSED_FIELD"}` | remote-input-control |
| `SESSION_END` | either direction | `{reason: String}` | connection-status-and-resilience, screen-sharing-core |

Pairing-time-only messages (`PAIRING_HELLO`, `PAIRING_ACK`) are a separate, short-lived exchange defined in `docs/plans/pairing-and-onboarding/PLAN.md` §4 — not part of the live-session protocol above, and not sent on the same connection (pairing happens before any `PairingEntry`/`AuthorizedChild` exists to establish a session with).

## 5. Shared Session/Connection State Machine (child side)

Owned by a single `ConnectionStateViewModel` (child device), consumed by remote-input-control (to enable/disable input UI) and screen-sharing-core (to know whether a live video surface should be shown). Full state definitions and transition triggers are specified in `docs/plans/connection-status-and-resilience/PLAN.md` §3 — summarized here for other plans' reference:

```
Idle/PairingSelect → WaitingForParent (§17, untimed) → Live → Reconnecting (§14, ≤60s) → Disconnected → (back to) Idle/PairingSelect
```

Orthogonal flag on top of `Live`: `inputAvailable: Boolean` (view-only mode, detailed-spec §11) — independent of the connection state above, driven by `ACCESSIBILITY_STATUS` messages, defined in `docs/plans/remote-input-control/PLAN.md`.

## 6. Shared Geometry / Coordinate-Scaling Concepts

`FrameGeometry` (in `common`):

```
data class FrameGeometry(
    val parentFrameWidth: Int,
    val parentFrameHeight: Int
)
```

`RenderedContentRect` — computed by the child's video view whenever either the view's own size changes or a new `FrameGeometry` arrives (new `VIDEO_FRAME` header with different dimensions, e.g. after a parent-side rotation, detailed-spec §12): the letterboxed/pillarboxed rectangle (in view-local pixel coordinates) that actually shows video content, per detailed-spec §15. Full letterbox math and the tap-coordinate transform (view px → content-rect-relative fraction → parent frame px, discarding taps outside `RenderedContentRect`) are specified in `docs/plans/remote-input-control/PLAN.md` §Coordinate Scaling; the recalculation triggers (child view resize, parent frame dimension change) are specified in both that plan and `docs/plans/screen-rotation-handling/PLAN.md`, which must stay consistent with each other.

## 7. Shared Constants

| Name | Value | Source |
|---|---|---|
| `HEARTBEAT_INTERVAL_MS` | 5000 | detailed-spec §9 |
| `MISSED_HEARTBEAT_THRESHOLD` | 3 (≈15s) | detailed-spec §14 |
| `RECONNECT_WINDOW_MS` | 60000 | detailed-spec §14 |
| `ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS` | 30000 | detailed-spec §11 |
| `TAP_MARKER_FADE_MS` | 300 | detailed-spec §10 |
| `SESSION_PORT` | 47321 (parent-side listen port during pairing-dial-back is N/A; this is the live-session port the parent connects out on — see screen-sharing-core PLAN.md) | implementation choice |
| `PAIRING_PORT` | 47322 (temporary, pairing-time only) | implementation choice, see pairing-and-onboarding PLAN.md §4 |
| Tailscale CGNAT range | `100.64.0.0/10` | implementation choice, used by `TailscaleAddressResolver` (pairing-and-onboarding PLAN.md §3) |

All interval/threshold values above are carried through directly from detailed-spec.md — not re-derived. Port numbers and the CGNAT-range detection technique are implementation choices with no product-facing consequence.

## 8. Implementation Order & Progress

Dependency-tier table, derived from a class/binding-level reading of all five PLAN.md files (not from the feature groupings in §1, which reflect spec-section boundaries, not build order). "Depends on" means the feature's own plan explicitly calls into, binds to, or edits a class owned by the other feature — not just a shared wire-protocol/type reference already fixed in §3–§7 above.

| Tier | Feature | Depends on | Status |
|---|---|---|---|
| 0 | Pairing & Onboarding | — | Merged |
| 1 | Screen Sharing Core | Pairing & Onboarding (`PairingRepository`/`AuthorizedChild`, §2.2) | Merged |
| 2 | Connection Status & Resilience | Pairing & Onboarding (`PairingEntry`/`TailscaleAddressResolver`/`PairingListScreen`); Screen Sharing Core (`ParentStatusMonitor` hands off to `ParentSessionServer`, and is "started alongside" `ScreenShareForegroundService` — needs that class merged to wire into) | Merged |
| 2 | Remote Input Control | Screen Sharing Core (`ParentAccessibilityService` binds to `ScreenShareForegroundService` via a companion reference — needs that class merged); Connection Status & Resilience is a **type-level** dependency only (`ConnectionState` sealed class, already fixed in §5 above) — `TapInputController`/`TextInputBar` read it but don't need that feature's actual runtime logic (`HeartbeatWatchdog` etc.) merged first, so this can proceed in the same tier as Connection Status & Resilience, not after it | Merged |
| 3 | Screen Rotation Handling | Screen Sharing Core (§2 is implemented as an addition to `ScreenShareForegroundService` itself — same-file edit, not just a call-out); Remote Input Control (`VideoDecoderView`'s dimension-change handling must already exist) | Not started |

**Notes:**
- Tier 0 and Tier 1 each have only one feature — no parallelism is available until Tier 2, which has two (Connection Status & Resilience, Remote Input Control). They touch different files (no same-file conflict identified), so both can be worked in separate worktrees simultaneously once Tier 1 is merged.
- **Forward-reference stub**: Pairing & Onboarding (Tier 0) references `AccessibilityPermissionMonitor`, a class owned by Remote Input Control (Tier 2) — its onboarding "Accessibility Service walkthrough" screen needs a granted/not-granted check before that shared class exists yet. Implement this with a local inline permission check (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, the same one-liner `AccessibilityPermissionMonitor` itself wraps) in Tier 0, then swap it for the real shared `AccessibilityPermissionMonitor` once Tier 2 lands — do not block Tier 0 on Tier 2, and do not invent a different/parallel permission-check design that could drift from the real one.
- This table is updated at each feature's merge (its own row's Status), not per-session. A new tier's worktrees are only cut once every row in all earlier tiers reads "Merged."
