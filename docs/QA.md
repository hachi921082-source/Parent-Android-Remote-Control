# Questions for the User

<!-- Unresolved questions requiring the user's own judgment, appended by designer-lite. -->

## Initial spec draft (2026-09-02) — RESOLVED 2026-09-02

Background: This project's early skeleton guessed the app was a parental-control/monitoring app based on the folder name alone. The user has since provided the real concept (Android-to-Android family remote support: child views/controls parent's device via MediaProjection) and answered all items below. All are now reflected in `docs/spec.md` and `docs/detailed-spec.md`; nothing here remains open.

1. **Feature scope** — Resolved. Screen viewing + tap/swipe control + text input, via MediaProjection screen sharing. See `docs/spec.md` §3.
2. **App architecture** — Resolved. Single combined app, role switched via tab, default tab = parent mode. See `docs/spec.md` §4.
3. **Device/role model** — Resolved. App-level MediaProjection-based screen sharing (not Device Owner/MDM-based). Parent device shares its screen; child device views/controls it. See `docs/spec.md` §1–§2.
4. **Target Android API level / min SDK** — Resolved. Android 8.0+ minimum, driven by Tailscale's Android app requirement. See `docs/spec.md` §6, `docs/detailed-spec.md` §7.
5. **Distribution intent** — Resolved. Personal/family use only; minimal security posture, not designed for public distribution. See `docs/spec.md` §7.
6. **Reference apps / design inspiration** — Resolved (implicitly, by the concrete design provided). No specific reference app was designated; TeamViewer-style remote support was used as a descriptive analogy only, not a feature-parity target.

## Open Verification Items (not spec decisions — tracked here for visibility only)

These are not questions requiring the user's design judgment; they are unverified real-device behaviors to confirm during/after implementation. Tracked in `docs/detailed-spec.md` (§1, §6) and `docs/DECISIONS.md` (§6) as the source of truth; listed here only so they aren't lost.

- AQUOS Wish4 (mother's device) background-app behavior is unverified — no device-specific mitigation steps are defined yet, unlike the Nubia S2.
- Whether MyOS (Nubia S2) kills the background service based on idle time or loss of foreground display is unverified. The app's auto-reconnect mitigation limits impact either way, but does not eliminate the risk.
- Whether a WorkManager/AlarmManager-triggered restart (detailed-spec.md §6, the defense-in-depth periodic self-check added alongside the foreground service's own in-process reconnect logic) actually succeeds in practice on MyOS is unverified. This is distinct from the item above: that item concerns what *triggers* the kill; this one concerns whether the *mitigation* mechanism itself (the scheduled wake-up used to detect and restart a killed service) can survive or run at all under MyOS's aggressiveness, given MyOS is known to also restrict background scheduled tasks in general, not just running foreground services. Needs verification on the actual Nubia S2 device.

## MediaProjection Resume-After-Process-Kill UX (added 2026-09-03) — RESOLVED 2026-09-03

Background: detailed-spec.md §6 states that after a service kill, "impact is limited to a brief interruption rather than a full session loss requiring the parent to redo the 3-tap (or 2-tap) start flow." On the father's device (MyOS, aggressive background killer per §1), the kind of kill MyOS performs terminates the entire app process, not just a graceful `Service.onDestroy()` while the process itself survives. Android's MediaProjection grant is bound to the process holding it; once that process is killed, the grant is invalidated, and there is no OS API to silently resume screen capture without the parent physically tapping the OS's own "Start Now?" confirmation dialog again — this is a hard platform constraint of the same nature already established in DECISIONS.md #1 (the dialog is unavoidable and, per detailed-spec.md §13 step 2, reappears "every session"). The WorkManager/AlarmManager-based restart that §6 already describes as defense-in-depth can restart the killed process and re-establish the network connection to the child device, but it cannot restore the actual screen-capture grant without that tap — this holds regardless of restart strategy.

So §6's literal wording ("brief interruption... NOT requiring the parent to redo the tap flow") overpromises for the specific MyOS full-process-kill scenario it exists to describe. It is only achievable for lesser cases: a Service stopping while the process itself survives, or a transient network/Tailscale disconnect.

Question: what should actually happen, product-wise, when a full MyOS process-kill occurs mid-session? Three options were identified (raised by developer-lite while drafting the `screen-sharing-core` and `connection-status-and-resilience` PLAN.md files):
- **(A) No special affordance.** After a full-process-kill, sharing has simply stopped; the parent must notice (most likely because the child, already on the call, tells them) and manually re-tap the home-screen widget — the existing 2-tap flow (§4). Simplest, but doesn't literally deliver "brief interruption without redoing the tap flow."
- **(B) Add a "tap to resume sharing" notification.** Triggered by the WorkManager/AlarmManager wake-up (§6) when it detects the process was killed mid-session, a high-priority parent-side notification appears; one tap re-triggers the MediaProjection dialog (still one unavoidable tap per Decision #1). Minimizes the parent's extra steps in this specific failure moment, but introduces a new self-service, parent-facing affordance — a category of design this project has previously leaned away from (see DECISIONS.md #7: an auto-dial-call idea was rejected specifically because the parent's tech proficiency was judged too low to reliably rely on for anything beyond the core minimal flow).
- **(C) Formalize (A) by correcting the spec wording.** Same end-state as (A) — no new UI — but §6's text is corrected to accurately scope the "brief interruption" promise to non-process-death cases only (Service-survives-kill, or a transient network drop), rather than the MyOS full-process-kill case it currently appears to cover.

designer-lite's leaning: (C). It resolves the wording inaccuracy without adding new parent-facing UI, consistent with spec.md §2 ("any design requiring an extra parent-side action beyond the core share-start flow is treated as high-risk") and the precedent set in DECISIONS.md #7 against adding automated/self-service affordances aimed at the parent. That said, this is a leaning, not a decision on the user's behalf — in particular, whether option B's reduced step-count in a real failure moment is worth the added UI/complexity and the deviation from the DECISIONS.md #7 precedent is a product-philosophy call properly left to the user.

Blocks: developer-lite cannot finalize the "service killed mid-session, process died" handling in `docs/plans/screen-sharing-core/PLAN.md` and `docs/plans/connection-status-and-resilience/PLAN.md` — specifically, what the app presents to the parent after such a kill, and whether any new notification/UI component needs to be built for it — until this is resolved. `detailed-spec.md` §6's wording is left as-is until the user decides, since editing it now (even just to narrow its scope) would itself bake in a provisional choice among A/B/C.

**Resolution**: user chose (C) — no dedicated recovery UI; `detailed-spec.md` §6's wording has been corrected to scope the "brief interruption" promise to process-survives/transient-network cases only. See `docs/DECISIONS.md` #13. developer-lite may now finalize the two blocked PLAN.md sub-items using option (C)/(A)'s end-state (no new notification/UI component for this case).
