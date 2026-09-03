using System.Windows.Controls;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// LiveView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §6/§7、
/// video-and-session/decode.PLAN.md §2参照。<see cref="ParentStatusPanel"/>の更新と
/// <see cref="ReconnectingBanner"/>の表示切り替え（connection-state-ui）に加え、<see cref="Video"/>
/// を<c>store.Decoder</c>へ結びつける（decode）。TextInputSlotへの実装は後続tier（input-relay）の
/// 責務。
/// </summary>
public partial class LiveView : UserControl
{
    private readonly ConnectionStateStore _store;

    public LiveView(ConnectionStateStore store)
    {
        InitializeComponent();
        _store = store;

        // store.Decoderは、新規Live突入時点でConnectionStateStore.OnConnectedが既に構築済み
        // （LiveViewが構築されるのはConnectionStateがLiveへ遷移した後のみのため -- decode.PLAN.md
        // §4 step 1、Session/ConnectionStateStore.csのDecoderプロパティのコメント参照）。
        Video.Attach(store.Decoder!);

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
