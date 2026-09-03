using System.Windows.Threading;
using FamilyRemote.PcClient.Net;
using FamilyRemote.PcClient.Pairing;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// <see cref="ConnectionState"/>の単一の真実の源。Android側 child/ConnectionStateViewModel.kt
/// をそのまま移植したもの。docs/plans/pc-client/connection-state-ui/PLAN.md §2/§3参照。
/// <see cref="PcSessionListener"/>（待ち受けソケット）と、Live確立後の<see cref="Net.SessionChannel"/>
/// / <see cref="HeartbeatWatchdog"/>を所有する。
///
/// スレッド設計: <see cref="PcSessionListener"/>/<see cref="Net.SessionChannel"/>/
/// <see cref="HeartbeatWatchdog"/>のコールバックはすべてバックグラウンドスレッドから呼ばれるため、
/// 状態を実際に変更する箇所（<see cref="SetState"/>等）は必ず<paramref name="dispatcher"/>上で
/// 実行する（<see cref="RunOnUiThread"/>）。MainWindow等のUIコードから直接呼ばれる公開メソッド
/// （<see cref="StartWaiting"/>等）はUIスレッド上で呼ばれる前提のため、追加のマーシャリングをしない。
///
/// 再入防止（GitHub issue #15）に関する呼び出しパターンのメモ: <see cref="PcSessionListener.Start"/>
/// を呼ぶのは(1)<see cref="StartWaiting"/>、(2)<see cref="RetryWaiting"/>（直前の試行が
/// onFailedで既に完了している場合のみ到達可能）、(3)Reconnecting再武装（直前の試行がonConnectedで
/// 既に完了し、Liveへ遷移済みの場合のみ到達可能）の3箇所のみ -- いずれも「直前のAcceptLoopタスクが
/// 既に終了している」ことが呼び出し条件として保証された経路であり、稼働中のAcceptLoopに対して
/// 二重にStart()を呼ぶことはない。
/// </summary>
public sealed class ConnectionStateStore
{
    private readonly Dispatcher _dispatcher;
    private readonly PcSessionListener _listener = new();

    private SessionChannel? _channel;
    private HeartbeatWatchdog? _watchdog;

    public ConnectionState State { get; private set; } = new ConnectionState.Idle();

    /// <summary>状態が変わるたびに発火する。常にUIスレッド上で呼ばれる。</summary>
    public event Action<ConnectionState>? StateChanged;

    /// <summary>直近に受信したSTATUS_HEARTBEAT/STATUS_EVENTの内容（表示専用、§6）。</summary>
    public ParentStatusSnapshot? LatestStatus { get; private set; }

    /// <summary>常にUIスレッド上で呼ばれる。</summary>
    public event Action<ParentStatusSnapshot>? StatusUpdated;

    /// <summary>
    /// <see cref="PcSessionListener"/>自体の起動失敗（Tailscale未検出等）のメッセージ。
    /// 単発の不正ハンドシェイクとは異なり無言では済ませない（PLAN.md §5 point 5）。
    /// </summary>
    public string? ListenerError { get; private set; }

    /// <summary>常にUIスレッド上で呼ばれる。</summary>
    public event Action<string?>? ListenerErrorChanged;

    public ConnectionStateStore(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
    }

    /// <summary>
    /// ペアリング先を選択してWaitingForParentへ入る（PLAN.md §4/§5）。
    /// 新規ペアリング完了直後もこれで直接呼ばれる（PLAN.md §4最後の箇条書き参照 -- 一覧の
    /// 再読み込み・再選択ロジックを経由しない）。
    /// </summary>
    public void StartWaiting(PcPairingEntry pairing)
    {
        LatestStatus = null;
        SetState(new ConnectionState.WaitingForParent(pairing));
        Arm(pairing);
    }

    /// <summary>Listener失敗後の「Retry」ボタン用（PLAN.md §5 point 5）。</summary>
    public void RetryWaiting()
    {
        if (State is not ConnectionState.WaitingForParent waiting)
        {
            return;
        }

        Arm(waiting.Pairing);
    }

    /// <summary>WaitingForParentの「Cancel」ボタン用 -- 待ち受けを止めてIdleへ戻る（PLAN.md §5 point 4）。</summary>
    public void CancelWaiting()
    {
        _listener.Stop();
        SetState(new ConnectionState.Idle());
    }

    /// <summary>Disconnectedの「ペアリング一覧に戻る」ボタン用（PLAN.md §7）。</summary>
    public void BackToPairingSelection()
    {
        SetState(new ConnectionState.Idle());
    }

    private void Arm(PcPairingEntry pairing)
    {
        _listener.Start(
            pairing,
            onConnected: channel => RunOnUiThread(() =>
            {
                SetListenerError(null);
                OnConnected(pairing, channel);
            }),
            onFailed: message => RunOnUiThread(() => SetListenerError(message)));
    }

