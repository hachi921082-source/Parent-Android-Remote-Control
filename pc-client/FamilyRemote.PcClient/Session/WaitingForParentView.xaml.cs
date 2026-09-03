using System.Windows;
using System.Windows.Controls;
using FamilyRemote.PcClient.Pairing;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// WaitingForParentView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md
/// §5参照。表示中は<see cref="ConnectionStateStore.ListenerErrorChanged"/>を購読し、リスナー起動
/// 失敗時のみエラー文言+Retryボタンを出す（単発の不正ハンドシェイクは無言のまま、PLAN.md §5
/// point 5）。ホスト側（MainWindow）は毎回新しいインスタンスを構築する想定
/// -- Reconnecting再武装ではこの画面は再表示されない（状態はLive/Reconnectingのまま）。
/// </summary>
public partial class WaitingForParentView : UserControl
{
    private readonly ConnectionStateStore _store;

    public WaitingForParentView(ConnectionStateStore store, PcPairingEntry pairing)
    {
        InitializeComponent();
        _store = store;

        WaitingText.Text = string.Format(Strings.SessionWaitingTitle, pairing.Label);
        RenderListenerError(store.ListenerError);

        Loaded += (_, _) => _store.ListenerErrorChanged += OnListenerErrorChanged;
        Unloaded += (_, _) => _store.ListenerErrorChanged -= OnListenerErrorChanged;
    }

    private void OnListenerErrorChanged(string? message) => RenderListenerError(message);

    private void RenderListenerError(string? message)
    {
        Visibility visibility = message != null ? Visibility.Visible : Visibility.Collapsed;
        ListenerErrorText.Visibility = visibility;
        RetryButton.Visibility = visibility;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e) => _store.CancelWaiting();

    private void OnRetryClick(object sender, RoutedEventArgs e) => _store.RetryWaiting();
}
