# PC Viewer Client — Specification

<!-- Confirmed specification for the Windows PC viewer/controller client, an additional client type alongside the existing Android child app. Built up collaboratively with designer-lite; records only "what is currently decided." Companion document to `docs/spec.md` (the existing Android app's specification) — does not duplicate its content; cross-references it by section number where this feature depends on or reuses it. `docs/spec.md` itself is not modified by this feature. -->

## Index

1. Overview / Purpose
2. Target Users
3. Core Features
4. Relationship to the Existing Android App
5. Platform / Tech Constraints
6. Pairing Model
7. Security Posture
8. Out of Scope
9. Open Items
10. Future Extensions (Not Yet In Scope)

## 1. Overview / Purpose

- Category: a new Windows PC application that plays the "viewer/controller" role currently played by the Android child app (`docs/spec.md` §1) — i.e. it connects to an already-paired parent phone and lets the operator view the parent's screen and send tap/swipe/text input to it.
- This is an additional client type, not a replacement for the existing Android child app. Both continue to exist: a parent can be viewed and controlled from either an Android child device or this PC client.
- Use case: same as `docs/spec.md` §1 — the child talks the parent through something over a phone call while seeing and operating the parent's screen remotely, now optionally from a PC instead of (or in addition to) an Android device.

## 2. Target Users

- Same child user described in `docs/spec.md` §2 (tech-comfortable adult), now optionally operating from a Windows PC instead of an Android device.
- Same parent users described in `docs/spec.md` §2 (non-tech-savvy) — this feature does not change who the parent is or their tech proficiency; the parent-side app and its minimal-step design priority (`docs/spec.md` §2) are unaffected by this addition.

## 3. Core Features

Same feature set as `docs/spec.md` §3, played from a PC instead of an Android device:

- Screen viewing: the PC displays a live mirror of the parent's device screen.
- Remote control: the PC operator can send tap and swipe input to the parent's device.
- Text input: the PC operator can send text input to the parent's device.
- Same `FLAG_SECURE` black-screen limitation as `docs/spec.md` §3 (unchanged, parent-side OS behavior); the PC client includes an equivalent help hint explaining this, adapted to desktop conventions (e.g. a hover tooltip on an info icon, rather than Android's tap-to-open-dialog) — see `docs/pc-client-detailed-spec.md` §5.
- Same tap-position visualization feedback as `docs/spec.md` §3, shown on the PC's own viewer.
- Parent device status summary: same diagnostic content as `docs/spec.md` §3 (Wi-Fi connected/disconnected, network type, signal strength, SSID where available) is displayed on the PC client too, delivered over the same status channel as the Android child — see `docs/pc-client-detailed-spec.md` §8.
- Zoom/magnifier display: to help precisely tap small UI elements on the parent's screen (leveraging the PC's larger display versus a phone-to-phone view), the video view supports magnifying a region before the operator taps within it. Zoom is always capped to fit entirely within the current window — no panning/scrolling is needed or provided; to zoom in further, the operator resizes the window itself. This is a PC-client-side-only feature — it sends the same `INPUT_TAP`/`INPUT_SWIPE` messages already used elsewhere, with no new wire message and no parent-side awareness of zoom. See `docs/pc-client-detailed-spec.md` §10 and `docs/DECISIONS.md`.
- Physical key relay: beyond the existing complete-text-then-send text input (Text Input bullet above, unchanged), the PC client can also relay individual key presses (arrow keys, Backspace, Delete, Enter, Tab, Escape) as they're pressed on the PC keyboard, for finer live control than the batch-text-send flow. This requires a new wire message and a small addition to the parent's `ParentAccessibilityService`. See `docs/pc-client-detailed-spec.md` §11 and `docs/DECISIONS.md`.
- Application model: the PC client runs as an ordinary visible desktop window, like any normal Windows app — it does not minimize to the system tray, run as a background/headless service, or auto-start on boot. Closing the window ends the app normally.

## 4. Relationship to the Existing Android App

- The parent-side Android app is unchanged for this client, with two exceptions: its pairing-display screen is extended to also show its host address and a manually-typeable pairing code (§6 below), and its `ParentAccessibilityService` gains a small addition to support the physical key relay feature (§3 above) — see `docs/pc-client-detailed-spec.md` §3 and §11 for detail. No other change to the parent app, and no change at all to the Android child app.
- The parent app cannot tell whether it is talking to an Android child or this PC client, and does not need to.
- Video transport: reuses the existing wire protocol unchanged — the parent Android app continues to capture/encode via MediaCodec and send H.264 video exactly as it does today for the Android child (`docs/spec.md` §3, `docs/detailed-spec.md` §5). The PC client decodes this same stream itself, via Windows Media Foundation (the OS's built-in, hardware-accelerated H.264 decoder — see §5), and renders it. See `docs/DECISIONS.md` for why this video-transport approach was chosen over alternatives that would have required parent-side app changes.

## 5. Platform / Tech Constraints

- Target OS: Windows only (not cross-platform). Minimum version: Windows 10 or later.
- Tech stack: C#/.NET, WPF for the UI.
- Video decode: Windows Media Foundation (the OS's own built-in, hardware-accelerated H.264 decoder), configured for low-latency real-time operation (not the decoder's default buffering behavior) — chosen specifically over a third-party library (FFmpeg) to avoid third-party licensing/redistribution concerns entirely, since the app is Windows-only anyway. See `docs/DECISIONS.md`.
- Distribution: a single portable executable, not a Windows installer package (no MSI, no setup wizard).
- Single-instance enforcement: launching the executable a second time while an instance is already running does not start a second, independent instance.
- Repository placement: new top-level folder `pc-client/` within this same repository (not a separate repo).
- UI language: same ja/en/zh, OS-locale-auto-detected, English-fallback policy as the Android app (`docs/spec.md` §6) — applies to this client's UI too. See `docs/QA.md` ("PC Viewer Client: Does the ja/en/zh UI-Language Policy Apply to Its UI Too?", RESOLVED).

## 6. Pairing Model

- Pairing is still fixed/pre-established (`docs/spec.md` §5), not entered per session — this client follows the same list-based pairing model, not a fixed 1:1 relationship.
- The existing QR-code pairing flow (parent's pairing-display screen encodes `{v, parentHost, port, tempToken}`) is not usable as-is from a PC without a webcam. The parent's pairing-display screen is extended to also show, alongside (not instead of) the QR code, two additional pieces of information: the Tailscale host address (IP/hostname) as plain readable text, and a separate manually-typeable short pairing code, generated specifically for the PC-pairing case — so a PC without a camera can be paired by typing both in.
- Manual code alphabet: Crockford Base32-style — digits 0-9 and uppercase letters A-Z, excluding I, L, O, U (avoids character-confusion ambiguity). 8 characters. Entry is case-insensitive.
- The parent's temporary pairing listener enforces an attempt-count lockout on this manual code, to bound brute-force risk given its necessarily lower entropy than the QR flow's token — see `docs/pc-client-detailed-spec.md` §3 and `docs/DECISIONS.md`.
- The PC client's own pairing screen has two input fields — host address and the manual code — instead of a camera view.
- This addition is specific to PC pairing; the existing QR-only Android-to-Android pairing flow (`docs/plans/pairing-and-onboarding/PLAN.md`) is unchanged.

## 7. Security Posture

- Same minimal-security, family-only posture as `docs/spec.md` §7 — unaffected by this addition.

## 8. Out of Scope

- Cross-platform support (macOS/Linux) — Windows only, see §5.
- Any change to the Android child app itself.
- Any change to the video/screen-capture mechanism on the parent's Android app — see §4.

## 9. Open Items

See `docs/QA.md` for all questions currently requiring the user's decision. This spec will be updated as each item is resolved.

## 10. Future Extensions (Not Yet In Scope)

<!-- Considered and explicitly deferred by the user during a later round of discussion, not rejected — recorded here for a future session to pick up with context. Not part of Core Features (§3). -->

- **Multiple simultaneous parent-device connections**: viewing/controlling more than one paired parent device at the same time (e.g. via tabs) was considered and deliberately deferred, not built now. It is feasible with no Android-side changes — the parent side already supports being paired to multiple children, and the pairing-entry-list concept (§6, `docs/pc-client-detailed-spec.md` §4) already supports storing more than one entry — the only cost would be PC-side window/tab management.
- **File/photo transfer from PC to the parent's device**: sending a file from the PC to be saved/opened on the parent's device was considered and deliberately deferred, not built now. Unlike the zoom/magnifier and physical-key-relay features (§3), this one was **not** accepted as a further exception to this feature's general "no changes to the parent Android app beyond the already-accepted exceptions" principle — it would require a new Android-side receive-handler in the parent app (a new wire message type, plus storage/permission handling). No further design is recorded here; a future session would need to revisit whether a new parent-side receive-handler is acceptable before scoping it.
- **Manual "lock the parent's touch" overlay**: a child-toggled mechanism to suppress the parent's own physical touch input during a remote-input session was considered and not adopted now — see `docs/pc-client-detailed-spec.md` §12 for the known limitation this would address and why it isn't solved yet, and `docs/DECISIONS.md` for the full rationale. Tracked in GitHub issue [#8](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/8).
