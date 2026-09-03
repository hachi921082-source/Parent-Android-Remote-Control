using System.Windows;
using System.Windows.Controls;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// ReconnectingBanner.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §7
/// 参照。表示/非表示の切り替えだけを持つ -- 内容(文言)はXAML側で固定。
/// </summary>
public partial class ReconnectingBanner : UserControl
{
    public ReconnectingBanner()
    {
        InitializeComponent();
    }

    public void SetVisible(bool visible) =>
        Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
}
