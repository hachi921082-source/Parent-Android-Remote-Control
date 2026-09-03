# PLAN: Single-Session Enforcement

<!-- Implementation plan. Covers detailed-spec.md §17 ("Exclusive single-session model" bullet) and
spec.md §5's last bullet. Follow-up to two already-merged plans: modifies
docs/plans/screen-sharing-core/PLAN.md §6 (parent-side dial-out) and adds new behavior to
docs/plans/connection-status-and-resilience/PLAN.md (child-side state machine/UX). Tracked as
GitHub issue #9. See docs/DECISIONS.md #24 and docs/QA.md ("Exclusive Single-Session Model") for
the confirmed-spec background this plan implements. -->

## Index

1. Scope
2. Background: Why "Dial All Children" Creates the Gap
3. Common Groundwork (Required Regardless of the Open Question in §4)
4. OPEN QUESTION — Reject vs. Queue vs. Silent-Drop (not decided by this plan)
5. Wire Protocol: `SESSION_BUSY` (only needed for the "explicit notice" branches of §4)
6. Child-Side State & UX (`connection-status-and-resilience`)
7. Files Touched — Summary
8. Interactions With Other Features (PC Client, remote-input-control)
9. Out of Scope

## 1. Scope

Makes the already-merged Tier 1/Tier 2 code enforce "at most one client may hold an active `Live`
session with a given parent device at a time" (`docs/DECISIONS.md` #24). Today,
`ScreenShareForegroundService.dialAuthorizedChildren()` dials **every** `AuthorizedChild` record
concurrently at share-start, and every one of them that happens to be reachable and waiting becomes
an independent, fully-equal `Live` peer — this plan closes that gap. It does **not** decide the
exact rejection mechanism for a losing client (§4) — that is a genuine open product question,
called out explicitly rather than silently resolved, per this session's instructions.

## 2. Background: Why "Dial All Children" Creates the Gap

`ScreenShareForegroundService.kt` (parent, already merged):

```kotlin
private fun dialAuthorizedChildren() {
    val children = PairingRepository(applicationContext).getAuthorizedChildren()
    expectedChildCount = children.size
    endedChildCount = 0
    if (children.isEmpty()) return
    children.forEach { child -> serviceScope.launch { runSessionLoop(child) } }
}
```

`runSessionLoop(child)` dials `child` with backoff, and on `ParentSessionServer.connect()`
succeeding, unconditionally does:

```kotlin
sessionServersByChild[child.childId] = server
return // success -- the server's own read loop now owns this connection's lifetime.
```

