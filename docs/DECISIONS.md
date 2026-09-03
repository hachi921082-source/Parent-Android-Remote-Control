# Decisions

<!-- Background, comparative analysis, and rejected alternatives for spec.md decisions. -->

## Index

1. MediaProjection Confirmation Dialog Cannot Be Bypassed
2. Full-Screen Sharing Instead of Per-App Sharing
3. List-Based Pairing Instead of Fixed 1:1
4. Widget-Based 2-Tap Design Chosen Over 3-Tap Baseline
5. Default Tab Is Parent Mode
6. Open Risk: MyOS Background-Kill Trigger Condition Unverified
7. Auto-Dial Call Integration Rejected
8. Screen-Sleep Prevention During Sharing Adopted
9. Text Input: `ACTION_SET_TEXT` Chosen Over Clipboard Paste and Per-Keystroke Forwarding
10. Aspect-Ratio Scaling: Letterbox/Pillarbox Chosen Over Stretch-to-Fill
11. Letterbox/Pillarbox Taps: Discard Chosen Over Clamp-to-Nearest-Coordinate
12. Parent-Initiated Connection Establishment Chosen Over Always-On Child-Polled Listener
13. No Dedicated "Resume Sharing" UI After a Full Process-Kill
14. UI Language Support: ja/en/zh via System-Locale Auto-Detect, English Fallback
15. PC Viewer Client Video Transport: Reuse Existing H.264 Wire Protocol Over New Capture Methods
16. PC Viewer Client Decode Library: Windows Media Foundation Over FFmpeg
17. PC Manual Pairing Code: Moderate Entropy Plus Attempt-Rate Limiting, Not Entropy Alone
18. PC Zoom/Magnifier: Client-Side Only, No New Wire Message
19. PC Physical Key Relay: Accepted as a Second Explicit Exception to "No Android-Side Changes"
20. PC Client Runs as an Ordinary Window, Not a Tray/Background App
21. PC Physical Key Relay: Focus-Scoped Capture, Not a Global Keyboard Hook
22. Simultaneous Parent-Touch and Remote-Input Arbitration: Known, Accepted Gap (Not Solved)
23. PC Client Decodes Continuously; No Pause/Resume on Minimize or Occlusion
24. Exclusive Single-Session Model: One Live Client at a Time, Chosen Over Simultaneous Multi-Viewer

## 1. MediaProjection Confirmation Dialog Cannot Be Bypassed

Android's MediaProjection API always shows an OS-level "Start screen sharing now?" confirmation dialog when screen capture begins, as a privacy-by-design protection. No non-root app can suppress or remotely trigger this dialog on the user's behalf. Because of this, no matter how the rest of the app is designed, there will always be one point where the parent must physically tap to confirm sharing — this is treated as a fixed, unavoidable constraint rather than a design choice, and all tap-count designs (spec.md §3, detailed-spec.md §4) are built around it rather than trying to eliminate it.

## 2. Full-Screen Sharing Instead of Per-App Sharing

MediaProjection's native behavior only supports mirroring the entire device display; there is no OS-level API to restrict capture to a single app's screen. Full-screen sharing was adopted not because it was chosen over a per-app alternative on its merits, but because per-app sharing is not technically available — this incurs no extra development cost since it's the platform's default/only behavior. The FLAG_SECURE black-screen exception (spec.md §3, detailed-spec.md §5) is a downstream consequence of this same OS-level capture mechanism.

## 3. List-Based Pairing Instead of Fixed 1:1

The requirement of supporting at least two parents (mother and father, each on a separate device) means a single child device must be able to connect to more than one parent device. A fixed 1:1 pairing model would not support this, so pairing is stored as a list of (device ID/key, label) entries on the child's device instead. This adds one extra step on the child's side only (a selection screen when more than one pairing exists, detailed-spec.md §3) — the parent-side operation count is unaffected, since parent-side setup/operation is performed independently per parent device.

## 4. Widget-Based 2-Tap Design Chosen Over 3-Tap Baseline

