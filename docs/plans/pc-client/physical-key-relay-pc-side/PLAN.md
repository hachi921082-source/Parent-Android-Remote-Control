# PLAN: PC Client — Physical Key Relay (PC Side)

<!-- Implementation plan. Covers pc-client-detailed-spec.md §11 (PC-side half: focus-scoped
capture, INPUT_KEY send, WPF PreviewKeyDown implementation). Part of the PC Viewer Client
feature. The Android-side half (ParentAccessibilityService handler, per-key injection
mechanism) is a SEPARATE plan in the flat layout: docs/plans/pc-key-relay-support/PLAN.md — do
not confuse the two; this plan only covers the new C# pc-client/ code. -->

## Index

1. Scope
2. Components
3. Focus-Scoped Capture (`PreviewKeyDown`)
4. Key Set & `INPUT_KEY` Payload
5. WPF Interception Gotchas
6. Availability Gating
7. Interactions With Other Features
8. Open Items

## 1. Scope

Capturing individual PC keyboard presses on `VideoView` and relaying them as `INPUT_KEY` messages, for finer live control than the batch `INPUT_TEXT` flow. Does not cover the parent-side injection mechanism (that's `docs/plans/pc-key-relay-support/PLAN.md`) — this plan only owns capture, encoding, and sending.

## 2. Components

| Component | Responsibility |
|---|---|
| `Input/KeyRelayController` | `PreviewKeyDown` handler on `VideoView`; maps a subset of `Key` values to the fixed `INPUT_KEY` key-identifier set, sends the message, marks the event handled. |
| `Net/InputKeyPayload` | Wire payload record for `MessageType.InputKey` (`docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §3). |

## 3. Focus-Scoped Capture (`PreviewKeyDown`)

Per `docs/pc-client-detailed-spec.md` §11 / `docs/DECISIONS.md` #21 (focus-scoped, not a global hook):

- `KeyRelayController` subscribes to `VideoView.PreviewKeyDown` — WPF's ordinary tunneling key event, which only fires while `VideoView` (or a child of it) currently holds keyboard focus. No system-wide keyboard hook (`SetWindowsHookEx` or similar) exists anywhere in this plan.
- `VideoView` must be focusable (`Focusable="true"`) and gains focus via the ordinary WPF mechanisms (mouse click on the video area, `Tab`-navigation from another control, or explicit `Focus()` call when `LiveView` — `docs/plans/pc-client/connection-state-ui/PLAN.md` §6 — is first shown) — no special focus-stealing logic is added; standard WPF focus behavior is sufficient and is the entire mechanism this requirement relies on.
- Because WPF gives keyboard focus to exactly one control at a time, a keypress while `docs/plans/pc-client/input-relay/PLAN.md`'s `TextInputBar` (a separate `TextBox`) has focus never reaches `VideoView.PreviewKeyDown` at all — no double-send risk between composing `INPUT_TEXT` and this plan's `INPUT_KEY`, confirmed by `docs/pc-client-detailed-spec.md` §11's own explicit note.

## 4. Key Set & `INPUT_KEY` Payload

Per `docs/pc-client-detailed-spec.md` §11 — fixed set, not general passthrough: Arrow Up/Down/Left/Right, Backspace, Delete, Enter, Tab, Escape.

```csharp
public enum RelayKey { ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Backspace, Delete, Enter, Tab, Escape }

public record InputKeyPayload(RelayKey Key)
{
    public MessageFrame ToFrame() => MessageFrame.Of(MessageType.InputKey, JsonSerializer.Serialize(this));
}
```

`KeyRelayController.OnPreviewKeyDown`:

```csharp
private static readonly Dictionary<Key, RelayKey> KeyMap = new()
{
    [Key.Up] = RelayKey.ArrowUp,
    [Key.Down] = RelayKey.ArrowDown,
    [Key.Left] = RelayKey.ArrowLeft,
    [Key.Right] = RelayKey.ArrowRight,
    [Key.Back] = RelayKey.Backspace,
    [Key.Delete] = RelayKey.Delete,
    [Key.Enter] = RelayKey.Enter,
    [Key.Tab] = RelayKey.Tab,
    [Key.Escape] = RelayKey.Escape,
};

private void OnPreviewKeyDown(object sender, KeyEventArgs e)
{
    if (!InputAvailabilityState.InputAvailable) return;               // §6
    if (!KeyMap.TryGetValue(e.Key, out var relayKey)) return;          // not in the fixed set -- ignored
    SessionChannel.Send(new InputKeyPayload(relayKey).ToFrame());
    e.Handled = true;                                                  // §5
}
```

Key-repeat while a key is held down (`KeyEventArgs.IsRepeat`) is **not** filtered/suppressed here — each repeat fires another `INPUT_KEY` send, matching how a physical Android keyboard's own auto-repeat would behave; no debounce/throttle logic is added, since the spec places no requirement on repeat behavior and the parent-side handler (`docs/plans/pc-key-relay-support/PLAN.md`) processes each `INPUT_KEY` message independently regardless of send rate.

## 5. WPF Interception Gotchas

Per `docs/pc-client-detailed-spec.md` §11's explicit implementation hint: using `PreviewKeyDown` (tunneling) rather than the bubbling `KeyDown`, and setting `e.Handled = true` after a successful relay (§4's code above), is required specifically because WPF's own default handling would otherwise consume `Tab` (built-in focus-navigation to the next control) and, in some control templates, `Escape` (dialog-close/cancel conventions) before they'd ever reach a bubbling handler. Since `VideoView` has no other competing `PreviewKeyDown` handler and no built-in WPF control template of its own to fight with, this is sufficient — no additional `KeyboardNavigation.TabNavigation="None"` or similar attached-property overrides are needed beyond marking the event handled.

## 6. Availability Gating

Per `docs/pc-client-detailed-spec.md` §11's last content bullet: `INPUT_KEY` is gated behind the exact same `InputAvailabilityState.InputAvailable` flag as `INPUT_TAP`/`INPUT_SWIPE`/`INPUT_TEXT` (`docs/plans/pc-client/input-relay/PLAN.md` §8) — not an independent flag. `KeyRelayController` reads the same shared observable that plan defines; no separate `ACCESSIBILITY_STATUS`-adjacent state is introduced.

## 7. Interactions With Other Features

- **video-and-session/connectivity**: this plan adds `Net.MessageType.InputKey = 11` and `Net/InputKeyPayload.cs` to that plan's `Net` package (already listed as a to-be-added file in `docs/plans/pc-client/video-and-session/connectivity.PLAN.md` §2's component table); sends `INPUT_KEY` frames out over that plan's `Net.SessionChannel`.
- **input-relay**: shares `InputAvailabilityState` (§6); focus-scoping (§3) is what prevents double-handling between that plan's `TextInputBar` and this plan's key capture.
- **connection-state-ui**: `KeyRelayController` is only active while `LiveView` (`docs/plans/pc-client/connection-state-ui/PLAN.md` §6) is the current content — constructed/torn down alongside `VideoView`.
- **docs/plans/pc-key-relay-support/PLAN.md (Android side)**: that plan's `ParentAccessibilityService` handler is the exact receiving counterpart to this plan's send logic — the two plans must stay in sync on the `RelayKey`/Kotlin key-identifier enum values and the `MessageType.InputKey = 11` tag; do not let either plan's value set drift without updating the other. See `docs/plans/README.md` §4 for the shared Message Protocol table entry both plans rely on.

## 8. Open Items

None. `docs/pc-client-detailed-spec.md` §11 fixes the key set, the Escape/Back-adjacent behavior split (handled entirely on the Android side, this plan sends Escape identically to the other eight keys — no special-casing needed here since the distinction only matters to the receiver), the focus-scoping requirement, and the `PreviewKeyDown`/`e.Handled` implementation hint. Key-repeat behavior (§4) is a minor implementation-level choice with no product-facing consequence flagged in the spec.
