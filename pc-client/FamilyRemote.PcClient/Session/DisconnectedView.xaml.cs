using System.Windows;
using System.Windows.Controls;
using FamilyRemote.PcClient.Pairing;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// DisconnectedView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §7
/// 参照。「ペアリング一覧に戻る」は<see cref="ConnectionStateStore.BackToPairingSelection"/>を
/// 呼ぶのみ -- Disconnected到達時点で既にリスナー/チャネル/watchdogは
/// ConnectionStateStore側で片付け済み（OnDisconnectedDetected）。
/// </summary>
public partial class DisconnectedView : UserControl
{
    private readonly ConnectionStateStore _store;

    public DisconnectedView(ConnectionStateStore store, PcPairingEntry pairing)
    {
        InitializeComponent();
        _store = store;
        DisconnectedText.Text = string.Format(Strings.SessionDisconnectedTitle, pairing.Label);
    }

    private void OnBackClick(object sender, RoutedEventArgs e) => _store.BackToPairingSelection();
}
