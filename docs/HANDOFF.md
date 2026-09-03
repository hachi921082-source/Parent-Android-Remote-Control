# Handoff

<!-- Session-handoff checkpoint: status, what changed, what remains open, recommended next step. Updated at tier boundaries during parallel PLAN implementation (see docs/plans/README.md §8), and otherwise whenever a session needs to hand off context. -->

## Status (2026-09-03)

All five features across all four tiers (0–3) are implemented, reviewed, and merged to `master`. The `docs/plans/README.md` §8 Implementation Order & Progress table is now fully "Merged" — the initial feature set defined by `docs/spec.md`/`docs/detailed-spec.md` is complete.

- `docs/spec.md`, `docs/detailed-spec.md`, `docs/DECISIONS.md`, `docs/QA.md` — fully confirmed; no known open contradictions.
- `docs/plans/<feature>/PLAN.md` × 5 — all complete and merged, no `docs/QA.md` escalations remaining.
- **UI language policy** (applies to any future new string): every new user-facing string must be added to `values/strings.xml` (English, default/fallback), `values-ja/strings.xml` (Japanese), and `values-zh/strings.xml` (Chinese) with matching keys. See `docs/detailed-spec.md` §18 / `docs/DECISIONS.md` #14.
- **Widget appearance** (`ShowScreenWidgetProvider`, Tier 1) is still an intentional placeholder (generic icon, localized label text); revisit before any real release.
- **Standing test-environment limitation**: no Android device or emulator has been available in any session across all four tiers. Every `reviewer-lite` pass has substituted build verification plus manual code/data-flow tracing (and, once, a standalone JVM harness against unmodified source) for live on-device testing. This is a recorded, project-wide gap, not something to re-discover per session.
- **Open follow-up**: [GitHub issue #4](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/4) — the child-side `Reconnecting` state doesn't recover to `Live` on a same-socket self-heal (only via a fresh `SESSION_HELLO`). Non-blocking, found during Tier 2 review, deliberately deferred by the user rather than fixed before merge.

## What changed this session

- Implemented and merged **Tier 2, Connection Status & Resilience** and **Tier 2, Remote Input Control** — see prior HANDOFF.md revision (git history) for full detail if needed; both merged via PR #5 and PR #6, sequentially (one worktree at a time, reviewed and merged before starting the next) rather than in parallel, per the user's preference this session.
- Implemented and merged **Tier 3, Screen Rotation Handling** (branch `feature/screen-rotation-handling`, checked out directly in the main working directory — no worktree, since this tier has only one feature and no parallel work to isolate): parent-side `ScreenShareForegroundService` now registers a `DisplayManager.DisplayListener` (filtered to `Display.DEFAULT_DISPLAY`) and reconfigures the capture encoder in place on a display-dimension change, reusing the same `MediaProjection` grant and never touching the network/session layer. Retired the "interim measure pending Tier 3" comments in `child/SessionActivity.kt` and `docs/plans/connection-status-and-resilience/PLAN.md` — `configChanges` + `VideoDecoderView`'s existing `onSizeChanged`-driven reflow was confirmed as the final child-side design (no new child-side code needed). `reviewer-lite` approved with two non-blocking hardening notes (an API 26-30 `createVirtualDisplay`-failure edge case; a self-healing display-metrics-source mismatch) — neither blocks merge, both left as-is. PR #7 merged; local branch deleted.
- `docs/plans/README.md` §8's dependency-tier table: all rows (Tier 0 through Tier 3) now read "Merged".

## Dependency tiers (docs/plans/README.md §8)

| Tier | Feature | Status |
|---|---|---|
| 0 | Pairing & Onboarding | **Merged** |
| 1 | Screen Sharing Core | **Merged** |
| 2 | Connection Status & Resilience | **Merged** |
| 2 | Remote Input Control | **Merged** |
| 3 | Screen Rotation Handling | **Merged** |

Branch naming convention used throughout: `feature/<feature-name>`.

## Recommended next step

The originally planned feature set is complete. No PLAN.md remains to implement. Options for what comes next (none started; ask the user which to pursue):

1. **Full real-device end-to-end pass** — `reviewer-lite` has flagged this at every tier boundary: no Android device/emulator has been available in any session, so nothing has been live-tested yet, only build-verified and code-traced. Recommended flow once a device/emulator is available: pairing → share-start → live video → remote tap/swipe/text input → simulated connection loss/reconnect → **actual physical rotation** of the parent device mid-session (ideally on more than one API level/OEM skin — detailed-spec §2/§13 specifically calls out the ZTE Nubia S2 as an idiosyncratic real target device) → accessibility-permission revoke/restore.
2. **Address GitHub issue #4** (Reconnecting-state self-heal gap, see above) — small, well-scoped, already has a suggested fix recorded in the issue.
3. **Widget appearance** (`ShowScreenWidgetProvider`) — still a deliberate placeholder (generic icon/label); the user said this isn't urgent but should be revisited before any real release.
4. **Scope new work** — if the user has additional features/spec changes in mind beyond the original five, that would start a fresh `designer-lite` → `docs/spec.md`/`docs/detailed-spec.md` update → new `PLAN.md` cycle per `docs/plans/README.md`'s established process.