Because the parent user is not tech-savvy, minimizing the number of operation steps on the parent's side was set as the top design priority (spec.md §2). The baseline flow (open app icon → press "Show Screen" button → OS confirmation dialog) requires 3 taps. Since the OS confirmation dialog tap cannot be removed (see §1 above), the only remaining opportunity for reduction was combining "open app" and "press button" into a single action. A home-screen widget that directly invokes the share-start routine achieves this, reducing the flow to 2 taps (widget tap → OS dialog). This was accepted as the target design rather than merely a future idea, given the top-priority status of minimizing parent-side steps.

## 5. Default Tab Is Parent Mode

The app is a single combined app with tab-switched roles (spec.md §4). The parent is the non-tech-savvy user and must be able to use the app with zero navigation/friction, so parent mode ("Show Screen") is shown by default on launch. The child, by contrast, is tech-comfortable and can be expected to manually switch to the viewer/controller tab without needing auto-detection — so no role-detection logic was built to route the child to a different default automatically.

## 6. Open Risk: MyOS Background-Kill Trigger Condition Unverified

It is confirmed that ZTE's MyOS (running on the father's Nubia S2) aggressively force-closes background apps, and one-time device settings changes are defined to mitigate this (detailed-spec.md §2). However, it is not yet verified whether the kill trigger is based on idle time or on loss of foreground display. If it is foreground-display-based, the app's background service risks being killed even while the child is actively mid-operation (e.g. mid-tap-sending), not only when idle. The auto-reconnect mitigation (detailed-spec.md §6) reduces the impact of a kill to a brief interruption but does not eliminate the underlying risk, since the exact trigger condition has not been confirmed through testing on the actual device.

## 7. Auto-Dial Call Integration Rejected

An idea was proposed to have the share-start widget also auto-dial a voice call to the child, so the parent would reach a "talking while operating" state in effectively one tap instead of separately placing a phone call. This was rejected: the parent's tech proficiency is assessed as too low to reliably rely on for related phone operations (e.g. the parent may not even reliably operate switching to speakerphone once a call is active), so adding another automated telephony behavior was judged more likely to confuse than help. Voice/call handling stays fully out of this app's scope (spec.md §8); the parent and child continue to coordinate via a normal, separately-placed phone call.

## 8. Screen-Sleep Prevention During Sharing Adopted

While a sharing session is active, if the parent's device screen auto-locks (its normal idle-timeout behavior), the child's tap/swipe/text input would silently stop reaching the parent's screen — and the non-tech-savvy parent would have no way to self-diagnose or recover from this. To close this gap without adding any parent-side step, the app holds the parent's screen awake for the duration of an active sharing session (spec.md §3), released automatically when the session ends.

## 9. Text Input: `ACTION_SET_TEXT` Chosen Over Clipboard Paste and Per-Keystroke Forwarding

Two alternative mechanisms were considered for delivering the child's typed text to the parent's device (detailed-spec.md §16) and rejected:
- **Clipboard-based paste**: writing the child's text to the parent device's clipboard and simulating a paste action was rejected because it would silently overwrite the parent's actual clipboard contents — an unwanted side effect on a family member's personal device, which could clobber something the parent had copied for their own unrelated purposes.
- **Per-keystroke forwarding**: streaming each keystroke to the parent device as the child types was rejected due to added implementation complexity and latency sensitivity (each keystroke would need to be individually transmitted and injected in near-real-time), with no offsetting benefit for this app's use case — the child composes the full text before sending it in every realistic scenario, so there is nothing to gain from character-by-character delivery.

Instead, the app sends the complete composed string in a single message and injects it directly via the Accessibility Service's `ACTION_SET_TEXT` action on the currently-focused editable node — this avoids both the clipboard side effect and the per-keystroke complexity/latency concern.

## 10. Aspect-Ratio Scaling: Letterbox/Pillarbox Chosen Over Stretch-to-Fill

