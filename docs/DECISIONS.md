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
