# PLAN: PC Client — App Shell / Bootstrap

<!-- Implementation plan. Covers pc-client-detailed-spec.md §1 (Environment/Prerequisites), §2
(single-instance enforcement, ordinary-window application model), §9 (UI localization
infrastructure). Part of the PC Viewer Client feature — see docs/pc-client-spec.md /
docs/pc-client-detailed-spec.md. This plan underpins every other docs/plans/pc-client/ plan;
implement it first as a practical matter (not a tracked build-order requirement). -->

## Index

1. Scope
2. Project Scaffold
3. Application Model: Ordinary Window, No Tray
4. Single-Instance Enforcement
5. UI Localization Infrastructure
6. Startup Sequence
7. Interactions With Other Features
8. Open Items

## 1. Scope

Everything needed before any feature-specific screen can exist: the WPF project itself, the main window shell, single-instance enforcement, and the localization mechanism every other plan's UI text depends on. Does not include any pairing, video, input, or session logic — those are owned by their own plans and are hosted inside the shell this plan builds.

## 2. Project Scaffold

Per `docs/plans/pc-client/README.md` §3:

- `pc-client/FamilyRemote.PcClient.sln`, single WPF project `pc-client/FamilyRemote.PcClient/FamilyRemote.PcClient.csproj`.
- `TargetFramework=net8.0-windows`, `UseWPF=true`, `Nullable=enable`.
- Minimum OS: Windows 10 (`docs/pc-client-detailed-spec.md` §1) — no runtime OS-version gate is coded (net8.0-windows's own minimum supported Windows version already satisfies this; no lower-bound check needed beyond what the .NET runtime itself requires).
- No NuGet dependencies are needed for this plan specifically; other plans (video-and-session, in particular) add their own.
- `App.xaml` / `App.xaml.cs` — application entry point, owns startup sequence (§6).
- `MainWindow.xaml` / `MainWindow.xaml.cs` — the single top-level window; hosts a content-switching root (e.g. a `ContentControl` or named `Grid` with visibility-toggled children) whose actual content is supplied by `docs/plans/pc-client/connection-state-ui/PLAN.md` (pairing selection → waiting → live → reconnecting → disconnected). This plan only owns the window chrome itself (title, icon, min size, resizability), not its content.

## 3. Application Model: Ordinary Window, No Tray

Per `docs/pc-client-spec.md` §3 / `docs/DECISIONS.md` #20:

- `MainWindow` is a normal, resizable, taskbar-visible window — no `ShowInTaskbar="False"`, no `WindowStyle="ToolWindow"`, no tray icon (`NotifyIcon`) anywhere in the project.
- No auto-start-on-boot registration (no Registry `Run` key write, no Startup-folder shortcut) — the app only runs when the operator explicitly launches it.
- Closing the window (`MainWindow.Closing` event) ends the app unconditionally: any active session is torn down (delegates to `docs/plans/pc-client/connection-state-ui/PLAN.md`'s session-teardown method, called from this handler) and the process exits normally — no "minimize to tray and keep running" branch exists to design for.
- Minimum window size: an implementation-level default (e.g. `MinWidth=480`, `MinHeight=360`) large enough to keep the video view and status/input controls usable — no product-mandated value (`docs/pc-client-detailed-spec.md` §5 explicitly leaves this open).

## 4. Single-Instance Enforcement

Per `docs/pc-client-detailed-spec.md` §2 (required):

- `App.OnStartup` creates a named `Mutex(initiallyOwned: true, name: "Global\\FamilyRemote.PcClient.SingleInstance", out bool createdNew)`.
- If `createdNew == false` (an instance is already running): show a brief `MessageBox` ("Family Remote is already running.") and call `Shutdown()` immediately, without creating `MainWindow` at all. Chosen over bring-existing-window-to-foreground specifically because that would require inter-process signaling (a named pipe or `FindWindow`/`SetForegroundWindow` P/Invoke) for a low-value convenience — `docs/pc-client-detailed-spec.md` §2 explicitly leaves the exact second-launch behavior open as an implementation choice, and the only fixed requirement is that a second instance must not run concurrently, which this satisfies with the simplest possible mechanism.
- The `Mutex` handle is held for the process's entire lifetime (a static field on `App`, never explicitly released — released implicitly on process exit) so a crashed/killed process doesn't leave a stale lock (OS-owned mutexes are automatically abandoned/released when their owning process terminates, regardless of cause).

## 5. UI Localization Infrastructure

Per `docs/pc-client-detailed-spec.md` §9 (resolved — same ja/en/zh, OS-locale-auto-detect, English-fallback policy as the Android app, `docs/DECISIONS.md` #14 extended):

- `Resources/Strings.resx` (default, authored in English — the fallback for any locale outside ja/en/zh, mirroring Android's unqualified `values/strings.xml`), `Resources/Strings.ja.resx`, `Resources/Strings.zh.resx` — WPF's standard satellite-resource-per-culture mechanism, `ResXFileCodeGenerator` (or `PublicResXFileCodeGenerator`) generating a strongly-typed `Strings` accessor class.
- Culture resolution: `App.OnStartup`, before `MainWindow` is constructed, reads `CultureInfo.CurrentUICulture.TwoLetterISOLanguageName` and maps it: `"ja"` → Japanese resources, `"zh"` → Chinese resources, anything else → default (English) — this mapping is a one-line `switch`, not a full locale-negotiation library, since only these three buckets plus a fallback are ever needed (`docs/pc-client-detailed-spec.md` §9). Sets `Thread.CurrentThread.CurrentUICulture` (and `CurrentThread.CurrentCulture` for date/number formatting consistency) to the resolved culture before any XAML binding to `Strings.*` evaluates.
- No in-app manual language picker (confirmed, `docs/QA.md` "PC Viewer Client: Does the ja/en/zh UI-Language Policy Apply to Its UI Too?") — the culture is fixed for the process's lifetime once resolved at startup; changing the Windows display language and relaunching is the only way to see a different language, consistent with there being no runtime re-resolution logic anywhere in this plan.
- XAML usage pattern: string keys are referenced via a lightweight static-resource binding (e.g. `Text="{x:Static properties:Strings.SomeKey}"` for text that doesn't need to react to anything at runtime — sufficient here since culture never changes mid-process, so a full `MarkupExtension`/dynamic-rebinding mechanism is unnecessary complexity).
- Each other plan that introduces user-facing text adds its own keys to all three `.resx` files (English content authored directly by that plan's implementation; `.ja`/`.zh` translations are a translation-content task, not a design decision — same expectation already implicit in how the Android app's `values-ja`/`values-zh` files were populated for the five merged tiers).

## 6. Startup Sequence

1. `App.OnStartup`: acquire single-instance mutex (§4) — exit immediately if already running.
2. Resolve and apply UI culture (§5).
3. Construct and show `MainWindow`.
4. `MainWindow.Loaded`: hand off to `docs/plans/pc-client/connection-state-ui/PLAN.md`'s entry point — reads the PC-side pairing entry list (`docs/plans/pc-client/pairing-pc-side/PLAN.md`) and shows either the manual-pairing screen (zero entries), the single-entry auto-selected `WaitingForParent` screen, or the pairing-selection list (multiple entries), per `docs/pc-client-detailed-spec.md` §6.

## 7. Interactions With Other Features

- **pairing-pc-side / connection-state-ui**: this plan's `MainWindow` content area is where those plans' screens are hosted; this plan does not itself contain pairing or session logic.
- **video-and-session (decode)**: that plan's `VideoView` control lives inside the `Live` state's content, hosted the same way.
- All other plans consume `Resources/Strings` and `Common/Constants` (the latter defined per `docs/plans/pc-client/README.md` §6, physically created by whichever plan is implemented first that needs it — this plan creates the file with its own §4/§5-relevant constants if any exist, but most constants belong to other plans' domains).

## 8. Open Items

None. All product-level requirements in `docs/pc-client-detailed-spec.md` §1/§2/§9 are either fixed values (single-instance required, no tray, ja/en/zh policy) or explicitly left open as implementation details this plan resolves directly (second-instance behavior, minimum window size, exact culture-mapping mechanism).
