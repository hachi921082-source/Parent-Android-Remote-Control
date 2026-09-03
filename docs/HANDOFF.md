# Handoff

<!-- Session-handoff checkpoint: status, what changed, what remains open, recommended next step. Updated at tier boundaries during parallel PLAN implementation (see docs/plans/README.md §8), and otherwise whenever a session needs to hand off context. -->

## Index

1. Status
2. PC Viewer Client — what's decided
3. Dependency tiers (docs/plans/README.md §8)
4. Recommended next step

## 1. Status (2026-09-03)

The original five-feature/four-tier app (Tier 0–3) remains fully implemented, reviewed, and merged to `master` — unchanged. **The PC Viewer Client is fully specced and fully planned.** Of its 11 PLAN.md files, the **two Android-side ones are now implemented, reviewed, and merged** (see below); the **nine PC-side ones (new C#/.NET codebase) still have zero code written** — no `pc-client/` folder exists yet.

- Original app (Tier 0–3): unchanged, still all merged. See "Dependency tiers" below.
- **PC Viewer Client spec: complete.** `docs/pc-client-spec.md` + `docs/pc-client-detailed-spec.md`. Read both directly for full detail — do not re-derive from this summary.
- **Android-side PC-support plans: implemented, reviewed, merged (2026-09-03)**:
  - `docs/plans/pc-pairing-code-support/PLAN.md` — manual pairing code (`ParentPairingActivity`) + `PairingServer` multi-attempt/independent-lockout redesign. Implemented via `developer-lite` in its own worktree, reviewed by `reviewer-lite` (approved, clean build, no findings), merged as [PR #10](https://github.com/hachi921082-source/Parent-Android-Remote-Control/pull/10).
  - `docs/plans/pc-key-relay-support/PLAN.md` — `INPUT_KEY` wire message + `ParentAccessibilityService` handler for relayed physical keys. Implemented via `developer-lite` in its own worktree (in parallel with the above — no file overlap), reviewed by `reviewer-lite` (approved, clean build, no findings), merged as [PR #11](https://github.com/hachi921082-source/Parent-Android-Remote-Control/pull/11).
  - The `PairingServer` behavior-change side effect on the existing QR-only flow (shared listener now tolerates up to 5 failed attempts per channel, uniformly, instead of closing on first failure) was explicitly flagged and confirmed with the user before implementation — accepted as specified, no PLAN.md rewrite needed. See `docs/QA.md`/`docs/DECISIONS.md` if the reasoning needs to be re-derived later.
- **PC-side plans (9 files, `docs/plans/pc-client/`)**: still **not started**. New C#/.NET/WPF codebase.
- **New follow-up feature planned this session: Single-Session Enforcement** (GitHub issue #9). `docs/plans/single-session-enforcement/PLAN.md` makes the already-merged Tier 1/2 code enforce "only one client may hold an active `Live` session at a time" — a gap surfaced by PC-client planning (a parent now realistically connects to both an Android child and a PC client at once). Two parts:
  - §3 (parent-side arbitration correctness fix): a strict bugfix, no product decision needed, not yet implemented.
  - §5/§6 (losing-client UX): the reject/queue/silent-drop product question is now **resolved** — see `docs/QA.md` ("Single-Session Rejection Mechanism"): **explicit "busy" notice, then terminal give-up** (no auto-retry). Not yet implemented.
  - **Same-file conflict note**: §5 adds `SESSION_BUSY(12)` to `net/MessageType.kt` — the same file `pc-key-relay-support` just added `INPUT_KEY(11)` to (already merged, so this is no longer a live conflict — the next implementer just needs `MessageType.kt` at its current, merged state).
  - Added as Tier 4 in `docs/plans/README.md` §8 (unlike the PC-client plans, which stay deliberately excluded from that table since they don't touch already-merged files).
- **Two GitHub issues opened during PC-client planning, tracking gaps in the CORE (already-merged) app**:
  - [#8](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/8) — undefined behavior when the parent physically touches their device while remote input arrives simultaneously. Deliberately left as an accepted limitation, not scheduled.
  - [#9](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/9) — exclusive single-session enforcement. Now has a PLAN.md (`single-session-enforcement`, see above); implementation not yet started.
- **UI language policy** (ja/en/zh, OS-locale-driven): applies project-wide. `pc-pairing-code-support` added new strings to all three `values/`/`values-ja/`/`values-zh/strings.xml` (confirmed genuinely translated, not copy-pasted, by reviewer-lite); `pc-key-relay-support` introduced no new UI strings; the future PC client uses `.resx` (not yet built).
- **Widget appearance** (`ShowScreenWidgetProvider`, Tier 1) still an intentional placeholder; unrelated to this session's work.
- **Standing test-environment limitation**: no Android device/emulator available in any session so far — build-verified/code-traced only (both PRs' reviewer-lite passes ran a real Gradle build but could not do on-device verification). Expect the same for a Windows dev/test environment once PC-client implementation starts, unless one becomes available.
- **Open follow-up, unrelated to this session**: [GitHub issue #4](https://github.com/hachi921082-source/Parent-Android-Remote-Control/issues/4) — `Reconnecting` doesn't recover to `Live` on same-socket self-heal.
- **Windows long-path note for future worktree cleanup**: `git worktree remove` on this repo can fail with "Filename too long" on Windows once Gradle build output exists in the worktree (deeply nested Kotlin inner-class `.class` paths under `app/build/intermediates/...`). Git still unregisters the worktree internally even when the filesystem delete fails. Fix: mirror an empty directory onto the leftover folder with `robocopy <empty> <leftover> /MIR` (handles long paths where `Remove-Item`/`rd` don't), then delete the now-empty folder.

## 2. PC Viewer Client — what's decided (summary; read `docs/pc-client-spec.md`/`docs/pc-client-detailed-spec.md` for full detail, and the 11 PLAN.md files for implementation-level detail)

- Additional client type alongside the Android child app (not a replacement); Windows-only, Windows 10+; C#/.NET, WPF; single portable exe, no installer; single-instance-enforced (named mutex); ordinary window app (no tray/background, no pause-on-minimize).
- Video: parent's Android app is completely unchanged — reuses the existing H.264 wire protocol as-is; PC client decodes via Windows Media Foundation (not FFmpeg — licensing), low-latency configured (`CODECAPI_AVLowLatencyMode` or equivalent).
- Pairing: `ParentPairingActivity` gains a small addition — shows a plain-text host address plus a new 8-character Crockford Base32 manual code (I/L/O/U excluded, case-insensitive with full alias correction, independent rate-limit counter from the QR `tempToken`'s, threshold=5) alongside the existing QR. **Implemented and merged** (PR #10). This plus the key-relay addition below were the only two Android-side code changes this feature needed.
- New features beyond parity with the Android child: zoom/magnifier (mouse-wheel, capped to fit window, dynamically re-clamped on resize, no pan needed — not yet implemented, PC-side), physical key relay (new `INPUT_KEY` wire message, in `docs/plans/README.md` §4's shared protocol table; arrows/Backspace/Delete/Enter/Tab/Escape; Escape→`GLOBAL_ACTION_BACK`; focus-scoped capture via WPF `PreviewKeyDown`, not global — **parent-side handler implemented and merged**, PR #11; PC-side sender not yet implemented).
- Deliberately deferred (`docs/pc-client-spec.md` §10 Future Extensions): multi-parent-tabs, PC→parent file transfer, manual touch-lock overlay (issue #8).
- Everything else (connection states/timeouts, tap-marker behavior, view-only mode, status summary, rotation handling, coordinate scaling, pairing-selection-on-launch flow, post-Disconnected destination) reuses the Android child's existing behavior/wire protocol unchanged.

## 3. Dependency tiers (docs/plans/README.md §8)

| Tier | Feature | Status |
|---|---|---|
| 0 | Pairing & Onboarding | **Merged** |
| 1 | Screen Sharing Core | **Merged** |
| 2 | Connection Status & Resilience | **Merged** |
| 2 | Remote Input Control | **Merged** |
| 3 | Screen Rotation Handling | **Merged** |
| 4 | Single-Session Enforcement | Not started (planned; rejection-mechanism decision resolved, see §1 above) |

This table does not, and per earlier decision will not, include the PC Viewer Client's 9 PC-side plans (`docs/plans/pc-client/`) — they don't touch already-merged files. The two Android-side PC-support plans (`pc-pairing-code-support`, `pc-key-relay-support`) were likewise kept out of this table while in flight (different-codebase-area convention) and are now both Merged (see §1).

Branch naming convention used throughout: `feature/<feature-name>`.

## 4. Recommended next step

1. **PC-side implementation** (`docs/plans/pc-client/`, 9 PLAN.md files, new C#/.NET/WPF codebase, zero code so far): `docs/plans/pc-client/README.md`'s own note suggests App Shell first (it's the foundation the other PC-side plans build on) — check that file for the suggested build order. Note: `reviewer-lite`'s build-verification step will need a `dotnet build`-equivalent, not `./gradlew`.
2. **Single-Session Enforcement** (Tier 4, `docs/plans/single-session-enforcement/PLAN.md`): both the arbitration fix (§3) and the now-decided reject-UX (§5/§6) are unblocked and can be implemented via the normal `developer-lite` → `reviewer-lite` → user go-ahead → merge flow. Same `net/MessageType.kt` file `pc-key-relay-support` touched — no longer a live conflict since that merged, just start from current `master`.
3. Lower priority, unchanged from before: full real-device end-to-end pass on the original app, issue #4, widget appearance placeholder.
