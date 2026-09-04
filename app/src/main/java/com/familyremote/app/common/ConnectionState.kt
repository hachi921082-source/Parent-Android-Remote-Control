package com.familyremote.app.common

import com.familyremote.app.pairing.PairingEntry

/**
 * Child-side connection lifecycle state, per docs/plans/connection-status-and-resilience/PLAN.md
 * §3 (canonical definition, referenced from docs/plans/README.md §5). Lives in `common` since
 * remote-input-control also reads it (type-level dependency only, per README.md §8's dependency
 * table) without needing this feature's runtime logic merged first.
 *
 * ```
 * Idle/PairingSelect
 *    -> WaitingForParent (untimed, cancellable)
 *    -> Live (heartbeat + missed-heartbeat watchdog active)
 *    -> Reconnecting (<=60s)
 *    -> Disconnected (terminal, directs back to Idle/PairingSelect)
 *    -> Rejected (terminal, directs back to Idle/PairingSelect -- single-session-enforcement)
 * ```
 *
 * `inputAvailable` (view-only mode, detailed-spec §11) is NOT encoded here -- it is an orthogonal
 * flag owned by docs/plans/remote-input-control/PLAN.md, layered only on top of [Live]/[Reconnecting].
 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data class WaitingForParent(val pairing: PairingEntry) : ConnectionState()
    data class Live(val pairing: PairingEntry) : ConnectionState()
    data class Reconnecting(val pairing: PairingEntry, val since: Long) : ConnectionState()
    data class Disconnected(val pairing: PairingEntry) : ConnectionState()

    /** Terminal: the parent already had another client Live and rejected this one via
     * `SESSION_BUSY` (docs/plans/single-session-enforcement/PLAN.md §4/§6). Kept as its own state
     * rather than folded into [Disconnected] -- unlike that state's deliberate
     * process-kill-vs-network-outage ambiguity, the cause here is precisely known and worth
     * stating plainly to the user. */
    data class Rejected(val pairing: PairingEntry) : ConnectionState()
}
