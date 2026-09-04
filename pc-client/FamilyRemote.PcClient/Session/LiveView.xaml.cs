using System.Windows.Controls;
using FamilyRemote.PcClient.Zoom;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// LiveView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §6/§7、
/// video-and-session/decode.PLAN.md §2参照。<see cref="ParentStatusPanel"/>の更新と
/// <see cref="ReconnectingBanner"/>の表示切り替え（connection-state-ui）に加え、<see cref="Video"/>
/// を<c>store.Decoder</c>へ結びつける（decode）ほか、<see cref="Video"/>に対する<see cref="ZoomController"/>
/// の構築・結線（zoom-magnifier PLAN.md §6）を行う。TextInputSlotへの実装は後続tier（input-relay）の
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

        // ZoomControllerはVideoインスタンスと1:1でこのコンストラクタ内でのみ生成する -- LiveView自体が
        // Live<->Reconnecting往復では再構築されない（MainWindow.xaml.csの_liveView/EnsureLiveView()
        // 参照）ため、新規Live突入毎に必ず新しいZoomController（=新しいZoomState、Level=1.0）が
        // 作られることになり、zoom-magnifier PLAN.md §6が要求する「新規Live突入毎にリセット」を
        // 追加のリセットロジックなしに満たす。
        _ = new ZoomController(Video);

        // Level>1では拡大されたFrameImageがVideoView自身の境界をはみ出す（詰まっている軸、
        // ZoomedContentRectCalculatorのコメント参照）。WPFのPanel（VideoView.xaml/LiveView.xaml
        // 双方のGrid）はデフォルトで子要素をクリップしないため、Video自身にClipToBounds=trueを
        // 明示しないとはみ出し分がVideoSlotの外（TextInputSlot側）まで描画されてしまう
        // （input-relayがTextInputSlotへ実高さのTextInputBarを配置した時点で顕在化する幾何バグ、
        // レビュー指摘）。VideoViewのSecureHintButtonはVideoView自身の境界内（bottom-right, Margin=8）
        // に位置するため、この境界でのクリップでは一切影響を受けない。
        Video.ClipToBounds = true;

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
