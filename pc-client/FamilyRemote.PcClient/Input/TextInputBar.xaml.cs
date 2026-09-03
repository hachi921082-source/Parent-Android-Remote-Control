using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using FamilyRemote.PcClient.Net;

namespace FamilyRemote.PcClient.Input;

/// <summary>
/// TextInputBar.xamlのコードビハインド。docs/plans/pc-client/input-relay/PLAN.md §7参照 --
/// Android側 child/TextInputBar.kt を移植したもの。全文を組んでから一度だけ送信する
/// （キーストロークごとの逐次送信はしない、Decision #9）。
/// </summary>
public partial class TextInputBar : UserControl
{
    private const int ToastAutoHideMs = 3000;

    private readonly DispatcherTimer _toastTimer;
    private Func<MessageFrame, bool>? _sendFrame;

    public TextInputBar()
    {
        InitializeComponent();

        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(ToastAutoHideMs) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer.Stop();
            ToastBorder.Visibility = Visibility.Collapsed;
        };
    }

    /// <summary>フレーム送信先を結びつける -- Session/LiveView.xaml.csのコンストラクタから一度だけ
    /// 呼ばれる想定（store.Channelはライブ突入時点で既に非null、Session/ConnectionStateStore.cs
    /// 参照）。</summary>
    public void Attach(Func<MessageFrame, bool> sendFrame)
    {
        _sendFrame = sendFrame;
    }

    /// <summary>フィールド+ボタンを一緒に有効/無効化する -- 入力UIは一括で無効化するという
    /// PLAN.md §7/§8の要件。</summary>
    public void SetEnabled(bool enabled)
    {
        TextBoxInput.IsEnabled = enabled;
        SendButton.IsEnabled = enabled;
    }

    /// <summary>TEXT_INJECT_FAILED受信時の一過性トースト表示（PLAN.md §7） -- TextInputBar自体を
    /// 無効化はしない（送信の都度の失敗であり、InputAvailableのような定常状態ではない）。</summary>
    public void ShowTextInjectFailedNotice()
    {
        ToastBorder.Visibility = Visibility.Visible;
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    private void OnSendClicked(object sender, RoutedEventArgs e)
    {
        string text = TextBoxInput.Text;
        if (string.IsNullOrEmpty(text) || _sendFrame == null)
        {
            return;
        }

        TextBoxInput.Clear();
        _sendFrame(new InputTextPayload(text).ToFrame());
    }
}
