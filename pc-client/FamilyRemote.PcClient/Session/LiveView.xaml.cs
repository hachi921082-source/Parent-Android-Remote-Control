using System.Windows.Controls;
using FamilyRemote.PcClient.Input;
using FamilyRemote.PcClient.Net;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// LiveView.xamlのコードビハインド。docs/plans/pc-client/connection-state-ui/PLAN.md §6/§7、
/// video-and-session/decode.PLAN.md §2、input-relay/PLAN.md §2/§3/§7/§8参照。
/// <see cref="ParentStatusPanel"/>の更新と<see cref="ReconnectingBanner"/>の表示切り替え
/// （connection-state-ui）、<see cref="Video"/>を<c>store.Decoder</c>へ結びつける（decode）ことに加え、
/// <see cref="PointerInputController"/>/<see cref="InputAvailabilityState"/>の構築・<see cref="Overlay"/>
/// （タップ/スワイプマーカー+ビューオンリーバナー）・<see cref="TextBar"/>（テキスト入力）の有効化を
/// 一括して担う（input-relay）。
/// </summary>
public partial class LiveView : UserControl
{
    private readonly ConnectionStateStore _store;
    private readonly InputAvailabilityState _inputAvailability;

    public LiveView(ConnectionStateStore store)
    {
        InitializeComponent();
        _store = store;

        // store.Decoderは、新規Live突入時点でConnectionStateStore.OnConnectedが既に構築済み
        // （LiveViewが構築されるのはConnectionStateがLiveへ遷移した後のみのため -- decode.PLAN.md
        // §4 step 1、Session/ConnectionStateStore.csのDecoderプロパティのコメント参照）。
        Video.Attach(store.Decoder!);

        // store.Channelも同じ理由でこの時点で既に非null（ConnectionStateStore.Channelのコメント
        // 参照）-- AccessibilityStatus/TextInjectFailedのハンドラをここで直接登録できる
        // （input-relay/PLAN.md §8）。
        _inputAvailability = new InputAvailabilityState(Dispatcher);
        RegisterInputAvailabilityHandlers();
        _inputAvailability.InputAvailableChanged += _ => RenderInputAvailability();
        _inputAvailability.TextInjectFailed += () => TextBar.ShowTextInjectFailedNotice();

        var pointerInput = new PointerInputController(
            Video,
            isInputAllowed: IsInputAllowed,
            sendFrame: frame => _store.Channel?.SendFrame(frame) ?? false,
            currentGeometry: () => _store.Decoder?.CurrentGeometry);
        pointerInput.ValidGesture += points => Overlay.ShowMarker(points);

        TextBar.Attach(frame => _store.Channel?.SendFrame(frame) ?? false);

        StatusPanel.Render(store.LatestStatus);
        RenderBanner(store.State);
        RenderInputAvailability();

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

    private void OnStateChanged(ConnectionState state)
    {
        RenderBanner(state);

        if (state is ConnectionState.Live)
        {
            // 新規Live突入、またはReconnecting->Liveの再接続: このセッションのInputAvailableは
            // 前の接続のものではもう意味を持たないため既定へ戻す（PLAN.md §8、Android側
            // InputAvailabilityViewModel.resetがConnectionState.Liveを観測するたび呼ばれるのと
            // 同じルール）。store.Channelはこの時点で新しいインスタンスへ更新済みのため、
            // ハンドラも登録し直す（ConnectionStateStore.OnConnectedのコメント参照）。
            RegisterInputAvailabilityHandlers();
            _inputAvailability.Reset();
        }

        RenderInputAvailability();
    }

    private void OnStatusUpdated(ParentStatusSnapshot snapshot) => StatusPanel.Render(snapshot);

    private void RenderBanner(ConnectionState state) => Banner.SetVisible(state is ConnectionState.Reconnecting);

    /// <summary>PointerInputController/TextInputBarの共通ゲート -- 外側のConnectionStateが
    /// Liveであり、かつInputAvailableがtrueの間のみ入力を許す（PLAN.md §8最終箇条書き）。</summary>
    private bool IsInputAllowed() => _store.State is ConnectionState.Live && _inputAvailability.InputAvailable;

    private void RenderInputAvailability()
    {
        TextBar.SetEnabled(IsInputAllowed());
        Overlay.SetInputUnavailable(_store.State is ConnectionState.Live && !_inputAvailability.InputAvailable);
    }

    private void RegisterInputAvailabilityHandlers()
    {
        _store.Channel!.RegisterHandler(MessageType.AccessibilityStatus, _inputAvailability.OnAccessibilityStatusFrame);
        _store.Channel!.RegisterHandler(MessageType.TextInjectFailed, _inputAvailability.OnTextInjectFailedFrame);
    }
}
