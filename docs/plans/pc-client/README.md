# PC Viewer Client — Implementation Plans (Shared Foundations)

<!-- Companion index to docs/plans/README.md, scoped to the new Windows PC viewer/controller
client. Plans here are organized by feature name/area (not build order) per explicit user
instruction — see docs/pc-client-spec.md / docs/pc-client-detailed-spec.md for the confirmed
specification these plans implement. This directory does NOT participate in
docs/plans/README.md's dependency-tier table (§8) or feature list (§1) — kept intentionally
separate, per explicit user instruction. -->

## Index

1. Feature Map
2. Directory Split: Which Codebase Each Plan Touches
3. Solution & Project Structure (C#/.NET/WPF)
4. Shared Data Model (PC Side)
5. Shared Message Protocol (Reused + New)
6. Shared Constants (PC Side)
7. Cross-References
8. Build Order (Implementation Tiers)

## 1. Feature Map

| Feature | Directory | pc-client-detailed-spec.md sections covered |
|---|---|---|
| App Shell / Bootstrap | `docs/plans/pc-client/app-shell/` | §1 (Environment/Prerequisites), §2 (single-instance enforcement, ordinary-window model), §9 (localization infrastructure) |
| Pairing (PC side) | `docs/plans/pc-client/pairing-pc-side/` | §3 (PC-side half — two-field screen, normalization, handshake as initiator), §4 (PC-side pairing data model) |
| Video & Session — Decode | `docs/plans/pc-client/video-and-session/decode.PLAN.md` | §5 (Video Transport & Decode) |
| Video & Session — Connectivity | `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` | §6 (Session Establishment & Connectivity) |
| Input Relay | `docs/plans/pc-client/input-relay/` | §7 (Input Relay: Tap/Swipe/Text) |
| Connection-State UI | `docs/plans/pc-client/connection-state-ui/` | §6 (pairing-selection-on-launch, exclusive-session note), §8 (Connection-State-Driven UI) |
| Zoom/Magnifier | `docs/plans/pc-client/zoom-magnifier/` | §10 (Zoom/Magnifier Display) |
| Physical Key Relay (PC side) | `docs/plans/pc-client/physical-key-relay-pc-side/` | §11 (PC-side half — capture, `INPUT_KEY` send) |

Not organized by build/dependency order — see each `PLAN.md`'s own "Interactions With Other Features" section for how they connect. App Shell / Bootstrap underpins everything else (project scaffold, localization infra, window model) and should be implemented first as a practical matter, but this is a build-order note within this README, not a tracked tier table.

## 2. Directory Split: Which Codebase Each Plan Touches

This feature also requires two small, explicitly-accepted changes to the **existing, already-merged Android app** (`docs/DECISIONS.md` #19): a manual-pairing-code addition to `ParentPairingActivity`, and an `INPUT_KEY` handler addition to `ParentAccessibilityService`. Per explicit user instruction, these two Android-side plans live in the **flat, existing** `docs/plans/<feature-name>/PLAN.md` layout — siblings of the five already-merged tiers — **not** under this `docs/plans/pc-client/` subfolder, even though they exist because of this PC-client feature:

- `docs/plans/pc-pairing-code-support/PLAN.md` — `ParentPairingActivity` manual-code addition + `PairingServer` multi-attempt/lockout redesign.
- `docs/plans/pc-key-relay-support/PLAN.md` — `ParentAccessibilityService` `INPUT_KEY` handler addition.

Both carry a header note cross-referencing `docs/pc-client-spec.md`/`docs/pc-client-detailed-spec.md` and stating they are intentionally excluded from `docs/plans/README.md`'s dependency-tier table (§8), the same way this directory is. **Rule of thumb**: if a plan's code changes land in `pc-client/` (C#), it lives under `docs/plans/pc-client/`; if its code changes land in `app/` (Kotlin), it lives in the flat `docs/plans/<feature-name>/` layout — regardless of which feature/spec section motivated the change.

## 3. Solution & Project Structure (C#/.NET/WPF)

- Repository placement: new top-level folder `pc-client/` (sibling to `app/`), per `docs/pc-client-spec.md` §5.
- Solution: `pc-client/FamilyRemote.PcClient.sln`.
- Project: `pc-client/FamilyRemote.PcClient/FamilyRemote.PcClient.csproj` — WPF app, `<TargetFramework>net8.0-windows</TargetFramework>`, `<UseWPF>true</UseWPF>`, `<Nullable>enable</Nullable>`, `<ImplicitUsings>enable</ImplicitUsings>` (ordinary modern-C# project defaults — implementation-quality choices with no product-facing consequence).
- Publish (single portable exe, `docs/pc-client-detailed-spec.md` §1): `dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true`. Self-contained (not framework-dependent) is chosen specifically so the operator never needs to separately install a .NET runtime — keeps the "just run the `.exe`" requirement (`docs/pc-client-spec.md` §5, no installer) genuinely true regardless of what's already on the target PC.
- Root namespace: `FamilyRemote.PcClient`.
- Folder/namespace layout (mirrors the Android app's package-layout table, `docs/plans/README.md` §2):

  | Folder | Owning plan(s) | Mirrors (Android) |
  |---|---|---|
  | `App/` | app-shell | app-wide bootstrap |
  | `Pairing/` | pairing-pc-side | `pairing/` |
  | `Session/` | connection-state-ui | `child/` connection state, `pairing/ChildPairingScanActivity`-equivalent listener role |
  | `Video/` | video-and-session/decode | `child/VideoDecoderView` |
  | `Input/` | input-relay, physical-key-relay-pc-side | `child/TapInputController`, `child/TextInputBar` |
  | `Zoom/` | zoom-magnifier | (no Android equivalent — PC-only) |
  | `Net/` | first introduced by video-and-session/connectivity, extended by physical-key-relay-pc-side | `net/` |
  | `Common/` | shared across all plans (constants, `FrameGeometry`, `RenderedContentRect`) | `common/` |
  | `Resources/` | app-shell (infra), string keys added by whichever plan introduces them | `res/values*/strings.xml` |

## 4. Shared Data Model (PC Side)

`PcPairingEntry` — defined fully in `docs/plans/pc-client/pairing-pc-side/PLAN.md` §2; mirrors the Android child's `PairingEntry` (`docs/plans/pairing-and-onboarding/PLAN.md` §2.1), independent list, per `docs/pc-client-detailed-spec.md` §4.

`FrameGeometry` / `RenderedContentRect` (PC-side equivalents) — defined in `docs/plans/pc-client/video-and-session/decode.PLAN.md` §5, ported field-for-field from `docs/plans/README.md` §6.

`ConnectionState` (PC-side equivalent) — defined in `docs/plans/pc-client/connection-state-ui/PLAN.md` §3, ported case-for-case from `docs/plans/README.md` §5.

## 5. Shared Message Protocol (Reused + New)

The PC client implements the **same** `Net/MessageType` tag values as the Android app's `com.familyremote.app.net.MessageType` enum (`docs/plans/README.md` §4), byte-for-byte, so it is indistinguishable from an Android child at the wire level (`docs/pc-client-spec.md` §4). No existing tag is renumbered.

One new tag is added by this feature, on the Android side first (`docs/plans/pc-pairing-code-support/PLAN.md`... — no, added by `docs/plans/pc-key-relay-support/PLAN.md`) and mirrored here: `INPUT_KEY = 11` (child/PC→parent), inserted immediately after `SESSION_END = 10` and before the pairing-time-only `PAIRING_HELLO = 100`/`PAIRING_ACK = 101` values, matching the Kotlin enum's own ordering convention. See `docs/plans/README.md` §4 (Message Protocol table, updated by this feature) and `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md` for the payload shape and send-side logic.

Framing is identical: `[4-byte big-endian length][1-byte MessageType][payload bytes]`, ported to C# in `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §3 (`Net/MessageFrame.cs`).

## 6. Shared Constants (PC Side)

Reuses the exact numeric values already fixed in `docs/plans/README.md` §7 (Android) and `docs/plans/pc-pairing-code-support/PLAN.md` (manual pairing code) — not re-derived:

| Name | Value | Source |
|---|---|---|
| `HeartbeatIntervalMs` | 5000 | `docs/plans/README.md` §7 |
| `MissedHeartbeatThreshold` | 3 | `docs/plans/README.md` §7 |
| `ReconnectWindowMs` | 60000 | `docs/plans/README.md` §7 |
| `TapMarkerFadeMs` | 300 | `docs/plans/README.md` §7 |
| `SessionPort` | 47321 | `docs/plans/README.md` §7 |
| `PairingPort` | 47322 | `docs/plans/README.md` §7 |
| `PairingCodeValidityMs` | 300000 (5 min) | `docs/plans/README.md` §7 / `docs/plans/pc-pairing-code-support/PLAN.md` |
| `ManualCodeLength` | 8 | `docs/pc-client-detailed-spec.md` §3 |
| `CrockfordAlphabet` | `"0123456789ABCDEFGHJKMNPQRSTVWXYZ"` (32 symbols, excludes I/L/O/U) | `docs/pc-client-detailed-spec.md` §3 |

Defined in `Common/Constants.cs`, one `static class`, mirroring the Android app's single `common.Constants` object.

## 7. Cross-References

- `docs/pc-client-spec.md`, `docs/pc-client-detailed-spec.md` — confirmed/detailed specification (authoritative source for all plans in this directory).
- `docs/DECISIONS.md` #15–#24 — PC-client-specific decisions and rationale.
- `docs/plans/README.md` — Android app's shared foundations (wire protocol, data models, constants) that this feature reuses/extends.
- `docs/plans/pairing-and-onboarding/PLAN.md`, `docs/plans/screen-sharing-core/PLAN.md`, `docs/plans/connection-status-and-resilience/PLAN.md`, `docs/plans/remote-input-control/PLAN.md`, `docs/plans/screen-rotation-handling/PLAN.md` — the five already-merged Android tiers this feature's plans repeatedly cross-reference for behavior parity.

## 8. Build Order (Implementation Tiers)

<!-- Derived from each PLAN.md's own §"Interactions With Other Features" (dependency direction stated there, not re-derived). Kept in this README rather than docs/plans/README.md's §8 dependency-tier table, consistent with this directory's exclusion from that table (see the top-of-file note and §2). Unlike the main app, this is a layered/bottom-up new codebase (App Shell → Pairing → Connectivity → State → Decode → UI features → peripheral input), so parallelization opportunity is limited to one tier. -->

| Tier | Plan(s) | Depends on (prior tier) | Parallel? |
|---|---|---|---|
| 1 | `app-shell` | — | Solo |
| 2 | `pairing-pc-side` | app-shell (`MainWindow` host, `Resources.Strings`, `Common.Constants`) | Solo |
| 3 | `video-and-session/connectivity` | pairing-pc-side (`PcTailscaleAddressResolver`, `PcPairingEntry.ParentHost`/`SharedSecret`) | Solo |
| 4 | `connection-state-ui` | video-and-session/connectivity (`PcSessionListener`/`Net.SessionChannel` as dial-in counterpart to the `ConnectionState` machine) | Solo |
| 5 | `video-and-session/decode` | video-and-session/connectivity (deframed `VIDEO_FRAME` payloads), connection-state-ui (`MediaFoundationDecoder` lifecycle tied to `ConnectionState`) | Solo |
| 6 | `zoom-magnifier` **and** `input-relay` | video-and-session/decode (both subscribe independently to the shared `RenderedContentRect`/`FrameGeometry` observable decode exposes — decode.PLAN.md §7 — so neither needs the other's code, only decode's) | **Parallel — 2 worktrees** |
| 7 | `physical-key-relay-pc-side` | input-relay (shares `Input/InputAvailabilityState`, owned by input-relay), video-and-session/connectivity (adds `MessageType.InputKey`/`Net/InputKeyPayload.cs` to its `Net` package, already anticipated in connectivity's own component table) | Solo (see note below — 3-way parallel with Tier 6 possible) |

Rationale for the single parallel opportunity as PLAN.md files stand today: this is new, bottom-up-layered code (unlike the original Android app's five tiers, which mostly extended an already-working base along independent seams). Every other plan pair has a stated one-directional dependency in its own "Interactions With Other Features" section; only zoom-magnifier and input-relay are explicitly designed (via decode's shared observable) to have no code-level dependency on each other.

**Open note — Tier 6/7 merge possible, decide at Tier 6 time**: `physical-key-relay-pc-side`'s only dependency on `input-relay` is the small `Input/InputAvailabilityState` class (not `PointerInputController`/`TapMarkerOverlay`/`TextInputBar`, which it never touches). If `InputAvailabilityState` is hoisted out of `input-relay`'s ownership into an earlier shared location (e.g. `connection-state-ui`, which already gates both plans' activation on `ConnectionState.Live`/`Reconnecting`), `physical-key-relay-pc-side` would no longer need to wait for `input-relay` and could join Tier 6 as a third parallel worktree, eliminating Tier 7 entirely. This requires a small edit to `input-relay/PLAN.md`'s and `physical-key-relay-pc-side/PLAN.md`'s component tables (and wherever `InputAvailabilityState` newly lives) before it's cut as worktrees — not done yet. **Ask the user about this when Tier 6 is actually about to be implemented**, rather than deciding it now. Tiers 1–5 do not have an equivalent opening — each depends on the prior tier's actual running code (project scaffold / socket implementation / decode pipeline), not just a small shared type, so re-splitting their headings would not unlock further parallelism there.

Practical use: cut one worktree per solo tier, or two (potentially three, per the note above) worktrees for Tier 6, following the same `developer-lite` → `reviewer-lite` → user go-ahead → merge flow used for the Android-side plans (see [HANDOFF.md](../../HANDOFF.md) §1). `reviewer-lite`'s build-verification step needs a `dotnet build`-equivalent, not `./gradlew`, once `pc-client/FamilyRemote.PcClient.sln` exists (Tier 1).
