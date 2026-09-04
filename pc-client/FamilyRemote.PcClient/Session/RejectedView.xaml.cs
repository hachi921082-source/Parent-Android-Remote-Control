using System.Windows;
using System.Windows.Controls;
using FamilyRemote.PcClient.Pairing;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// RejectedView.xamlのコードビハインド。GitHub issue #16参照。DisconnectedView.xaml.csと同じ構造
/// -- 「ペアリング一覧に戻る」は<see cref="ConnectionStateStore.BackToPairingSelection"/>を呼ぶのみ。
/// Rejected到達時点で既にリスナー/チャネル/watchdog/デコーダは
/// ConnectionStateStore側で片付け済み（OnSessionBusyDetected）。
/// </summary>
public partial class RejectedView : UserControl
{
    private readonly ConnectionStateStore _store;

    public RejectedView(ConnectionStateStore store, PcPairingEntry pairing)
    {
        InitializeComponent();
        _store = store;
        RejectedText.Text = string.Format(Strings.SessionRejectedTitle, pairing.Label);
    }

    private void OnBackClick(object sender, RoutedEventArgs e) => _store.BackToPairingSelection();
}