For handling a mismatch between the child's viewer area and the parent's captured frame aspect ratio (detailed-spec.md §15), stretch-to-fill (distorting the video to exactly fill the viewer area regardless of aspect ratio) was considered and rejected: stretching would distort the parent's actual screen proportions, which would make the child's tap coordinates map incorrectly onto the parent's real UI elements (a tap that looks correctly placed on the distorted video would land on the wrong spot on the parent's actual, undistorted screen). Letterboxing/pillarboxing (preserving the parent's real aspect ratio and adding black bars instead) keeps the coordinate mapping between the child's rendered video and the parent's actual screen accurate and simple.

## 11. Letterbox/Pillarbox Taps: Discard Chosen Over Clamp-to-Nearest-Coordinate

For taps/swipes landing in the letterbox/pillarbox black-bar regions (detailed-spec.md §15), clamping the coordinate to the nearest valid in-video position was considered and rejected in favor of discarding the input entirely. The black-bar regions sit at the outer edges of the child's screen, which is exactly where an accidental/stray touch (e.g. a finger brushing the edge while holding the device) is more likely to land than a deliberate tap. If such a stray touch were clamped to the nearest valid coordinate, it would silently register as a real tap at the edge of the parent's actual screen content — potentially triggering an unintended action on the parent's device with no indication to the child that this was an accidental edge-touch rather than a deliberate tap. For a non-tech-savvy parent, an unexplained action happening on their screen is worse than a stray touch simply being dropped with no effect. Discarding avoids this risk at no cost, since a deliberate tap on real video content is never in the black-bar region to begin with.

## 12. Parent-Initiated Connection Establishment Chosen Over Always-On Child-Polled Listener

For establishing the connection at the start of the §17 waiting-for-parent period (detailed-spec.md §17), an always-on listener on the parent's device (or a child-side poll against one) was considered and rejected in favor of having the parent's device initiate the connection at the moment it completes the §4 share-start flow. An always-on parent-side listener would need to survive MyOS's aggressive background-kill behavior (§1/§2/§6) with no foreground-service justification to keep it alive before sharing even starts — a strictly harder reliability problem than the one §6's mitigations already accept, since at that point there is no foreground service running yet to piggyback on. Piggybacking connection establishment on share-start time instead means it only needs to run at a moment the foreground service is already guaranteed to be running (§4) — consistent with how §8's wake lock and §6's mitigations already piggyback on that same foreground-service lifecycle rather than introducing a separate always-running component. This also means the pre-session waiting period never needs its own resilience story against MyOS's kill behavior, since nothing needs to stay alive on the parent's side during that window in the first place.

## 13. No Dedicated "Resume Sharing" UI After a Full Process-Kill

When MyOS performs a full process-kill (as opposed to a service-level stop that the process survives), Android invalidates the MediaProjection grant along with the killed process — no restart mechanism, however fast, can resume screen capture without the parent physically re-tapping the OS's own confirmation dialog (`docs/DECISIONS.md` #1). Two options were considered for this specific moment: (A/C) no dedicated recovery UI — the parent simply redoes the existing 2-tap widget flow (detailed-spec.md §4) once they notice sharing stopped (typically prompted by the child, already on the call); or (B) a dedicated "tap to resume sharing" notification, triggered by the §6 WorkManager/AlarmManager watchdog detecting the kill, to shorten the parent's path back to the confirmation dialog. The user chose (A/C): no dedicated UI is added. This keeps the total surface of parent-facing, self-service affordances unchanged, consistent with `docs/DECISIONS.md` #7 (the auto-dial-call idea was rejected on the same basis: the parent's tech proficiency is judged too low to reliably rely on for anything beyond the core minimal flow, so new recovery-specific UI aimed at the parent is treated as added risk rather than a clear win). detailed-spec.md §6's mitigation wording was corrected accordingly to scope the "brief interruption, no need to redo the tap flow" outcome to process-survives and transient-network cases only, rather than implying it also covers a full process-kill.

## 14. UI Language Support: ja/en/zh via System-Locale Auto-Detect, English Fallback

A review gap surfaced that UI language/locale was never specified anywhere in `spec.md`/`detailed-spec.md`, despite the target users being non-tech-savvy parents (spec.md §2) on Japanese-market devices (detailed-spec.md §1). detailed-spec.md §5/§11/§14/§17's "wording left to implementation" bullets had silently left language itself unspecified, and the already-merged Tier 0 (Pairing & Onboarding) feature shipped English-only `strings.xml` as a direct consequence (`docs/QA.md`, "UI Language / Locale Support").

Three sub-decisions were made together:
- **Languages: Japanese, English, and Chinese (ja/en/zh)**, covering both device markets/users in play (Japanese-market parent devices, per detailed-spec.md §1) and the child, for the whole app rather than only the onboarding flow.
- **Selection mechanism: automatic system-locale detection, not a manual in-app picker.** This follows the same precedent already applied twice elsewhere in this document — spec.md §2's step-minimization priority was used to reject an auto-dial-call affordance (#7) and a dedicated resume-sharing UI (#13) as unnecessary additions beyond the core flow. A manual language picker is the same category of problem in reverse: it would add a one-time setup step (choosing a language) that system-locale auto-detection makes unnecessary, since Android's own resource-qualifier mechanism (`values-ja/`, `values-zh/`, default `values/`) already selects the correct language with zero app-side steps for either the parent or the child.
- **Fallback: English**, for any system locale outside ja/en/zh (e.g. Korean). This was not treated as an independent design choice requiring justification — it is simply how Android's unqualified default resource set (`values/strings.xml`) already behaves for any unmatched locale, so authoring that default set in English (rather than, say, Japanese) is sufficient on its own; no additional fallback logic needs to be written or decided.

See `docs/QA.md` for the full resolution record, `docs/spec.md` §6 for the confirmed-spec bullet, and `docs/detailed-spec.md` §18 for the implementation-level elaboration.

## 15. PC Viewer Client Video Transport: Reuse Existing H.264 Wire Protocol Over New Capture Methods

For the new Windows PC viewer/controller client (`docs/pc-client-spec.md` §4, `docs/pc-client-detailed-spec.md` §5), three approaches were considered for getting the parent's screen to the PC:

1. **Reuse the existing H.264 wire protocol unchanged (chosen).** The parent's Android app continues capturing/encoding via `MediaCodec` and sending H.264 (Annex-B, in-band SPS/PPS) `VIDEO_FRAME` messages exactly as it already does for the Android child (`docs/detailed-spec.md` §5, `docs/plans/README.md` §4). The PC client decodes this same stream itself (e.g. via FFmpeg bindings such as FFmpeg.AutoGen) and renders it. The parent app cannot tell, and does not need to tell, whether it is talking to an Android child or this new PC client.
2. **Parent sends a JPEG screenshot burst.** Rejected. This would require a new parent-side capture/encode path (a screenshot-loop mechanism distinct from the existing `MediaCodec`/`VirtualDisplay` pipeline), touching the already-shipped, already-reviewed-and-merged parent app.
3. **A differential/dirty-rectangle screen-diffing codec (AnyDesk/TeamViewer-style).** Rejected. This would be more bandwidth-efficient for a mostly-static parent screen, but requires an entirely new parent-side screen-diffing encoder — substantially more complex to build and review than either option 1 or option 2, and again requires new parent-side Android app code.

Option 1 was chosen specifically because it requires **zero changes to the existing, already-reviewed-and-merged Android parent app or its wire protocol** — eliminating regression risk to that already-shipped app entirely, at the cost of no other reduction in scope (the PC client still has to implement H.264 decoding itself either way). Options 2 and 3 were both rejected primarily on this same basis (new parent-side app changes and the regression risk that comes with modifying an already-merged component), not on their own technical merits in isolation — option 3's bandwidth efficiency, in particular, was judged not worth the added parent-side complexity and review burden given this app's minimal-security, family-only, low-stakes posture (`docs/spec.md` §7).

## 16. PC Viewer Client Decode Library: Windows Media Foundation Over FFmpeg

For decoding the incoming H.264 stream on the PC client (`docs/pc-client-detailed-spec.md` §5), two options were considered:

1. **FFmpeg bindings (e.g. FFmpeg.AutoGen)** — a common, well-documented cross-platform choice, but pulls in third-party native libraries (`avcodec`/`avutil`/`swscale`) that must be bundled/redistributed alongside the app, raising LGPL redistribution obligations to track and satisfy.
2. **Windows Media Foundation (chosen)** — the OS's own built-in H.264 decoder (hardware-accelerated via the underlying platform decoder, exposed through APIs such as `IMFSourceReader`/Media Foundation Transforms). Requires no third-party native library bundling at all.

Windows Media Foundation was chosen specifically because the PC client is Windows-only (spec.md/`docs/pc-client-spec.md` §5) by design — there is no cross-platform requirement that would otherwise justify FFmpeg's portability, so its only advantage over the OS-native option doesn't apply here, while its licensing/redistribution overhead still would. Choosing Media Foundation avoids third-party licensing/redistribution concerns entirely, at no loss of capability given the target platform is fixed.

## 17. PC Manual Pairing Code: Moderate Entropy Plus Attempt-Rate Limiting, Not Entropy Alone

The new manual pairing code added for PC pairing (`docs/pc-client-spec.md` §6, `docs/pc-client-detailed-spec.md` §3) must be short enough for a human to type reliably, which caps its entropy well below the existing QR flow's `tempToken` (which has no typability constraint). An 8-character alphanumeric code, with visually-ambiguous characters (`0`/`O`, `1`/`I`/`l`) excluded to reduce transcription errors, gives roughly 40 bits of entropy — a moderate but not overwhelming amount on its own.

Rather than trying to raise entropy further (which would work against human-typability, the very reason this code exists — see spec.md §5's "PC without a webcam" gap), the parent-side temporary pairing listener also enforces an attempt-count lockout: after a small, bounded number of failed match attempts within the existing 5-minute pairing window, further attempts are locked out for the remainder of that window. Brute-force resistance is thus achieved through the combination of moderate entropy and attempt-rate limiting, not entropy alone.

This remains consistent with the project's minimal-security, family-only posture (`docs/spec.md` §7) — the underlying threat model is unchanged from the existing QR-based flow (an attacker must already be reachable on the same Tailscale tailnet, and the exposure window is the same 5 minutes); this decision only closes the specific new gap introduced by adding a lower-entropy, human-typable credential alongside the existing high-entropy one, not a broader security hardening effort.

## 18. PC Zoom/Magnifier: Client-Side Only, No New Wire Message

For the PC client's zoom/magnifier feature (`docs/pc-client-spec.md` §3, `docs/pc-client-detailed-spec.md` §10), two shapes were possible:

1. **Purely PC-client-side zoom (chosen)**: the PC client already receives the parent's full-frame H.264 video and already computes a rendered-content-rect → parent-frame coordinate mapping for every tap (`docs/detailed-spec.md` §15). Zooming is implemented as a display/input-mapping transform entirely within the PC client: it renders a magnified sub-region of the already-decoded frame, and computes tap coordinates against that sub-region's mapping instead of the full frame's — reusing the exact same downstream `INPUT_TAP`/`INPUT_SWIPE` messages and parent-side injection path unchanged.
2. **Parent-assisted zoom** (not pursued): the parent could instead be asked to send a higher-resolution crop of just the zoomed region on demand — a new request/response wire message, plus new parent-side encoding logic to produce a second, cropped video stream, touching the already-merged screen-sharing-core encoder.

Option 1 was chosen because it achieves the goal (precise tapping on small UI elements) with zero wire-protocol changes and zero parent-side (Android) code changes — standard digital zoom (magnifying a sub-region of an already-received, already-full-device-resolution frame) is sufficient for the target use case, so option 2's added parent-side complexity buys nothing extra. Consistent with the same "avoid touching the already-merged parent app" principle behind Decision #15's video-transport choice.

## 19. PC Physical Key Relay: Accepted as a Second Explicit Exception to "No Android-Side Changes"

For the PC client's physical key relay feature (`docs/pc-client-spec.md` §3, `docs/pc-client-detailed-spec.md` §11), an Android-side change to the already-merged `ParentAccessibilityService` is unavoidable — unlike the zoom feature (#18 above) or the rest of this PC-client work, individual key-press relay has no purely-client-side equivalent, since the actual key injection must happen on the parent's device, and the existing wire protocol has no message shaped for a discrete key event (only complete-string `INPUT_TEXT`, and gesture-shaped `INPUT_TAP`/`INPUT_SWIPE`).

This was accepted as a second explicit exception to this feature's general "no changes to the parent Android app" design principle (alongside the manual-pairing-code addition to `ParentPairingActivity`, `docs/pc-client-detailed-spec.md` §3) — accepted specifically because the resulting change is small and additive (a new message type plus one new handler branch in an already-merged file), not a redesign of any existing merged behavior, and because the capability (live per-keystroke navigation/editing control) has no viable alternative that avoids touching the parent app at all. This is in contrast to the file/photo-transfer idea (`docs/pc-client-spec.md` §10), which was **not** accepted as a similar exception: key relay is additive to already-merged input-injection code the parent app already has, whereas file transfer would require an entirely new capability (storage/permission handling) with no existing analog to extend.

## 20. PC Client Runs as an Ordinary Window, Not a Tray/Background App

A system-tray/background-resident mode (minimize to tray, keep the session alive while hidden, optionally auto-start on boot — the pattern used by apps like Discord or Skype) was implicitly possible but was not adopted. The PC client instead runs as an ordinary visible desktop window only, with closing the window ending the app like any normal desktop application (`docs/pc-client-spec.md` §3, `docs/pc-client-detailed-spec.md` §2/§8). Chosen to keep the client's behavior simple and predictable — a remote-support session is something the operator (child) actively starts and watches, not something expected to run unattended in the background, so the added complexity of tray/background lifecycle management was judged to have no offsetting benefit for this app's use case.

## 21. PC Physical Key Relay: Focus-Scoped Capture, Not a Global Keyboard Hook

For the physical key relay feature (`docs/pc-client-spec.md` §3, `docs/pc-client-detailed-spec.md` §11), two capture scopes were possible: (a) focus-scoped — `INPUT_KEY` is only sent while the PC client's own video-rendering pane has keyboard focus, the ordinary WPF default; or (b) a global/system-wide keyboard hook that captures key presses regardless of which window has focus on the PC.

Focus-scoped capture (a) was chosen. A global hook (b) would relay keystrokes typed into unrelated PC windows/applications — potentially including sensitive input the operator never intended to send anywhere, let alone to the parent's device — to the parent's device without the operator necessarily realizing it. This is the same category of concern already used to reject clipboard-based text injection in Decision #9: avoid unexpected side effects on data/input the user didn't intend to send. Focus-scoped capture also requires no special implementation effort — it is simply how keyboard events already work in an ordinary WPF window (consistent with Decision #20's "ordinary window" application model), whereas a global hook would require deliberately-added, non-default code to achieve a riskier behavior.

## 22. Simultaneous Parent-Touch and Remote-Input Arbitration: Known, Accepted Gap (Not Solved)

What happens if the parent physically touches their own device while the child is remotely sending input (tap/swipe/text/key relay) is undefined behavior — both input sources are simply interleaved by Android's normal input pipeline, with no arbitration, locking, or conflict resolution. This gap pre-dates this PC-client feature: it already existed, unaddressed, in the already-merged Android app (`docs/spec.md`/`docs/detailed-spec.md`, all tiers) before the PC client was ever proposed. The PC client does not make it any better or worse — it was simply noticed during this feature's specification review, not introduced by it.

Two ways to close the gap were investigated and both were rejected for now:
- **Automatic real-time arbitration**: not achievable without switching the parent's device into Accessibility touch-exploration mode, which would change the parent's entire device interaction model system-wide for as long as it's active — unacceptable given the parent's low tech proficiency (`docs/spec.md` §2) and this project's consistent precedent (Decisions #7, #13) of rejecting anything that adds parent-side complexity or risk beyond the core flow.
- **A manual, child-toggled "lock the parent's touch" overlay**: technically feasible, but not adopted now. Competitor research (AnyDesk/TeamViewer-class remote-support tools) found this class of tool generally does not offer local-input-locking for Android targets either — only for Windows targets, where the OS-level `BlockInput()` API makes it trivial. This is a platform capability asymmetry (Windows has a direct API for this, Android does not), not a shortcut specifically taken by this project.

Left as a known, accepted limitation. Tracked for possible future work (the manual, child-toggled overlay-lock option) in GitHub issue [#8](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/8) — not designed further here. See `docs/pc-client-detailed-spec.md` §12 for the spec-facing note.

## 23. PC Client Decodes Continuously; No Pause/Resume on Minimize or Occlusion

Two options were considered for how the PC client's H.264 decoding should behave when its window is minimized or fully hidden behind other windows: (a) decode continuously regardless of window visibility state; or (b) pause decoding while the window isn't visible, and resume when it becomes visible again, to save CPU/resource usage while the operator isn't actually looking at it.

Option (a) — continuous decoding, no pause/resume logic — was chosen. Option (b) would need to be explicitly undone on window-restore, and resuming decode risks a visible glitch or delay while waiting for the next IDR (keyframe) from the parent's stream, since H.264 with in-band SPS/PPS (`docs/detailed-spec.md` §5 as reused by this client, `docs/pc-client-detailed-spec.md` §5) can only resume clean decoding at a keyframe boundary, not an arbitrary point. The marginal CPU/resource savings from pausing were judged not worth that added implementation complexity and restore-time UX risk, given this project's scale and minimal-security, family-only posture (`docs/spec.md` §7) — consistent with the general preference elsewhere in this feature (Decision #20) for the simpler, more predictable behavior over one with a better-in-theory resource profile but added edge-case risk.

## 24. Exclusive Single-Session Model: One Live Client at a Time, Chosen Over Simultaneous Multi-Viewer

`docs/plans/screen-sharing-core/PLAN.md` §6 (already merged, Tier 1) originally has the parent dial **all** known paired clients simultaneously at share-start time, each independently becoming a live peer with full, equal tap/swipe/text control — left entirely undefined/unarbitrated at the time, since the app's real-world usage was assumed to involve only a single child device connecting at once (spec.md's real-world scenario). Introducing the PC Viewer Client made this assumption stop holding in practice: the same parent being paired to both an Android child and a PC client, with both potentially connecting at once, is now a realistic everyday scenario rather than a theoretical edge case.

Two models were available:
1. **Simultaneous multi-viewer** (the original, undefined-conflict behavior): every paired client that's currently reachable becomes an independent, fully-equal `Live` peer whenever the parent starts sharing. Flexible in principle (any paired device can watch/control at any time), but leaves tap/swipe/text conflicts between simultaneous controllers completely unarbitrated — closely related to, and compounding, the already-tracked parent-touch-vs-remote-input arbitration gap (`docs/pc-client-detailed-spec.md` §12, GitHub issue #8) by adding a second, symmetric class of the same "uncoordinated concurrent input" problem, this time between two remote controllers instead of one remote controller and the parent.
2. **Exclusive single-session model (chosen)**: only one client may hold an active `Live` session with a given parent device at a time. A second paired client attempting to connect while a session is already active is rejected or queued, not accepted as a second concurrent peer.

Option 2 was chosen for predictability and simplicity — extending the same reasoning already applied to the parent-touch-vs-remote-input gap (issue #8): uncoordinated concurrent controllers create conflicts with no clear resolution and no way for either operator to know the other is also acting, which is a worse outcome for a non-tech-savvy parent than simply not allowing it. The flexibility given up (multiple family members simultaneously viewing/controlling the same parent device) was never something spec.md's real-world scenario (a single child assisting a single parent over a phone call at a time, spec.md §1/§2) actually required — so nothing of practical value is lost by disallowing it, while a real and growing (now that PC + Android child can both be paired to the same parent) source of undefined behavior is closed.

This is a decision about the **core app's** own session model (not a PC-client-specific concern) — it applies equally to two simultaneously-connecting Android children, an Android child plus a PC client, or any other combination of paired client types. See `docs/detailed-spec.md` §17 and `docs/spec.md` §5 for the confirmed-spec statements, and `docs/QA.md` ("Exclusive Single-Session Model") for the full resolution record. Actual code-level implementation (rejecting/queuing the second connection attempt in `docs/plans/screen-sharing-core/PLAN.md` and the corresponding rejected-client-side UX in `docs/plans/connection-status-and-resilience/PLAN.md`, both already merged) is tracked as a follow-up in GitHub issue [#9](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/9) — not implemented as part of this decision.