    private void OnConnected(PcPairingEntry pairing, SessionChannel channel)
    {
        SetState(new ConnectionState.Live(pairing));

        HeartbeatWatchdog watchdog = _watchdog ??= new HeartbeatWatchdog(
            onReconnectingDetected: () => RunOnUiThread(() => OnReconnectingDetected(pairing)),
            onDisconnectedDetected: () => RunOnUiThread(() => OnDisconnectedDetected(pairing)));
        watchdog.Start(); // (再)開始 -- 最終受信時刻/Reconnectingマークを「今」にリセット。

        _channel?.Close();
        channel.RegisterHandler(MessageType.StatusHeartbeat, frame => OnStatusFrame(frame, watchdog, isEvent: false));
        channel.RegisterHandler(MessageType.StatusEvent, frame => OnStatusFrame(frame, watchdog, isEvent: true));
        channel.ConnectionLost += () => watchdog.NotifyConnectionLost();
        _channel = channel;
        channel.Start();
    }

    /// <summary>
    /// SessionChannelの読み取りループ（バックグラウンドスレッド）から直接呼ばれる。
    /// 「何か受信した」通知自体はUIスレッドへ切り替えず即座に行う（HeartbeatWatchdog内部は
    /// スレッドセーフに書かれている）。表示更新（<see cref="LatestStatus"/>/<see cref="StatusUpdated"/>）
    /// のみをUIスレッドへマーシャリングする。
    /// </summary>
    private void OnStatusFrame(MessageFrame frame, HeartbeatWatchdog watchdog, bool isEvent)
    {
        watchdog.OnMessageReceived();

        ParentStatusSnapshot snapshot = isEvent
            ? ToSnapshot(StatusEventPayload.Parse(frame.PayloadAsUtf8String()))
            : ToSnapshot(StatusHeartbeatPayload.Parse(frame.PayloadAsUtf8String()));

        RunOnUiThread(() =>
        {
            LatestStatus = snapshot;
            StatusUpdated?.Invoke(snapshot);
        });
    }

    private static ParentStatusSnapshot ToSnapshot(StatusHeartbeatPayload payload) =>
        new(payload.WifiConnected, payload.NetworkType, payload.SignalStrength, payload.Ssid);

    private static ParentStatusSnapshot ToSnapshot(StatusEventPayload payload) =>
        new(payload.WifiConnected, payload.NetworkType, payload.SignalStrength, payload.Ssid);

    /// <summary>
    /// 3回分のハートビート欠落を検知したときの遷移（PLAN.md §7）。Live/Reconnecting以外からは
    /// 到達しない想定（既にDisconnected/Idleへ抜けていればHeartbeatWatchdogは停止済みのため）。
    /// </summary>
    private void OnReconnectingDetected(PcPairingEntry pairing)
    {
        if (State is not (ConnectionState.Live or ConnectionState.Reconnecting))
        {
            return;
        }

        if (State is not ConnectionState.Reconnecting)
        {
            SetState(new ConnectionState.Reconnecting(pairing, DateTimeOffset.UtcNow));
        }

        // 受け付けループを再武装する -- リスナー自体は開いたまま
        // （video-and-session/connectivity.PLAN.md §4 step 4）。直前のAcceptLoopタスクは
        // 最初にonConnectedを1回呼んだ時点で既に終了しているため、二重起動にはならない。
        Arm(pairing);
    }

    /// <summary>60秒の再接続猶予切れ（PLAN.md §7）。</summary>
    private void OnDisconnectedDetected(PcPairingEntry pairing)
    {
        _channel?.Close();
        _channel = null;
        _watchdog?.Stop();
        _watchdog = null;
        _listener.Stop();
        SetState(new ConnectionState.Disconnected(pairing));
    }

    /// <summary>
    /// MainWindow.Closingから呼ぶ（PLAN.md §8）。リスナー停止・チャネル破棄のみを行う --
    /// MediaFoundationDecoderの破棄はvideo-and-session/decode（未実装tier）が持つべき責務のため、
    /// ここでは意図的に含めない（このtierの時点ではデコーダの概念自体が存在しない）。
    /// </summary>
    public void Teardown()
    {
        _listener.Stop();
        _channel?.Close();
        _channel = null;
        _watchdog?.Stop();
        _watchdog = null;
    }

    private void SetState(ConnectionState newState)
    {
        State = newState;
        StateChanged?.Invoke(newState);
    }

    private void SetListenerError(string? message)
    {
        ListenerError = message;
        ListenerErrorChanged?.Invoke(message);
    }

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher.CheckAccess())
        {
            action();
        }
        else
        {
            _dispatcher.Invoke(action);
        }
    }
}