`sessionServersByChild: MutableMap<UUID, ParentSessionServer>` therefore has no size cap — every
reachable, currently-`WaitingForParent` paired client (Android child or, once implemented, PC
client — both are stored as the same `AuthorizedChild` record shape, see
`app/src/main/java/com/familyremote/app/pairing/AuthorizedChild.kt`) independently reaches `Live`
with full, equal tap/swipe/text control. `broadcastVideoFrame()`/`broadcastFrame()` then fan every
frame out to all of them. This was an accepted, explicitly-flagged gap at the time
(`docs/plans/screen-sharing-core/PLAN.md` §6: "out of scope to design a 'which child is actively
waiting' negotiation... since spec.md only anticipates a single child device in practice") — no
longer acceptable now that a parent being paired to two clients at once (Android + PC) is a
realistic everyday case (`docs/DECISIONS.md` #24).

There is no path today for a child to *initiate* a connection to the parent — the parent always
dials out (`docs/DECISIONS.md` #12, reused as-is by the PC client per
`docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §4). So "a second client attempts to
connect while a session is already active" concretely means: **a second (or third...) coroutine
inside `dialAuthorizedChildren()`'s `forEach` completes its own `ParentSessionServer.connect()`
while another child's coroutine has already done so and is Live.** Enforcement is therefore a
parent-side arbitration problem among concurrently-racing dial attempts, not a listener-side
accept/reject problem on the child (both sides already do secret validation and neither is being
made "more secure" here — this is purely about which single winner gets to be the video/control
peer).

## 3. Common Groundwork (Required Regardless of the Open Question in §4)

This section's design is unaffected by which option in §4 is eventually chosen — it must be built
either way.

### 3.1 `activeChildId`: single-slot claim, race-safe

`ScreenShareForegroundService` gains:

```kotlin
private val arbitrationLock = Any()
private var activeChildId: UUID? = null // the single child/PC client currently holding the Live slot
```

New private method, called only from inside `runSessionLoop` right after a `connect()` call
succeeds (i.e. the TCP connect + `SESSION_HELLO` send completed — see §2's note that there is no
child→parent handshake ack today, so "connect() returned true" is the earliest point at which an
arbitration decision can be made):

```kotlin
/** Returns true iff [child] just won the single Live slot. Synchronous, non-suspending critical
 * section only -- safe to call from any of the concurrently-racing per-child coroutines
 * (serviceScope runs on Dispatchers.Default, so this genuinely races across threads, not just
 * across suspension points). */
private fun tryClaimSession(child: AuthorizedChild): Boolean = synchronized(arbitrationLock) {
    if (activeChildId == null) {
        activeChildId = child.childId
        true
    } else {
        false
    }
}
```

`runSessionLoop` is restructured so the post-`connect()` step is no longer an unconditional win:

```kotlin
val connected = server.connect(serviceScope)
if (connected) {
    if (tryClaimSession(child)) {
        sessionServersByChild[child.childId] = server
        return // -- unchanged from today: the server's own read loop now owns this connection.
    } else {
        // Lost the arbitration race -- see §4 for what happens here (open question).
        handleLosingCandidate(child, server, windowStartMs)
        return@let-or-continue // exact control flow depends on §4's outcome, see there.
    }
}
```

`handleLosingCandidate(...)`'s body is the one piece of this plan that forks on §4 — see that
section for the two concrete bodies (`LostRetry` vs. `LostTerminal`). Everything else in this §3
holds for both.

### 3.2 Freeing the slot when the active session ends

`onChildSessionEnded` must clear `activeChildId` only when the child that just ended **was** the
claimant — a losing candidate's own connection ending (its throwaway socket from `handleLosingCandidate`
closing) must never touch `activeChildId`:

```kotlin
private fun onChildSessionEnded(child: AuthorizedChild, reason: String) {
    val wasActive = (activeChildId == child.childId)
    sessionServersByChild.remove(child.childId)?.close()
    if (wasActive) {
        synchronized(arbitrationLock) { activeChildId = null }
    }
    if (reason == "connection_lost") {
        serviceScope.launch { runSessionLoop(child) }
    } else {
        onChildPermanentlyDone(child, wasActive)
    }
}
```

### 3.3 Correcting the whole-session stop condition

Today's `onChildReconnectExhausted` stops the whole `ScreenShareForegroundService` once
`endedChildCount >= expectedChildCount` (i.e. every dialed child, win-or-lose, has individually
given up). That condition happens to still be correct for "nobody ever got a Live session at all"
(all candidates exhaust without anyone claiming), but it is renamed/clarified here so the two truly
distinct cases are explicit rather than accidentally conflated by one counter:

```kotlin
private val exhaustedChildIds = mutableSetOf<UUID>() // replaces endedChildCount

private fun onChildPermanentlyDone(child: AuthorizedChild, wasActive: Boolean) {
    exhaustedChildIds += child.childId
    when {
        wasActive ->
            // The one client that was actually Live gave up for good -- there is no longer an
            // active or recoverable session (screen-sharing-core PLAN.md §8), so sharing ends.
            stopSession("reconnect_timeout")
        activeChildId == null && exhaustedChildIds.size >= expectedChildCount ->
            // Nobody ever claimed the slot, and every dialed candidate has now given up.
            stopSession("reconnect_timeout")
        else ->
            // A losing candidate gave up while someone else is genuinely Live -- not a reason to
            // stop the session.
            Unit
    }
}
```

This is a real (small) bug-fix opportunity independent of §4: today's flat counter *happens* to
produce the right aggregate answer only because it waits for every child to individually finish;
the rewrite above makes the "only the active claimant's exhaustion stops the whole share session"
invariant explicit and auditable rather than incidental.

### 3.4 `broadcastVideoFrame` / `broadcastFrame` — no change needed

Both already iterate `sessionServersByChild.values` generically; since §3.1-§3.2 guarantee that map
never holds more than one entry once claimed, no change is required there. Left as-is deliberately —
noted so a future reviewer doesn't go looking for a diff that isn't needed.

## 4. RESOLVED — Explicit "Busy" Notice, Then Give Up (Terminal)

**Decided 2026-09-03** — see `docs/QA.md` ("Single-Session Rejection Mechanism"): the "Explicit +
give up" row below is the chosen design. The losing client is told immediately via `SESSION_BUSY`
and does not automatically retry. §5 and §6 below (previously conditional) now apply as written —
no other row in the table is built.

Kept below for the record: the full design space this plan laid out before the decision, so the
rejected alternatives' tradeoffs stay documented.

The three named options decompose into two independent axes:

| | Give up on the loser for good | Retry the loser later (if the slot frees up within its own reconnect window) |
|---|---|---|
| **Silent** (no signal to the losing client) | = "silent drop" | = "queue-and-connect-after" (silent form) |
| **Explicit** (`SESSION_BUSY` sent, distinct child-side UI) | = "explicit busy response" | a fourth, unnamed hybrid — notice shown, then eventually connects anyway |

| Axis choice | `handleLosingCandidate` body | New wire message? | New `ConnectionState`? | New UI/strings? | Child listener after rejection |
|---|---|---|---|---|---|
| Silent + give up | Close the throwaway socket, call `onChildPermanentlyDone(child, wasActive = false)` immediately (no further retry). | No | No | No | Closes — child is left indefinitely in `WaitingForParent` with **no feedback that it will never connect this session** (flagged UX concern below). |
| Silent + retry | Close the throwaway socket; treat exactly like a normal failed `connect()` — fall through to the existing backoff/`delay()`/retry loop already in `runSessionLoop`, bounded by the same `RECONNECT_WINDOW_MS` (60s) used for post-connection-loss reconnects. | No | No | No | Stays open (existing `ChildSessionListener`/`WaitingForParent` behavior, completely unchanged). |
| Explicit + give up | Send `SESSION_BUSY` (§5) over the just-connected socket, then close; call `onChildPermanentlyDone(child, wasActive = false)` immediately. | Yes | Yes (`Rejected`, terminal — §6) | Yes (§6) | Closes, mirroring `Disconnected`'s existing `listener.stop()` call. |
| Explicit + retry | Send `SESSION_BUSY`, close, but *do* re-enter the backoff/retry loop (same as "silent + retry"). | Yes | Yes, but **non-terminal** — child must keep its `ChildSessionListener` open after showing the notice so a later successful dial can still reach it, unlike `Disconnected`. | Yes | Stays open — architecturally different from the terminal case; §6 does not attempt to fully design this hybrid since it is the least-specified of the four and no design source leans toward it. |

Observations to weigh when this is decided (not a recommendation — recorded so the decision doesn't
re-derive them later):

- **"Silent + give up" has a real UX gap**: once the parent's dial loop for that child exhausts
  (`RECONNECT_WINDOW_MS`, 60s) it simply stops trying — but the child is never told, and keeps
  showing the ordinary, untimed "waiting for parent to start sharing" screen (detailed-spec §17)
  forever, indistinguishable from "the parent just hasn't started yet." A non-tech-savvy user has
  no way to know they need to do anything differently.
- **"Silent + retry" is the cheapest to build** (§3 groundwork only, zero new protocol/UI) and has
  no such gap — the losing client simply keeps waiting normally and connects automatically once the
  active session ends, within the existing 60s window. Its downside: if the active session runs
  longer than 60s (very likely for a real screen-share call), the losing client's dial attempt from
  the parent's side still times out and gives up, silently, at the 60s mark — reintroducing the same
  gap as "silent + give up" just with a longer fuse, unless `RECONNECT_WINDOW_MS` is special-cased
  for this scenario (a further sub-decision).
- **"Explicit + give up" is the most informative to the user** and maps cleanly onto this
  project's existing terminal-state pattern (`Disconnected`, §6 of connection-status-and-resilience
  PLAN.md) — it is also the option `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §5
  speculates ("presumably adding a new rejection/busy signal...") will eventually be built, though
  that plan explicitly does not commit to it either.
- **The unnamed "explicit + retry" hybrid** is the most work (non-terminal notice state, listener
  must survive past it) for a benefit (informative *and* eventually connects) that may not matter in
  practice given spec.md's real-world scenario is a single short call, not a long queue of waiting
  viewers.

**Resolved** — see the heading above; §5/§6 are unblocked.

## 5. Wire Protocol: `SESSION_BUSY`

The "explicit + give up" row above was chosen, so:

- `net/MessageType.kt`: add `SESSION_BUSY(12)` (next free tag after `INPUT_KEY(11)`, before the
  pairing-time-only `PAIRING_HELLO(100)`/`PAIRING_ACK(101)` block).
- `net/SessionHandshakeMessages.kt`: add alongside the existing `SessionHelloPayload`/
  `SessionEndPayload` (same file — already the home for session-lifecycle payloads):
  ```kotlin
  data class SessionBusyPayload(val reason: String = "session_already_active") {
      fun toJson(): String = JSONObject().apply { put("reason", reason) }.toString()
      companion object {
          fun parse(raw: String): SessionBusyPayload =
              SessionBusyPayload(reason = JSONObject(raw).optString("reason", "session_already_active"))
      }
  }
  ```
  A `reason` field is included even though only one reason exists today, for parity with
  `SessionEndPayload`'s shape and future-proofing/debug-log readability — not a product requirement.
- `docs/plans/README.md` §4's shared message-type table gains a new row:
  `| SESSION_BUSY | parent→child, sent immediately after a losing arbitration attempt, before any
  SESSION_END/VIDEO_FRAME | {reason: String} | single-session-enforcement |`
  (a coordinating session should add this row and the corresponding `docs/plans/README.md` §8
  dependency-tier entry for this feature — noted here, not edited by this plan document itself).
- `ParentSessionServer.connect()`'s caller (`runSessionLoop`, §3.1) sends this frame directly over
  the freshly-connected socket in the losing branch, then calls `server.close()` — no change to
  `ParentSessionServer` itself is required (`sendFrame`/`close` already exist and are generic).
- **PC client cross-reference (no code change in this plan)**: once this is implemented,
  `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §5 needs an update to actually
  handle `SESSION_BUSY` (currently explicitly states no such handling exists). Out of scope here —
  flagged so a future session doesn't miss it.

## 6. Child-Side State & UX (`connection-status-and-resilience`) — only for the terminal "explicit" branch

Assumes "explicit + give up" (§4) is the chosen option, since it is the only one of the four this
plan fully designs (see §4's closing note on why the hybrid is left undesigned). If a different
option is chosen, revisit this section before implementing.

### 6.1 New `ConnectionState`

`app/src/main/java/com/familyremote/app/common/ConnectionState.kt` gains a fifth, terminal sealed
subclass, mirroring `Disconnected`'s shape exactly:

```kotlin
data class Rejected(val pairing: PairingEntry) : ConnectionState()
```

Placed as its own state (not folded into `Disconnected` with different text) because, unlike the
process-kill-vs-network-outage ambiguity `Disconnected` deliberately stays generic about
(`docs/plans/connection-status-and-resilience/PLAN.md` §8), the cause here is precisely known and
worth stating plainly to the user.

### 6.2 `ChildSessionChannel` / `ConnectionStateViewModel` wiring

`ChildSessionListener.Result.Connected` already flips `ConnectionStateViewModel` straight to `Live`
on accept (§4 of the original plan; no application-level handshake ack exists in either direction
today, so this was always optimistic). `SESSION_BUSY`, when it arrives, is handled the same way
`SESSION_END` already is — as a terminal signal recognized by `ChildSessionChannel`'s read loop,
surfaced via a new callback:

```kotlin
// ChildSessionChannel: alongside the existing SESSION_END check in its read loop.
if (frame.type == MessageType.SESSION_BUSY) {
    onSessionBusy()
    return
}
```

`ConnectionStateViewModel` wires `onSessionBusy` to close the channel, stop the listener (mirrors
`onDisconnectedDetected`'s cleanup), and set `_state.value = ConnectionState.Rejected(pairing)`.

**Accepted simplification, called out explicitly**: because `SESSION_BUSY` is sent as the very
first frame the parent writes after losing arbitration (before any `VIDEO_FRAME`), and the child
already flipped to `Live` optimistically on accept, there is a technically-real but
sub-second/imperceptible internal transition through `Live` before `Rejected` — no video frame is
ever actually rendered during that window (none has been sent), so nothing visibly "looks live" to
the user. This is judged acceptable rather than restructuring `onConnected()` into a
confirm-before-Live handshake (a larger, more invasive change to an already-merged file, for a
window with no observable effect) — flag for `designer-lite`/reviewer if this reasoning doesn't
hold up under review.

### 6.3 UI

`SessionActivity`/`activity_session.xml` gain a `rejectedSection` mirroring `disconnectedSection`
(same layout pattern: message text + a single "back to device list" action, reusing
`session_disconnected_back`'s existing string — same action, no need for a second string). New
`renderState()` branch:

```kotlin
binding.rejectedSection.visibility = if (state is ConnectionState.Rejected) View.VISIBLE else View.GONE
// ...
is ConnectionState.Rejected ->
    binding.rejectedText.text = getString(R.string.session_rejected_title, state.pairing.label)
```

### 6.4 New strings (ja/en/zh, per detailed-spec §18)

Added to all three of `values/strings.xml`, `values-ja/strings.xml`, `values-zh/strings.xml`
(matching the existing `session_disconnected_title` placeholder pattern):

| Locale | `session_rejected_title` |
|---|---|
| en (default) | `Another device is already connected to %1$s. Try again once that session ends.` |
| ja | `%1$sには既に別の端末が接続されています。そのセッションが終了してから、もう一度お試しください。` |
| zh | `已有其他设备连接到%1$s。请等该连接结束后再试一次。` |

No new string needed for the back button (reuses `session_disconnected_back`).

## 7. Files Touched — Summary

| File | Change |
|---|---|
| `app/src/main/java/com/familyremote/app/parent/ScreenShareForegroundService.kt` | §3.1-§3.3: `activeChildId` + `arbitrationLock`, `tryClaimSession`, restructured `runSessionLoop`/`onChildSessionEnded`/`onChildPermanentlyDone` (renamed from `onChildReconnectExhausted`), new `handleLosingCandidate` (body depends on §4). |
| `app/src/main/java/com/familyremote/app/net/MessageType.kt` | §5 (explicit branches only): add `SESSION_BUSY(12)`. |
| `app/src/main/java/com/familyremote/app/net/SessionHandshakeMessages.kt` | §5: add `SessionBusyPayload`. |
| `app/src/main/java/com/familyremote/app/common/ConnectionState.kt` | §6.1: add `Rejected`. |
| `app/src/main/java/com/familyremote/app/child/ChildSessionChannel.kt` | §6.2: recognize `SESSION_BUSY`, new `onSessionBusy` callback. |
| `app/src/main/java/com/familyremote/app/child/ConnectionStateViewModel.kt` | §6.2: wire `onSessionBusy` to `Rejected`. |
| `app/src/main/java/com/familyremote/app/child/SessionActivity.kt` | §6.3: `rejectedSection` render branch. |
| `app/src/main/res/layout/activity_session.xml` | §6.3: new section view. |
| `app/src/main/res/values/strings.xml`, `values-ja/strings.xml`, `values-zh/strings.xml` | §6.4: `session_rejected_title`. |
| `docs/plans/README.md` §4/§8 | Cross-reference only, not edited by this document — see §5's note. |

## 8. Interactions With Other Features (PC Client, remote-input-control)

- **PC Viewer Client**: shares the same `AuthorizedChild` record and the same
  `ScreenShareForegroundService` dial-out path (`docs/plans/pc-client/video-and-session/connectivity.PLAN.md`
  §4/§5) — §3's arbitration groundwork applies to a PC client exactly as it does to a second Android
  child, with no PC-specific branching needed on the parent side. If §4's open question resolves to
  an "explicit" option, `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §5 and
  `docs/plans/pc-client/connection-state-ui/PLAN.md` need a follow-up update to handle `SESSION_BUSY`
  and add an equivalent `Rejected`-style WPF state — out of scope for this plan (different codebase),
  flagged for whichever session picks that up.
- **remote-input-control**: `ConnectionState.Rejected` behaves like `Disconnected` from that plan's
  perspective — input UI is already gated on `ConnectionState.Live` specifically
  (`docs/plans/connection-status-and-resilience/PLAN.md` §3's "no state here encodes
  `inputAvailable`" note), so no change needed there; `Rejected` simply is not `Live`.
- **screen-rotation-handling**: unaffected — operates entirely within an already-claimed `Live`
  session.

## 9. Out of Scope

- Deciding §4's open question (explicitly deferred — see that section).
- The unnamed "explicit + retry" hybrid's full design (§4/§6 note why).
- Any PC-client-side (`docs/plans/pc-client/`) code changes (different codebase/repo area per this
  session's directory-placement rule — cross-referenced in §8 only).
- Any change to the pairing data model (`AuthorizedChild`) — this plan does not need one; both
  Android children and PC clients are already the same record shape.
