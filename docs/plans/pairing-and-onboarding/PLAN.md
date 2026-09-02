# PLAN: Pairing & Onboarding

<!-- Implementation plan. Covers detailed-spec.md §1 (Target Devices), §2 (Parent-Side Device Setup), §3 (Pairing Data Model), §13 (Initial Pairing & Permission Onboarding Flow). -->

## Index

1. Scope
2. Data Model
3. Components
4. Pairing Handshake Protocol
5. Onboarding Flow (Screens & Steps)
6. Parent-Side Device Setup Helper (Nubia S2 / MyOS)
7. Label Rename Flow
8. Open Items

## 1. Scope

This feature covers everything needed to go from "app freshly installed on both devices" to "a working, named pairing entry that other features (screen-sharing-core, connection-status-and-resilience) can use to dial or accept a connection." It does not cover the share-start flow itself (screen-sharing-core) or the waiting-for-parent UI (connection-status-and-resilience §17) — only the one-time setup that makes those possible.

Devices in scope (detailed-spec §1): mother's AQUOS Wish4 (no special setup steps defined), father's ZTE Nubia S2 / MyOS (needs §2's battery/autostart steps).

## 2. Data Model

Package: `com.familyremote.app.pairing` (data classes shared via `common` per docs/plans/README.md §Data Models).

### 2.1 Child-side: `PairingEntry` (persisted list)

```
data class PairingEntry(
    val id: UUID,                 // = pairingId, generated at pairing time
    var label: String,            // free-form, editable any time (spec.md §5)
    val parentHost: String,       // parent's Tailscale IP (100.64.0.0/10) or MagicDNS name
    val sharedSecret: String,     // random 256-bit token, base64, generated at pairing time
    val createdAt: Long
)
```

Storage: Room database (single table `pairing_entries`) OR `EncryptedSharedPreferences`-backed JSON list — given the dataset is tiny (a handful of entries) and there is no query complexity, use a single JSON-serialized list in `EncryptedSharedPreferences` (simpler, avoids pulling in Room for one table). Key: `"pairing_entries_v1"`.

### 2.2 Parent-side: `AuthorizedChild` (persisted list, extends detailed-spec §3)

detailed-spec §3 states pairing entries live on the child's device and the parent only "needs to recognize/accept a connection from its paired child device via the shared device ID/key" — it does not enumerate what the parent stores. However, §17/Decision #12 require the **parent to actively dial the child** at share-start time, which is only possible if the parent has previously learned the child's address. This plan fills that gap (see §8 below — judged as a single-answer implementation completion, not escalated) by having the parent also persist a small record per paired child:

```
data class AuthorizedChild(
    val childId: UUID,            // = same pairingId as the child's PairingEntry.id
    val childHost: String,        // child's Tailscale IP or MagicDNS name, captured during pairing
    val sharedSecret: String      // same secret as PairingEntry.sharedSecret
)
```

No label field exists here (per §3, label is child-side bookkeeping only). Stored the same way as 2.1 (`EncryptedSharedPreferences`, key `"authorized_children_v1"`), as a list (supports being paired to more than one child in principle, even though the current use case only has one).

### 2.3 Shared secret format

256-bit (32 byte) value from `SecureRandom`, base64url-encoded for embedding in the QR payload and for storage.

## 3. Components

| Component | Device | Responsibility |
|---|---|---|
| `PairingRepository` | both | CRUD over `PairingEntry` list (child) or `AuthorizedChild` list (parent); used by pairing UI and by screen-sharing-core/connection-status-and-resilience to look up who to dial / how to authenticate. |
| `TailscaleAddressResolver` | both | Detects this device's own Tailscale IPv4 address by enumerating `NetworkInterface.getNetworkInterfaces()` and selecting the first IPv4 address in `100.64.0.0/10` (Tailscale's CGNAT range), or interface named `tailscale0`. Returns null (with a "Tailscale not detected" UI state) if none found. |
| `ParentPairingActivity` | parent | Screen shown when the parent's device is put into "pairing/setup mode" (started by the child, physically operating the parent's phone). Displays a QR code encoding `{childHello: false, parentHost, tempPairingToken}` — see §4. |
| `ChildPairingScanActivity` | child | Camera-based QR scanner (e.g. ML Kit barcode scanning or ZXing) that reads the parent's QR, then performs the handshake in §4, then hands off to `PairingLabelPromptScreen`. |
| `PairingLabelPromptScreen` | child | Simple text-entry screen ("Name this device", pre-filled suggestion "Parent"), on submit creates the `PairingEntry` and writes it via `PairingRepository`. |
| `PairingListScreen` | child | Lists existing `PairingEntry` rows (label + rename affordance + delete), and is the entry point for "add new pairing" (re-launches `ChildPairingScanActivity`). Also the selection screen referenced by detailed-spec §3 when >1 entry exists (reused, not a separate screen — see connection-status-and-resilience PLAN.md for how it's invoked at connect time). |
| `MyOSSetupChecklistScreen` | parent | Shown once, only reached via a step in the onboarding flow when the running device is detected as the ZTE Nubia S2 (or MyOS in general, see §6), with step-by-step instructions and deep links to the relevant Settings screens. |

## 4. Pairing Handshake Protocol

Reuses the same TCP framing described in docs/plans/README.md §Message Protocol (`net` package), run once during onboarding rather than during a live session.

Sequence (child physically operating both devices, per spec.md §5 / detailed-spec §13):

1. On the **parent** device: child opens the app, parent-mode tab, taps "Pair a new child device" (a setup entry point distinct from "Show Screen" — see connection-status-and-resilience / screen-sharing-core PLAN.md for how these two entry points coexist on the same tab). `ParentPairingActivity`:
   - Resolves `parentHost` via `TailscaleAddressResolver`. If null, shows "Tailscale not detected — install/sign in to Tailscale first" and does not proceed (see §8.1).
   - Generates a short-lived `tempPairingToken` (random, valid 5 minutes, used only to authenticate this one handshake — not the final `sharedSecret`).
   - Starts a temporary TCP listener on `PAIRING_PORT` (see README.md Constants) bound to the Tailscale interface.
   - Renders a QR code encoding JSON: `{"v":1,"parentHost":"<ip-or-hostname>","port":<PAIRING_PORT>,"tempToken":"<tempPairingToken>"}`.
2. On the **child** device: child switches to child-mode tab → "Add a parent device" → `ChildPairingScanActivity` opens the camera, scans the QR, parses the payload.
3. Child device connects out to `parentHost:PAIRING_PORT`, sends `PAIRING_HELLO` frame containing `{tempToken, childHost (self-resolved via TailscaleAddressResolver), proposedSecret (freshly generated by the child)}`.
4. Parent verifies `tempToken` matches and hasn't expired; if valid, parent persists `AuthorizedChild(childId = new UUID, childHost, sharedSecret = proposedSecret)`, replies `PAIRING_ACK` containing the same `childId` (UUID) it just generated, then closes the temporary listener and shows "Paired successfully" on the parent screen (informational only — no further parent action needed, consistent with spec.md §2's low-friction requirement for the parent, since even this pairing step is performed by the child holding the parent's phone).
5. Child receives `PAIRING_ACK`, creates `PairingEntry(id = childId from ack, label = <pending>, parentHost, sharedSecret = proposedSecret, createdAt = now)`, navigates to `PairingLabelPromptScreen` to collect the label, then saves via `PairingRepository`.
6. If any step times out (5 minutes) or fails (network error, malformed QR), show a retry-capable error screen on the child device; the parent-side temporary listener auto-closes after the same 5-minute window regardless of outcome.

This keeps directionality consistent with §17/Decision #12: the **child** dials the **parent** during pairing (parent is guaranteed foreground/open at that moment since the child is physically holding it), while the **parent** dials the **child** during actual session start (§17) — no contradiction, just two different moments with different natural initiators.

## 5. Onboarding Flow (Screens & Steps)

Maps 1:1 to detailed-spec §13's five steps:

1. **Install app** — out of scope for in-app flow (APK install/Play Store, manual).
2. **Accessibility Service walkthrough** (parent device): a guided screen (`AccessibilityOnboardingScreen`) shown the first time the parent-mode tab is opened without the permission granted — explains in plain language why it's needed, then a single button that opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` (cannot deep-link directly to the toggle itself — Android does not allow that — but can pass the target component via `EXTRA_FRAGMENT_ARG_KEY` on API levels that support it, best-effort). Detection of "granted" reuses the same permission-check helper as remote-input-control's `AccessibilityPermissionMonitor` (see docs/plans/remote-input-control/PLAN.md — shared component, not duplicated).
3. **ZTE-specific settings** — see §6 below.
4. **Pairing exchange** — see §4 above.
5. **Label assignment** — see §4 step 5 (`PairingLabelPromptScreen`).

The onboarding flow is presented as a single guided wizard (`OnboardingWizardActivity`, child-operated, walking through steps 2→3→4→5 on the parent device, then handing off to step 4's counterpart flow on the child device), entered via "Pair a new child device" and "Pair a new parent device" entry points described in §4. Steps 2-3 run once per parent device and are skipped on re-entry if already satisfied (e.g., re-pairing the same physical parent device for a second child, if that scenario arises).

## 6. Parent-Side Device Setup Helper (Nubia S2 / MyOS)

- Device detection: `Build.MANUFACTURER`/`Build.MODEL` match against a known list (`"ZTE"` / model containing `"Nubia S2"`), falling back to a generic "if your phone aggressively closes background apps, these settings may help" screen for unrecognized devices (defensive default, since the AAQUOS Wish4 currently has no defined steps per detailed-spec §2 and this may need revisiting per docs/QA.md's tracked verification item).
- `MyOSSetupChecklistScreen` renders detailed-spec §2's two steps as a checklist with deep links where possible:
  1. "Settings → App Management → App List → [This App] → Battery → Allow Background Use → No Restrictions" — attempt `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:" + packageName)` as the closest available deep link (MyOS's exact battery sub-screen is not directly addressable via a standard Intent), with the full instruction text shown so the child can navigate manually from there.
  2. "Settings → App Management → Auto Start Management → Enable [This App]" — no standard Android Intent reaches this MyOS-proprietary screen; show instructions only, no deep link attempt.
- This is a checklist the child ticks off manually (no way for the app to programmatically verify these OEM-specific settings were actually applied) — purely informational/guided, consistent with detailed-spec §2's manual one-time steps.

## 7. Label Rename Flow

`PairingListScreen` → tap a row's rename affordance → inline text edit or small dialog → `PairingRepository.updateLabel(id, newLabel)` → persists immediately. Per spec.md §5 / detailed-spec §3, this never touches the underlying `parentHost`/`sharedSecret`/`id`, and never contacts the parent device (label is purely local, child-side bookkeeping).

## 8. Open Items

### 8.1 Parent-side `AuthorizedChild` storage (§2 above) — filled gap, not escalated

detailed-spec §3 only explicitly describes pairing data stored on the child's device; it does not enumerate what (if anything) the parent stores beyond being able to "recognize/accept a connection... via the shared device ID/key." Since §17/Decision #12 require the parent to actively dial the child at share-start time, the parent must know the child's address ahead of time. This plan resolves that by having the parent persist a small `AuthorizedChild` record per pairing, captured during the same §4 handshake. This is treated as a natural completion of the data model (there is no plausible alternative design that avoids the parent needing to store *something* for this to work), not a product-level decision — so it was implemented directly rather than escalated to docs/QA.md. Flagged here for visibility/traceability.

### 8.2 Tailscale prerequisite — filled gap, not escalated

Neither detailed-spec §2 nor §13 lists "install and sign in to Tailscale (same tailnet on both devices)" as an onboarding step, even though detailed-spec §7 assumes Tailscale connectivity is available. This plan adds it implicitly via `TailscaleAddressResolver`'s failure state ("Tailscale not detected") at the point pairing is attempted (§4 step 1), which blocks progress with an explanatory message until Tailscale is installed/signed in — rather than as a separate enumerated onboarding step. This is treated as an obvious technical prerequisite (Tailscale is external, non-negotiable infra per detailed-spec §7), not a product decision, so it was implemented directly rather than escalated.

### 8.3 Blocked pending docs/QA.md / designer-lite

None for this feature. (The one cross-cutting item that was pending — process-kill recovery UX, `docs/plans/screen-sharing-core/PLAN.md` §10 and `docs/plans/connection-status-and-resilience/PLAN.md` §8 — was resolved 2026-09-03 via `docs/QA.md` → `docs/DECISIONS.md` #13; it never blocked this feature's plan and both of §8.1/§8.2 above remain consistent with that resolution — neither the `AuthorizedChild` storage extension nor the Tailscale-prerequisite handling depend in any way on how process-kill recovery UX was decided.)
