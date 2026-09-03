using System.Windows.Controls;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// LiveView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §6/§7参照。
/// このtierが持つのは<see cref="ParentStatusPanel"/>の更新と<see cref="ReconnectingBanner"/>の
/// 表示切り替えのみ -- VideoSlot/TextInputSlotへの実装は後続tier（video-and-session/decode,
/// input-relay）の責務。
/// </summary>
public partial class LiveView : UserControl
{
    private readonly ConnectionStateStore _store;

    public LiveView(ConnectionStateStore store)
    {
        InitializeComponent();
        _store = store;

        StatusPanel.Render(store.LatestStatus);
        RenderBanner(store.State);

        Loaded += (_, _) =>
        {
            _store.StateChanged += OnStateChanged;
            _store.StatusUpdated += OnStatusUpdated;
        };
        Unloaded += (_, _) =>
        {
            _store.StateChanged -= OnStateChanged;
            _store.StatusUpdated -= OnStatusUpdated;
        };
    }

    private void OnStateChanged(ConnectionState state) => RenderBanner(state);

    private void OnStatusUpdated(ParentStatusSnapshot snapshot) => StatusPanel.Render(snapshot);

    private void RenderBanner(ConnectionState state) => Banner.SetVisible(state is ConnectionState.Reconnecting);
}
