using System.ComponentModel;
using System.Windows;
using FamilyRemote.PcClient.Pairing;
using FamilyRemote.PcClient.Session;

namespace FamilyRemote.PcClient;

/// <summary>
/// 唯一のトップレベルウィンドウ。ウィンドウchrome（タイトル・最小サイズ・リサイズ可否）に加え、
/// RootContentへの画面切り替えを所有する -- docs/plans/pc-client/connection-state-ui/PLAN.md §4
/// のペアリング選択フロー（ゼロ件/1件/複数件）と、<see cref="ConnectionStateStore.StateChanged"/>
/// を購読してWaitingForParent/Live/Reconnecting/Disconnected/Idleの各画面をRootContentへ差し替える
/// ロジック。docs/plans/pc-client/app-shell/PLAN.md §3参照。
/// </summary>
public partial class MainWindow : Window
{
    private readonly PcPairingRepository _pairingRepository = new();
    private readonly ConnectionStateStore _store;

    /// <summary>
    /// Live/Reconnectingの間だけ非nullで保持する -- WaitingForParent -&gt; Liveの「新規のLive突入」
    /// でのみ新しいインスタンスを作り、Live&lt;-&gt;Reconnectingの往復では使い回す
    /// （docs/plans/pc-client/connection-state-ui/PLAN.md §9、zoom-magnifier等の「新規Live突入時のみ
    /// リセット」要件に合わせる -- video/zoomがまだ存在しないこのtierの時点でも、その前提を崩さない
    /// 構造にしておく）。
    /// </summary>
    private LiveView? _liveView;

    public MainWindow()
    {
        InitializeComponent();
        Closing += MainWindow_Closing;
        Loaded += MainWindow_Loaded;

        _store = new ConnectionStateStore(Dispatcher);
        _store.StateChanged += OnStoreStateChanged;
    }

    /// <summary>
    /// docs/plans/pc-client/app-shell/PLAN.md §6 step 4 / connection-state-ui/PLAN.md §4のエントリ
    /// ポイント -- ペアリング一覧の件数に応じてゼロ件/1件/複数件のいずれかの初期画面を出す。
    /// </summary>
    private void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        IReadOnlyList<PcPairingEntry> entries = _pairingRepository.GetAll();
        if (entries.Count == 0)
        {
            ShowManualPairing();
        }
        else if (entries.Count == 1)
        {
            _store.StartWaiting(entries[0]);
        }
        else
        {
            ShowPairingList();
        }
    }

    /// <summary>
    /// Cancel/Disconnectedからの「Idleへ戻る」際の画面選択。起動時の<see cref="MainWindow_Loaded"/>
    /// とは異なり、1件だけの場合でも自動的にWaitingForParentへは戻らない -- 一覧を必ず表示し、
    /// 操作者に明示的な再選択を要求する（docs/pc-client-detailed-spec.md §8「not an automatic
    /// silent retry back into WaitingForParent」を参照。auto-selectは起動時一回限りの利便であり、
    /// アクティブなセッションからの復帰には適用しない）。
    /// </summary>
    private void ShowPairingSelectionScreen()
    {
        IReadOnlyList<PcPairingEntry> entries = _pairingRepository.GetAll();
        if (entries.Count == 0)
        {
            ShowManualPairing();
        }
        else
        {
            ShowPairingList();
        }
    }

    private void ShowManualPairing()
    {
        var view = new ManualPairingView();
        // 新規ペアリング完了直後は、一覧を読み直して件数ルールを再適用せず、その場でできた
        // エントリを直接使う（PLAN.md §4最後の箇条書き）。
        view.PairingCompleted += (_, entry) => _store.StartWaiting(entry);
        SetRootContent(view);
    }

    private void ShowPairingList()
    {
        var view = new PairingListView();
        view.AddNewPairingRequested += (_, _) => ShowManualPairing();
        view.PairingEntrySelected += (_, entry) => _store.StartWaiting(entry);
        SetRootContent(view);
    }

    private void OnStoreStateChanged(ConnectionState state)
    {
        switch (state)
        {
            case ConnectionState.WaitingForParent waiting:
                _liveView = null;
                SetRootContent(new WaitingForParentView(_store, waiting.Pairing));
                break;

            case ConnectionState.Live:
            case ConnectionState.Reconnecting:
                EnsureLiveView();
                break;

            case ConnectionState.Disconnected disconnected:
                _liveView = null;
                SetRootContent(new DisconnectedView(_store, disconnected.Pairing));
                break;

            case ConnectionState.Idle:
                _liveView = null;
                ShowPairingSelectionScreen();
                break;
        }
    }

    /// <summary>
    /// 既にLiveViewを表示中（Live&lt;-&gt;Reconnectingの往復）なら何もしない -- LiveView自身が
    /// ConnectionStateStore.StateChangedを購読してReconnectingBannerの表示切り替えを行う
    /// （Session/LiveView.xaml.cs参照）。
    /// </summary>
    private void EnsureLiveView()
    {
        if (_liveView == null)
        {
            _liveView = new LiveView(_store);
            SetRootContent(_liveView);
        }
    }

    private void SetRootContent(UIElement content)
    {
        RootContent.Children.Clear();
        RootContent.Children.Add(content);
    }

    /// <summary>
    /// ウィンドウを閉じると常にアプリを終了する。「トレイに最小化して裏で動かし続ける」分岐は
    /// 存在しない（docs/pc-client-spec.md §3 / docs/pc-client-detailed-spec.md §8）。
    /// </summary>
    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        // アクティブなリスナー/チャネルがあれば片付ける（docs/plans/pc-client/connection-state-ui/
        // PLAN.md §8）。MediaFoundationDecoderの破棄はまだこのtierの責務ではない
        // （video-and-session/decodeが未実装のため、ConnectionStateStore.Teardown側にも同旨のコメント
        // あり）。
        _store.Teardown();

        // アプリ終了は無条件（Cancelしない）。プロセスはWPFの通常のシャットダウンフローで終了する。
    }
}
