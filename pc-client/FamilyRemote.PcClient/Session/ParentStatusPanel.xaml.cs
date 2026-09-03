using System.Windows.Controls;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// ParentStatusPanel.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §6
/// 参照。表示のみ -- 再接続判断等のロジックは一切持たない（そちらはHeartbeatWatchdogの責務）。
/// </summary>
public partial class ParentStatusPanel : UserControl
{
    public ParentStatusPanel()
    {
        InitializeComponent();
        Render(null);
    }

    /// <summary>
    /// <paramref name="snapshot"/>が<c>null</c>のときは「接続済み、状態情報待ち」の仮文言を出す
    /// -- Live直後、最初のSTATUS_HEARTBEATが届く前の一瞬に該当する
    /// （Android側 SessionActivity.renderStatus(null) と同じ扱い）。
    /// </summary>
    public void Render(ParentStatusSnapshot? snapshot)
    {
        if (snapshot is null)
        {
            StatusText.Text = Strings.SessionStatusPlaceholder;
            return;
        }

        string networkTypeText = snapshot.NetworkType switch
        {
            "WIFI" => Strings.NetworkTypeWifi,
            "CELLULAR" => Strings.NetworkTypeCellular,
            _ => Strings.NetworkTypeNone,
        };

        var lines = new List<string>
        {
            snapshot.WifiConnected ? Strings.SessionStatusWifiConnected : Strings.SessionStatusWifiDisconnected,
            string.Format(Strings.SessionStatusNetworkType, networkTypeText),
        };

        if (snapshot.SignalStrength is { } signalStrength)
        {
            lines.Add(string.Format(Strings.SessionStatusSignal, signalStrength));
        }

        if (snapshot.Ssid is { } ssid)
        {
            lines.Add(string.Format(Strings.SessionStatusSsid, ssid));
        }

        StatusText.Text = string.Join("\n", lines);
    }
}
