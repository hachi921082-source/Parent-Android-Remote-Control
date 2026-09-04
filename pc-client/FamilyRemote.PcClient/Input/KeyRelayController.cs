using System.Windows.Input;
using FamilyRemote.PcClient.Net;
using FamilyRemote.PcClient.Video;

namespace FamilyRemote.PcClient.Input;

/// <summary>
/// VideoView上のPreviewKeyDown（トンネリング）を捕捉し、固定キー集合（矢印上下左右/Backspace/
/// Delete/Enter/Tab/Escape）のみをINPUT_KEYとして中継する。docs/plans/pc-client/
/// physical-key-relay-pc-side/PLAN.md §3/§4/§5参照 -- フォーカススコープ捕捉であり、システム全体の
/// キーボードフックではない（docs/DECISIONS.md #21）。VideoViewが実際にキーボードフォーカスを
/// 持っている間しか<see cref="OnPreviewKeyDown"/>は呼ばれない（Session/LiveView.xaml.csが
/// VideoView.Focus()を呼ぶ箇所参照）。
///
/// PreviewKeyDown（Tunneling）を使うのはKeyDown（Bubbling）だとWPFの既定処理がTab
/// （フォーカス移動）/Escapeを先に消費してしまうため（PLAN.md §5）。固定集合外のキーはe.Handledを
/// trueにせず既定のWPF処理へそのまま渡す（中継しないだけで、無視や握りつぶしはしない）。
///
/// <see cref="PointerInputController"/>と同じdelegate注入パターン（isInputAllowed/sendFrame）を
/// 踏襲する（Input/PointerInputController.cs参照）。InputAvailabilityState/IsInputAllowed()は
/// Session/LiveView.xaml.csが単一の共有インスタンスとして渡す -- このクラス自身は独自の状態を
/// 持たない（PLAN.md §6）。
/// </summary>
public sealed class KeyRelayController
{
    // PLAN.md §4のマッピング表そのまま。この集合外のKeyは中継しない。
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

    private readonly Func<bool> _isInputAllowed;
    private readonly Func<MessageFrame, bool> _sendFrame;

    public KeyRelayController(VideoView video, Func<bool> isInputAllowed, Func<MessageFrame, bool> sendFrame)
    {
        _isInputAllowed = isInputAllowed;
        _sendFrame = sendFrame;
        video.PreviewKeyDown += OnPreviewKeyDown;
    }

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (!_isInputAllowed())
        {
            return;
        }

        if (!KeyMap.TryGetValue(e.Key, out RelayKey relayKey))
        {
            return; // 固定集合外 -- 中継せず、既定のWPF処理へそのまま渡す
        }

        // キーリピート(e.IsRepeat)もフィルタしない -- 押しっぱなし中は毎回送る
        // （PLAN.md §4、Androidの物理キーボード自動リピートと同じ挙動）。
        _sendFrame(new InputKeyPayload(relayKey).ToFrame());
        e.Handled = true;
    }
}
