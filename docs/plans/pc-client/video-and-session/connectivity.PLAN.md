# PLAN: PC Client — Video & Session: Connectivity

<!-- Implementation plan. Covers pc-client-detailed-spec.md §6 (Session Establishment &
Connectivity). Part of the PC Viewer Client feature. Sibling plan
docs/plans/pc-client/video-and-session/decode.PLAN.md covers H.264 decode (§5). -->

## Index

1. Scope
2. Components
3. Net Package: Wire Protocol Port to C#
4. Session Establishment (PC Listens, Parent Dials)
5. Exclusive-Single-Session Behavior (Client-Side Note Only)
6. Interactions With Other Features
7. Open Items

## 1. Scope

The PC client's half of the live-session TCP connection: framing, the `SESSION_HELLO` handshake, and the listen-for-parent lifecycle. Does not cover decoding the video payload once received (that's `decode.PLAN.md`) or the connection-state UI/heartbeat-timeout logic layered on top (that's `docs/plans/pc-client/connection-state-ui/PLAN.md`) — this plan owns only the socket and the message framing/dispatch underneath both.

## 2. Components

| Component | Responsibility |
|---|---|
| `Net/MessageType` | Enum mirroring the Android `com.familyremote.app.net.MessageType` tag values exactly (`docs/plans/pc-client/README.md` §5). |
| `Net/MessageFrame` | `[4-byte big-endian length][1-byte MessageType][payload]` framing — read/write helpers over a `NetworkStream`, ported from the Android `MessageFrame.kt`. |
| `Net/*Payload` records | One record type per message payload actually consumed/produced by this client (`SessionHelloPayload`, `VideoFramePayload`, `StatusPayload`, `AccessibilityStatusPayload`, `InputTapPayload`, `InputSwipePayload`, `InputTextPayload`, `TextInjectFailedPayload`, `SessionEndPayload`; `InputKeyPayload` added by `docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md`) — JSON (de)serialization via `System.Text.Json`, one file per feature's payload group, mirroring the Android app's `InputMessages.kt`/`StatusPayload.kt`/`SessionHandshakeMessages.kt` file split. |
| `Session/PcSessionListener` | Owns the listening `TcpListener` on `SessionPort` during `WaitingForParent`; accepts the parent's incoming `SESSION_HELLO`, validates `SharedSecret`, transitions to `Live` on success. |

## 3. Net Package: Wire Protocol Port to C#

Per `docs/plans/pc-client/README.md` §5: byte-for-byte identical framing and `MessageType` tag values to the Android app (`docs/plans/README.md` §4). `Net/MessageType.cs`:

```csharp
public enum MessageType : byte
{
    SessionHello = 1,
    VideoFrame = 2,
    StatusHeartbeat = 3,
    StatusEvent = 4,
    AccessibilityStatus = 5,
    InputTap = 6,
    InputSwipe = 7,
    InputText = 8,
    TextInjectFailed = 9,
    SessionEnd = 10,
    InputKey = 11,     // added by docs/plans/pc-key-relay-support/PLAN.md (Android) + docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md (this client)
    PairingHello = 100,
    PairingAck = 101
}
```

`Net/MessageFrame.cs` — `static void WriteTo(Stream, MessageType, byte[] payload)` and `static MessageFrame ReadFrom(Stream)`, same 4-byte-length + 1-byte-type layout, same `16 MiB` sanity ceiling on read as the Android implementation (`app/src/main/java/com/familyremote/app/net/MessageFrame.kt`).

Payload records use `System.Text.Json`'s source-generated serializers (`[JsonSerializable]` context) for AOT/trimming-friendliness under the self-contained single-file publish (`docs/plans/pc-client/app-shell/PLAN.md` §2) — an implementation-quality choice, not a product requirement.

## 4. Session Establishment (PC Listens, Parent Dials)

Per `docs/detailed-spec.md` §17 / `docs/DECISIONS.md` #12, reused unchanged (`docs/pc-client-detailed-spec.md` §6): the **parent** dials the **PC client**, never the reverse — identical direction to the Android child.

1. Once a `PcPairingEntry` is selected (`docs/plans/pc-client/connection-state-ui/PLAN.md` §4's selection flow), `PcSessionListener.Start(entry)`:
   - Resolves this PC's own Tailscale address via `Pairing.PcTailscaleAddressResolver` (`docs/plans/pc-client/pairing-pc-side/PLAN.md` §8).
   - Opens `new TcpListener(resolvedAddress, Constants.SessionPort)`, calls `Start()`, then `AcceptTcpClientAsync()` on a background `Task` — blocks indefinitely (no timeout), mirroring the Android `ChildSessionListener`'s untimed `accept()` (`docs/plans/connection-status-and-resilience/PLAN.md` §4 step 2-3).
   - This listener is opened fresh each time the PC client enters `WaitingForParent` — not an always-on background listener, same rationale as the Android side (`docs/plans/connection-status-and-resilience/PLAN.md` §4's note: scoped to this explicit, foreground, user-initiated waiting state).
2. On accept: read the `SESSION_HELLO` frame, compare its `SharedSecret` to `entry.SharedSecret`. Mismatch → close the socket, remain in `WaitingForParent`, keep `AcceptTcpClientAsync()`-ing (no lockout/rate-limiting here either — same low-security-posture rationale as the Android child's `ChildSessionListener`, `docs/plans/connection-status-and-resilience/PLAN.md` §4 step 4). Match → hand the now-established `NetworkStream` to a `Net.SessionChannel` wrapper (owns the read-loop `Task` that dispatches inbound frames by `MessageType` to whichever plan's handler is registered — decode.PLAN.md for `VideoFrame`, connection-state-ui for `StatusHeartbeat`/`StatusEvent`/`SessionEnd`, input-relay for `AccessibilityStatus`/`TextInjectFailed`), and signal `Live` to `docs/plans/pc-client/connection-state-ui/PLAN.md`.
3. Cancellation: `WaitingForParent`'s "Cancel" UI action calls `PcSessionListener.Stop()` (`TcpListener.Stop()`, unblocking the pending `AcceptTcpClientAsync()` with an `ObjectDisposedException`/`SocketException` that is caught and treated as a clean cancellation, not an error) — mirrors `docs/plans/connection-status-and-resilience/PLAN.md` §4 step 5.
4. Reconnect (within an already-`Live` session that drops): `PcSessionListener`'s underlying `TcpListener` is kept open across the whole `Live`/`Reconnecting` lifetime (never torn down on a mere missed-heartbeat blip), re-arming a fresh `AcceptTcpClientAsync()` for the `Reconnecting` state — identical design to the Android `ChildSessionListener`'s "as-built" reconnect approach (`docs/plans/connection-status-and-resilience/PLAN.md` §9). The 60s give-up deadline and missed-heartbeat detection themselves are owned by `docs/plans/pc-client/connection-state-ui/PLAN.md`'s `HeartbeatWatchdog` port, not this plan.

## 5. Exclusive-Single-Session Behavior (Client-Side Note Only)

Per `docs/pc-client-detailed-spec.md` §6 / `docs/DECISIONS.md` #24: at most one client may hold a `Live` session with a given parent at a time — but **no server-side enforcement of this rule exists yet anywhere in the codebase** (tracked separately as a follow-up to the already-merged `docs/plans/screen-sharing-core/PLAN.md`/`docs/plans/connection-status-and-resilience/PLAN.md` in GitHub issue #9, not implemented by any plan in this session). Consequently, this plan implements the session-establishment handshake exactly as described in §4 above, with **no special "rejected/busy" handling**, because no such wire message or parent-side behavior exists to handle yet — this is not a gap specific to the PC client; the Android child's own `docs/plans/connection-status-and-resilience/PLAN.md` is in the identical position today. When issue #9 is eventually implemented (presumably adding a new rejection/busy signal to the wire protocol and to both the Android child's and this plan's session-establishment logic), this plan's §4 will need a corresponding update at that time — out of scope here.

## 6. Interactions With Other Features

- **decode.PLAN.md**: receives already-deframed `VIDEO_FRAME` payloads from this plan's `Net.SessionChannel` dispatch.
- **connection-state-ui**: owns the `ConnectionState` machine this plan's accept/reconnect/cancel events feed into; this plan does not itself define `Idle`/`Live`/`Reconnecting`/`Disconnected` — it only raises the underlying socket-level events those states are built from.
- **input-relay / physical-key-relay-pc-side**: send `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT`/`INPUT_KEY` frames out over this plan's `Net.SessionChannel`; receive `AccessibilityStatus`/`TextInjectFailed` frames back through the same dispatch.
- **pairing-pc-side**: this plan reads `PcPairingEntry.ParentHost`/`SharedSecret` (that plan's data model) but never writes to it.

## 7. Open Items

None new. §5's exclusive-single-session gap is a pre-existing, already-tracked (issue #9) limitation shared with the already-merged Android app, not a new open question introduced by this plan — `docs/pc-client-detailed-spec.md` §6 explicitly scopes this plan's responsibility to "how the PC client itself behaves when its own connection attempt is accepted," which §4 above fully specifies.
