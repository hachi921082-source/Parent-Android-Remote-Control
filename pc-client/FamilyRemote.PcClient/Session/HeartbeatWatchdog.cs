using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// `Live -&gt; Reconnecting -&gt; Disconnected`遷移を駆動する。Android側 child/HeartbeatWatchdog.kt
/// をそのまま移植したもの。docs/plans/pc-client/connection-state-ui/PLAN.md §7参照。
/// セッションチャネル上で最後に何か受信した時刻（<see cref="OnMessageReceived"/>）と、
/// Reconnectingに入ってからの経過時間を別々に追跡する。
/// </summary>
public sealed class HeartbeatWatchdog
{
    private readonly Action _onReconnectingDetected;
    private readonly Action _onDisconnectedDetected;

    private long _lastReceivedAtMs;
    private long? _reconnectingSinceMs;
    private CancellationTokenSource? _cts;

    public HeartbeatWatchdog(Action onReconnectingDetected, Action onDisconnectedDetected)
    {
        _onReconnectingDetected = onReconnectingDetected;
        _onDisconnectedDetected = onDisconnectedDetected;
    }

    /// <summary>(再)開始する -- 最後の受信時刻を「今」にリセットし、Reconnectingマークもクリアする。</summary>
    public void Start()
    {
        _lastReceivedAtMs = NowMs();
        _reconnectingSinceMs = null;

        _cts?.Cancel();
        var cts = new CancellationTokenSource();
        _cts = cts;
        _ = RunLoopAsync(cts.Token);
    }

    private async Task RunLoopAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay((int)Constants.HeartbeatIntervalMs, token).ConfigureAwait(false);
                CheckOnce();
            }
        }
        catch (TaskCanceledException)
        {
            // Stop()によるキャンセル -- 正常終了として無視する。
        }
    }

    private void CheckOnce()
    {
        long now = NowMs();
        long? since = _reconnectingSinceMs;
        if (since is null)
        {
            long missedThresholdMs = Constants.MissedHeartbeatThreshold * Constants.HeartbeatIntervalMs;
            if (now - _lastReceivedAtMs >= missedThresholdMs)
            {
                MarkReconnecting(now);
            }
        }
        else if (now - since.Value >= Constants.ReconnectWindowMs)
        {
            _cts?.Cancel();
            _onDisconnectedDetected();
        }
    }

    private void MarkReconnecting(long now)
    {
        _reconnectingSinceMs = now;
        _onReconnectingDetected();
    }

    /// <summary>
    /// 「今まさに切断された」という明示的な外部シグナル（SessionChannel.ConnectionLost）を受けた際の
    /// 即時通知 -- 次の5秒周期チェックを待たずにReconnectingへ入る。既にReconnecting中ならno-op
    /// （後続の欠落チェックが60秒猶予を「今」から再スタートさせてしまわないようにするため）。
    /// </summary>
    public void NotifyConnectionLost()
    {
        if (_reconnectingSinceMs is null)
        {
            MarkReconnecting(NowMs());
        }
    }

    /// <summary>
    /// セッションチャネル上で何か受信するたびに呼ぶ。Reconnectingマークもクリアする
    /// （何か受信できた時点で接続は生きているとみなす）。
    /// </summary>
    public void OnMessageReceived()
    {
        _lastReceivedAtMs = NowMs();
        _reconnectingSinceMs = null;
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
    }

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
