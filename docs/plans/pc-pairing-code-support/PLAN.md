# PLAN: PC Pairing Code Support (Android Side)

<!-- Implementation plan for an EXISTING-Android-app change. This feature exists because of the
PC Viewer Client (see docs/pc-client-spec.md §6, docs/pc-client-detailed-spec.md §3) but its
code changes land entirely in app/ (Kotlin), so per explicit user instruction it lives in this
flat docs/plans/<feature-name>/ layout alongside the five already-merged Android tiers, NOT
under docs/plans/pc-client/ (which is reserved for the new C# pc-client/ codebase). This plan is
intentionally NOT added to docs/plans/README.md's dependency-tier table (§8) or feature list
(§1) -- kept administratively separate from that tracked tier system per the same instruction,
even though it lives in the same flat directory layout. See docs/plans/pc-client/README.md §2
for the full rationale. -->

## Index

1. Scope
2. Manual Code Generation
3. `ParentPairingActivity` UI Changes
4. `PairingServer` Redesign: Multi-Attempt Loop + Independent Lockout Counters
5. Wire Format (Unchanged Field Shape, New Disambiguation Rule)
6. String Resources
7. Interactions With Existing Pairing Flow
8. Open Items

## 1. Scope

Extends the already-merged `ParentPairingActivity` (`docs/plans/pairing-and-onboarding/PLAN.md` §3) to also display a plain-text host address and a manually-typeable Crockford Base32 pairing code, and redesigns the already-merged `PairingServer` (`app/src/main/java/com/familyremote/app/pairing/PairingServer.kt`) to tolerate a bounded number of failed attempts per credential type instead of closing on the first failure. This is the Android-side half of `docs/pc-client-detailed-spec.md` §3; the PC-side half (the two-field pairing screen, Crockford normalization, and this handshake's initiating side) is `docs/plans/pc-client/pairing-pc-side/PLAN.md`.

## 2. Manual Code Generation

New object `pairing/ManualPairingCodeGenerator.kt`, alongside the existing `SecureTokenGenerator.kt`:

```kotlin
object ManualPairingCodeGenerator {
    // Crockford Base32 alphabet, excludes I, L, O, U (docs/pc-client-detailed-spec.md §3).
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_LENGTH = 8
    private val secureRandom = SecureRandom()

    fun generate(): String = (1..CODE_LENGTH)
        .map { ALPHABET[secureRandom.nextInt(ALPHABET.length)] }
        .joinToString("")
}
```

Generated once per pairing attempt, alongside the existing `tempToken` (`ParentPairingActivity.startPairingAttempt()`), with the same 5-minute validity window (`Constants.PAIRING_TOKEN_VALIDITY_MS`) — not a separately-lived credential, per `docs/pc-client-detailed-spec.md` §3.

## 3. `ParentPairingActivity` UI Changes

`app/src/main/java/com/familyremote/app/pairing/ParentPairingActivity.kt` / `res/layout/activity_parent_pairing.xml`:

- Add two new `TextView`s below the existing `qrImageView` (before `statusText`): `hostAddressText` (shows `getString(R.string.parent_pairing_host_address_label, parentHost)`) and `manualCodeText` (shows `getString(R.string.parent_pairing_manual_code_label, formattedCode)`, where `formattedCode` groups the 8-character code as `XXXX-XXXX` for readability — an implementation-level display choice per `docs/pc-client-detailed-spec.md` §3's "exact text rendering... left open" note; the underlying value passed to `PairingServer` is always the ungrouped 8-character string).
- `startPairingAttempt()`: after resolving `parentHost` and generating `tempToken` (existing code), also call `ManualPairingCodeGenerator.generate()` and pass both the host address and the generated code to the new UI elements above, and pass the generated code into `PairingServer.listenForHandshake` as a new `manualCode` parameter (§4).
- Both `hostAddressText` and `manualCodeText` are shown/hidden together with `qrImageView` (same visibility lifecycle: visible while waiting, hidden on success/error) — all three represent the same in-progress pairing attempt.
- No change to the QR code's own encoded payload (`PairingQrPayload` — still just `{v, parentHost, port, tempToken}`, unchanged) — the manual code is additive, shown alongside, never folded into the QR payload itself.

## 4. `PairingServer` Redesign: Multi-Attempt Loop + Independent Lockout Counters

`app/src/main/java/com/familyremote/app/pairing/PairingServer.kt` currently accepts **exactly one** connection and closes the listener regardless of outcome (see its own doc comment: "accepts exactly one connection, then closes -- regardless of outcome"). `docs/pc-client-detailed-spec.md` §3 requires a bounded number of tolerated failed attempts per credential type before that specific channel locks out for the remainder of the window — this is not achievable without changing this single-accept design. This plan extends `PairingServer` (an already-merged file) to support the new requirement, treated as part of implementing the already-accepted manual-pairing-code exception (`docs/DECISIONS.md` #19's first exception, `ParentPairingActivity`) rather than a new, separate exception — the manual code cannot function per spec without this same listener also tolerating retries.

New `listenForHandshake` signature and loop:

```kotlin
suspend fun listenForHandshake(
    bindAddress: String,
    port: Int,
    tempToken: String,
    manualCode: String,
    validityMs: Long
): PairingServerResult = withContext(Dispatchers.IO) {
    val deadline = System.currentTimeMillis() + validityMs
    var tempTokenFailures = 0
    var manualCodeFailures = 0
    var serverSocket: ServerSocket? = null
    try {
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
        }
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return@withContext PairingServerResult.Timeout
            serverSocket.soTimeout = remaining.toInt()

            val outcome = runCatching {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = SOCKET_IO_TIMEOUT_MS
                    val frame = MessageFrame.readFrom(socket.getInputStream())
                    require(frame.type == MessageType.PAIRING_HELLO) { "Unexpected message type: ${frame.type}" }
                    val hello = PairingHelloPayload.parse(frame.payloadAsUtf8String())

                    val isManualCodeShaped = hello.tempToken.length == MANUAL_CODE_LENGTH &&
                        hello.tempToken.all { it in CROCKFORD_ALPHABET }

                    val matched = if (isManualCodeShaped) {
                        if (manualCodeFailures >= MAX_ATTEMPTS_PER_CHANNEL) false
                        else (hello.tempToken == manualCode).also { if (!it) manualCodeFailures++ }
                    } else {
                        if (tempTokenFailures >= MAX_ATTEMPTS_PER_CHANNEL) false
                        else (hello.tempToken == tempToken).also { if (!it) tempTokenFailures++ }
                    }
                    if (!matched) return@use null // close this connection, keep listening

                    val newChildId = UUID.randomUUID()
                    repository.addAuthorizedChild(
                        AuthorizedChild(childId = newChildId, childHost = hello.childHost, sharedSecret = hello.proposedSecret)
                    )
                    val ack = PairingAckPayload(newChildId)
                    MessageFrame.of(MessageType.PAIRING_ACK, ack.toJson()).writeTo(socket.getOutputStream())
                    newChildId
                }
            }.getOrNull()

            if (outcome != null) return@withContext PairingServerResult.Success(outcome)
            // else: failed/mismatched attempt -- loop back to accept() again within the remaining window
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    } catch (e: SocketTimeoutException) {
        PairingServerResult.Timeout
    } catch (e: IOException) {
        PairingServerResult.Error(e.message ?: "Network error")
    } finally {
        runCatching { serverSocket?.close() }
    }
}

companion object {
    private const val SOCKET_IO_TIMEOUT_MS = 15_000
    private const val MAX_ATTEMPTS_PER_CHANNEL = 5
    private const val MANUAL_CODE_LENGTH = 8
    private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
}
```

Key design points:

- **Disambiguation by shape, not a new field**: an incoming `tempToken` value that is exactly 8 characters and drawn entirely from the Crockford alphabet is treated as a manual-code attempt; anything else (the QR flow's own 24-byte-base64url token is much longer) is treated as a QR-token attempt. No new JSON field is added to `PairingHelloPayload` — `docs/pc-client-detailed-spec.md` §3 does not request one, and this keeps the wire shape unchanged for the existing QR-only Android-to-Android flow.
- **Independent counters, independent lockout**: `tempTokenFailures`/`manualCodeFailures` are tracked separately; once either reaches `MAX_ATTEMPTS_PER_CHANNEL` (chosen as **5**, within the spec's suggested "e.g. 3, 5" range — a product-neutral implementation choice per `docs/pc-client-detailed-spec.md` §3's explicit delegation), further attempts shaped for that channel are rejected outright (without even comparing the value) for the remainder of the window, while the other channel remains available. A failed manual-code attempt never increments `tempTokenFailures`, and vice versa — exactly the "does not count against the QR/`tempToken` lockout" requirement.
- **A failed/mismatched attempt no longer kills the whole listener** — the socket for that one connection is closed (implicit via `.use {}`), and the loop calls `accept()` again, unlike the previous one-shot design. This applies uniformly to both channels (a bad QR scan is now also tolerated up to the same bound, not just manual-code attempts) — a natural consequence of sharing one listener/loop, not a distinct new lenience being deliberately added to the QR flow for its own sake.
- **Success still ends the listener immediately** (`return@withContext PairingServerResult.Success(...)`), regardless of which channel succeeded — unchanged from the existing one-pairing-per-listener-lifetime assumption `ParentPairingActivity`'s UI already relies on (shows a terminal "Paired successfully" screen).
- No response is sent back to the child/PC on a failed/locked-out attempt (socket simply closes) — matches the existing silent-failure precedent already established for the live-session `ChildSessionListener`'s mismatch handling (`docs/plans/connection-status-and-resilience/PLAN.md` §4 step 4), extended here to the pairing-time listener for consistency.

## 5. Wire Format (Unchanged Field Shape, New Disambiguation Rule)

`PairingHelloPayload` (`app/src/main/java/com/familyremote/app/pairing/PairingHandshakeMessages.kt`) is **not modified** — `tempToken`, `childHost`, `proposedSecret` remain exactly as-is. The PC client (`docs/plans/pc-client/pairing-pc-side/PLAN.md` §6) sends its normalized 8-character manual code in the same `tempToken` field a QR-scanning Android child would send its scanned token in; §4 above's shape-based disambiguation is the only new logic, entirely server-side.

## 6. String Resources

New keys added to `res/values/strings.xml` (English, authored directly here) plus `res/values-ja/strings.xml`/`res/values-zh/strings.xml` (translation-content task, per the existing ja/en/zh policy, `docs/detailed-spec.md` §18):

- `parent_pairing_host_address_label` — e.g. `"Host address: %1$s"`.
- `parent_pairing_manual_code_label` — e.g. `"Manual code: %1$s"`.

Both follow the existing `parent_pairing_*` naming convention already used in `res/values/strings.xml` (`parent_pairing_title`, `parent_pairing_instructions`, etc.).

## 7. Interactions With Existing Pairing Flow

- **`docs/plans/pairing-and-onboarding/PLAN.md`**: this plan extends that plan's `ParentPairingActivity` and `PairingServer` (§3/§4 above are direct edits to those already-merged files) but does not change the QR-only Android-to-Android handshake's observable outcome — a scanning Android child still pairs exactly as before, just now via a listener that also tolerates a few failed attempts on its own channel rather than dying on the first one.
- **`docs/plans/pc-client/pairing-pc-side/PLAN.md`**: that plan's handshake (its own §6) is the client-side counterpart this plan's `PairingServer` redesign serves. The two plans must stay in sync on the "8-char Crockford-alphabet string vs. longer token" disambiguation rule and the shared `PAIRING_HELLO`/`PAIRING_ACK` field shape — do not let either drift without updating the other.

## 8. Open Items

None. `docs/pc-client-detailed-spec.md` §3 fixes every product-level requirement (host address + manual code shown together, Crockford alphabet, case-insensitive/alias-corrected entry, independent per-channel lockout); the only choice explicitly delegated to this plan (the exact attempt threshold) is resolved directly above (5), consistent with how the equivalent already-merged pairing-and-onboarding plan resolved its own analogous "filled gap, not escalated" items (`docs/plans/pairing-and-onboarding/PLAN.md` §8.1/§8.2).
