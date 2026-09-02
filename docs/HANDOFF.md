# Handoff

<!-- Session-handoff checkpoint: status, what changed, what remains open, recommended next step. Updated at tier boundaries during parallel PLAN implementation (see docs/plans/README.md §8), and otherwise whenever a session needs to hand off context. -->

## Status (2026-09-03)

Tier 0 (Pairing & Onboarding) and Tier 1 (Screen Sharing Core) are implemented, reviewed, and merged to `master`. UI localization (ja/en/zh) is now a recorded spec decision and Tier 0's strings have been retrofitted; Tier 1's strings were authored correctly from the start. Both Tier 2 worktrees (Connection Status & Resilience, Remote Input Control) are open and ready for implementation, in parallel.

- `docs/spec.md`, `docs/detailed-spec.md`, `docs/DECISIONS.md`, `docs/QA.md` — fully confirmed; the localization gap found during a Tier 0 review (all UI strings English-only, language never specified) has been resolved and recorded (`docs/DECISIONS.md` #14, `docs/detailed-spec.md` §18, `docs/QA.md`'s "UI Language / Locale Support" entry). No other known open contradictions.
- `docs/plans/<feature>/PLAN.md` × 5 — all complete, no `docs/QA.md` escalations remaining.
- `docs/plans/README.md` §8 — dependency-tier table for parallel implementation (see below).
- **UI language policy** (new, applies to all remaining tiers): every new user-facing string must be added to `values/strings.xml` (English, default/fallback), `values-ja/strings.xml` (Japanese), and `values-zh/strings.xml` (Chinese) with matching keys — no English-only string additions. Selection is automatic via the device's OS system locale; no in-app manual language picker. See `docs/detailed-spec.md` §18 / `docs/DECISIONS.md` #14.
- **Widget appearance** (`ShowScreenWidgetProvider`, implemented in Tier 1) is intentionally a placeholder (generic icon, localized label text) — the user deliberately deferred the actual icon/label design decision; revisit before any real release, not urgent for further tier implementation.

## What changed this session

- Implemented and merged **Tier 0 (Pairing & Onboarding)** end-to-end (developer-lite → reviewer-lite → user go-ahead → PR #1 → merge). First push of `master`/branches to `origin` (previously nothing was pushed).
- User raised, and this session addressed, a documentation-gap audit request: found UI language/locale was never specified anywhere in the spec despite the target parents being non-tech-savvy and on Japanese-market devices, and that Tier 0 had consequently shipped 100%-English strings. Discussed with the user directly; decided: **ja/en/zh, automatic OS-system-locale detection, English fallback**. Recorded via `designer-lite` following the same QA.md→DECISIONS.md→spec.md/detailed-spec.md pattern already used for the process-kill-UX decision (`docs/QA.md` new "UI Language / Locale Support" entry RESOLVED, `docs/DECISIONS.md` #14, `docs/spec.md` §6 bullet, `docs/detailed-spec.md` new §18 plus cross-references from the four existing "wording left to implementation" bullets in §5/§11/§14/§17). Pushed to `master`.
- Retrofitted Tier 0's strings for the new language policy: `developer-lite` added `values-ja/strings.xml` and `values-zh/strings.xml` (all ~54 keys translated, `values/strings.xml` unchanged as the English fallback) on branch `chore/localize-tier0-strings`, verified build, merged via PR #2, synced into the (at the time still in-progress) Tier 1 worktree.
- Implemented and merged **Tier 1 (Screen Sharing Core)**: `developer-lite` implemented the widget/trampoline 2-tap flow, capture/encode pipeline, parent-dials-child session establishment, wake-lock lifecycle, and two-layer background-kill mitigation, plus extended the shared `net` package with live-session messages and added new strings across all three locale files from the start. `reviewer-lite`'s first pass found one blocking issue (`SCHEDULE_EXACT_ALARM` called without a `canScheduleExactAlarms()`/try-catch guard — risked crashing the just-started foreground service on Android 13+) and one non-blocking-but-real gap (`onDestroy()` not releasing the wake lock/capture resources on a Service-only OS-triggered recreation, risking a wake-lock leak). `developer-lite` fixed both; `reviewer-lite`'s second pass approved with no new issues. User gave explicit merge go-ahead. PR #3 merged, `docs/plans/README.md` §8 Tier 1 row updated to "Merged", worktree removed, local branch deleted.
- Cut both Tier 2 worktrees (they touch different files, no conflict, so both were opened together per `docs/plans/README.md` §8's own note):
  - `../Parent-Android-Remote-Control-connection-status-and-resilience` on branch `feature/connection-status-and-resilience`
  - `../Parent-Android-Remote-Control-remote-input-control` on branch `feature/remote-input-control`
  - Both cut off `master` at commit `d36803b`.

## Dependency tiers (docs/plans/README.md §8)

| Tier | Feature | Status |
|---|---|---|
| 0 | Pairing & Onboarding | **Merged** |
| 1 | Screen Sharing Core | **Merged** |
| 2 | Connection Status & Resilience | **Worktree open** (`../Parent-Android-Remote-Control-connection-status-and-resilience`, branch `feature/connection-status-and-resilience`) — not yet implemented |
| 2 | Remote Input Control | **Worktree open** (`../Parent-Android-Remote-Control-remote-input-control`, branch `feature/remote-input-control`) — not yet implemented |
| 3 | Screen Rotation Handling | Not started (blocked on Tier 2 merge — both rows) |

Branch naming convention: `feature/<feature-name>` (unchanged, fourth/fifth use).

## Recommended next step

1. Two parallel sessions, one per Tier 2 worktree:
   - In `feature/connection-status-and-resilience`, invoke `developer-lite` on `docs/plans/connection-status-and-resilience/PLAN.md`. Depends on Tier 0 (`PairingEntry`/`TailscaleAddressResolver`/`PairingListScreen`) and Tier 1 (`ParentStatusMonitor` hands off to `ParentSessionServer`, started alongside `ScreenShareForegroundService` — both now merged and available).
   - In `feature/remote-input-control`, invoke `developer-lite` on `docs/plans/remote-input-control/PLAN.md`. Depends on Tier 1 (`ParentAccessibilityService` binds to `ScreenShareForegroundService` via a companion reference). This is also the feature that finally builds the real shared `AccessibilityPermissionMonitor` — Tier 0's local stub (`pairing/AccessibilityPermissionCheck`) should be swapped out for it once this lands (not necessarily by this feature's own PLAN.md — check whether that swap is in scope here or needs a small separate follow-up).
   - Both sessions should read `docs/plans/README.md` first, and both must follow the UI language policy above (ja/en/zh, all three `strings.xml` files) for any new string.
2. Once each is implemented, have `reviewer-lite` check it against `spec.md`/`detailed-spec.md`/its PLAN.md, confirm the build succeeds, and confirm locale-key parity for any new strings.
3. Get the user's explicit go-ahead before pushing/merging each one independently (`dev-workflow.md` §7 step 4 — reviewer approval is not merge authorization; approval for one does not cover the other).
4. On each merge: update `docs/plans/README.md` §8's corresponding row to "Merged", and sync the other still-open Tier 2 worktree (`git merge master --ff-only`) so it has the other feature's merged code.
5. Once **both** Tier 2 rows read "Merged": Tier 3 (Screen Rotation Handling) becomes eligible — cut its worktree next. `docs/HANDOFF.md` (this file) should be updated again at that point, per the tier-boundary-only convention — not after only one of the two Tier 2 rows merges.
