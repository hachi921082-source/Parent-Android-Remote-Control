# Handoff

<!-- Session-handoff checkpoint: status, what changed, what remains open, recommended next step. Updated at tier boundaries during parallel PLAN implementation (see docs/plans/README.md §8), and otherwise whenever a session needs to hand off context. -->

## Status (2026-09-03)

Tier 0 (Pairing & Onboarding), Tier 1 (Screen Sharing Core), and both Tier 2 features (Connection Status & Resilience, Remote Input Control) are implemented, reviewed, and merged to `master`. Tier 3 (Screen Rotation Handling) — the final tier — is now in progress on branch `feature/screen-rotation-handling`, checked out directly in the main working directory (no worktree: this tier has only one feature, so there is no parallel work to isolate).

- `docs/spec.md`, `docs/detailed-spec.md`, `docs/DECISIONS.md`, `docs/QA.md` — fully confirmed; no known open contradictions.
- `docs/plans/<feature>/PLAN.md` × 5 — all complete, no `docs/QA.md` escalations remaining.
- `docs/plans/README.md` §8 — dependency-tier table; all Tier 0–2 rows read "Merged".
- **UI language policy** (applies to Tier 3 too): every new user-facing string must be added to `values/strings.xml` (English, default/fallback), `values-ja/strings.xml` (Japanese), and `values-zh/strings.xml` (Chinese) with matching keys — no English-only string additions. See `docs/detailed-spec.md` §18 / `docs/DECISIONS.md` #14.
- **Widget appearance** (`ShowScreenWidgetProvider`, implemented in Tier 1) is still an intentional placeholder (generic icon, localized label text); revisit before any real release.
- **No Android device/emulator has been available in any review session so far** (Tier 0 through both Tier 2 features) — every `reviewer-lite` pass has substituted build verification plus manual code/data-flow tracing (and, for connection-status-and-resilience, a standalone JVM harness against the unmodified source) for live on-device testing. This is a standing, recorded limitation, not something to silently re-discover each session.

## What changed this session

- Implemented and merged **Tier 2, Connection Status & Resilience** (worktree `../Parent-Android-Remote-Control-connection-status-and-resilience`, branch `feature/connection-status-and-resilience`): child-side `ConnectionState` state machine, `ChildSessionListener`/`ChildSessionChannel`/`HeartbeatWatchdog`/`ConnectionStateViewModel`, parent-side `ParentStatusMonitor`, and `SessionActivity` (single Activity hosting all four connection states, with a `liveContentContainer` placeholder left for the sibling input-control tier). `reviewer-lite` approved with one non-blocking finding — the `Reconnecting` state doesn't recover to `Live` on a same-socket self-heal (only via a fresh `SESSION_HELLO`) — which the user chose to track as [GitHub issue #4](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/4) rather than fix before merging. PR #5 merged; worktree and local branch removed.
- Implemented and merged **Tier 2, Remote Input Control** (worktree `../Parent-Android-Remote-Control-remote-input-control`, branch `feature/remote-input-control`): `TapInputController`/`TextInputBar`/`LetterboxLayoutCalculator`/`TapMarkerOverlay`, `VideoDecoderView` (wired into the `liveContentContainer` placeholder above), `ParentAccessibilityService`, and the real shared `AccessibilityPermissionMonitor` (Tier 0's local `AccessibilityPermissionCheck` stub swapped out at both call sites and deleted, per the forward-reference note in `docs/plans/README.md` §8). `reviewer-lite`'s first pass found one blocking issue — `TextInputBar` wasn't gated on `ConnectionState.Live` (only on `inputAvailable`), risking a silent-failed-send during the `Reconnecting` window — fixed in a follow-up commit and approved on the second pass. PR #6 merged; worktree and local branch removed.
- Updated `docs/plans/README.md` §8's dependency-tier table: both Tier 2 rows now read "Merged".
- Both Tier 2 features were implemented sequentially (one worktree at a time, reviewed and merged before starting the next), not in parallel — a deliberate change from Tier 2's original cut-both-worktrees-together setup, per the user's preference this session.
- Cut `feature/screen-rotation-handling` off `master`, checked out directly (no worktree — single-feature tier, no parallelism to isolate).

## Dependency tiers (docs/plans/README.md §8)

| Tier | Feature | Status |
|---|---|---|
| 0 | Pairing & Onboarding | **Merged** |
| 1 | Screen Sharing Core | **Merged** |
| 2 | Connection Status & Resilience | **Merged** |
| 2 | Remote Input Control | **Merged** |
| 3 | Screen Rotation Handling | **In progress** — branch `feature/screen-rotation-handling`, checked out directly in the main working directory |

Branch naming convention: `feature/<feature-name>` (unchanged, sixth use).

## Recommended next step

1. In the main working directory (already on `feature/screen-rotation-handling`), invoke `developer-lite` on `docs/plans/screen-rotation-handling/PLAN.md`. Depends on Screen Sharing Core (§2 is implemented as an addition to `ScreenShareForegroundService` itself — same-file edit) and Remote Input Control (`VideoDecoderView`'s dimension-change handling must already exist) — both merged and available.
2. Follow the UI language policy above (ja/en/zh, all three `strings.xml` files) for any new string.
3. Once implemented, have `reviewer-lite` check it against `spec.md`/`detailed-spec.md`/its PLAN.md, confirm the build succeeds, and confirm locale-key parity for any new strings.
4. Get the user's explicit go-ahead before pushing/merging (`dev-workflow.md` §7 step 4 — reviewer approval is not merge authorization).
5. On merge: update `docs/plans/README.md` §8's row to "Merged", delete the feature branch.
6. This is the last tier in the dependency table — once merged, the project's Implementation Order & Progress table is complete. Update `docs/HANDOFF.md` (this file) to reflect that, and check with the user on what comes next (e.g. a broader end-to-end pass, addressing GitHub issue #4, or scoping new work).
