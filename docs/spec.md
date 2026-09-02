# Specification

<!-- Confirmed specification. Built up collaboratively with designer-lite; records only "what is currently decided." -->

## Index

1. App Overview / Purpose
2. Target Users
3. Core Features
4. App Architecture
5. Pairing Model
6. Platform / Tech Constraints
7. Security Posture
8. Out of Scope
9. Open Items

## 1. App Overview / Purpose

- Category: Android-to-Android family remote support app.
- Direction: an adult child remotely assists a non-tech-savvy parent. The **parent's** device is the one being viewed/controlled — it shares its screen via Android MediaProjection. The **child's** device is the viewer/controller — it displays the parent's screen and can send taps/swipes/text input to it. This is a "remote support" tool (TeamViewer-for-family style), not a parental-control / monitoring app.
- Use case: the child talks the parent through something over a phone call while seeing and operating the parent's screen remotely.

## 2. Target Users

- Child: tech-comfortable adult, operates the viewer/controller side.
- Parent(s): non-tech-savvy, operates the screen-sharing side. At least two parents are assumed (mother and father), each on a separate device.
- Minimizing the number of operation steps required on the parent's side is the top priority of this app's design.
- Parent tech proficiency is very low — cannot be assumed to reliably operate even basic phone features (e.g. switching to speakerphone). Any design requiring an extra parent-side action beyond the core share-start flow is treated as high-risk.

## 3. Core Features

- Screen viewing: child's device displays a live mirror of the parent's device screen, via Android MediaProjection screen sharing.
- Remote control: child can send tap and swipe input to the parent's device.
- Text input: child can send text input to the parent's device.
- Screen sharing covers the parent's entire device display (not a single app) and continues across whatever app the parent switches to, with no restart needed.
- Exception: apps that set `FLAG_SECURE` (e.g. banking apps, password managers) always render as a black screen on the child's viewer; this cannot be bypassed.
- Screen-sleep prevention: while a sharing session is active, the parent's device screen is held awake (no auto-lock), so the child's operation is never interrupted by the parent's device sleeping.
- Parent device status summary: the parent's device status (especially Wi-Fi/connectivity state) is sent to and displayed on the child's screen, so the child can diagnose connection problems remotely.
- Tap-position visualization: the child's viewer shows a pointer/marker at the position just tapped, as operation feedback.
- Accessibility Service permission monitoring: the app periodically checks that the Accessibility Service permission (required for remote tap/swipe/text input injection) is still granted on the parent's device, and guides recovery if it has been revoked.
- Screen rotation handling: when the parent's device screen rotates, the child's view automatically fits the parent's current screen aspect ratio, with no need to restart sharing.

## 4. App Architecture

- Single combined app installed on both parent and child devices (not separate parent/child apps).
- Role/mode is switched via a tab inside the same app.
- Default tab shown on launch: parent mode (the "Show Screen" / share-screen UI). The child manually switches to the viewer/controller tab; there is no automatic role detection.

## 5. Pairing Model

- Pairing between a parent device and a child device is fixed (pre-established), not entered per session.
- Pairing is stored as a list of pairs (not a fixed 1:1 relationship), to support multiple parent devices (e.g. both mother's and father's phones paired to the same child device).
- One-time setup of each pairing is performed by the child.
- Each pairing entry's label can be renamed by the child at any time after creation, independent of the underlying pairing itself.

## 6. Platform / Tech Constraints

- Platform: Android.
- Minimum supported Android version: 8.0, driven by the minimum version required by the Tailscale Android app (used for connectivity).

## 7. Security Posture

- Security requirements are minimal: the app is intended for use among family members only, not for public/general distribution.

## 8. Out of Scope

- Control Functions as a standalone design section — the tap/swipe/text-input requirement itself is in scope (see §3), but no dedicated design section for it is planned.
- Development Time Estimate.
- Automated voice/call integration (e.g. auto-dialing the child on share-start) — see `docs/DECISIONS.md` for why this was rejected.

## 9. Open Items

See `docs/QA.md` for all questions currently requiring the user's decision. This spec will be updated as each item is resolved.
