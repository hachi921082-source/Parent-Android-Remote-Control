package com.familyremote.app.common

/**
 * App-wide constants shared across features, per docs/plans/README.md §7 ("Shared Constants").
 * Values not yet consumed by an implemented feature are kept here anyway so every feature reads
 * from a single source of truth instead of re-deriving/duplicating a value later.
 */
object Constants {

    // --- Live-session protocol (net package) -- consumed by screen-sharing-core /
    // connection-status-and-resilience / remote-input-control, not by this feature directly. ---
    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val MISSED_HEARTBEAT_THRESHOLD = 3
    const val RECONNECT_WINDOW_MS = 60_000L
    const val ACCESSIBILITY_PERIODIC_CHECK_INTERVAL_MS = 30_000L
    /** Was 300ms; raised per issue #44 -- too brief for an elderly user to register/read. */
    const val TAP_MARKER_FADE_MS = 600L
    const val SESSION_PORT = 47321

    // --- Pairing (this feature). ---
    /** Temporary, pairing-time-only TCP port. See PLAN.md §4. */
    const val PAIRING_PORT = 47322

    /** How long a `tempPairingToken` (and the parent-side listener waiting for it) stays valid. */
    const val PAIRING_TOKEN_VALIDITY_MS = 5 * 60 * 1000L

    /** Tailscale's CGNAT IPv4 range (100.64.0.0/10), used by TailscaleAddressResolver. */
    const val TAILSCALE_CGNAT_FIRST_OCTET = 100
    const val TAILSCALE_CGNAT_SECOND_OCTET_MIN = 64
    const val TAILSCALE_CGNAT_SECOND_OCTET_MAX = 127
}
