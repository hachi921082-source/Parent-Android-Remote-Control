using FamilyRemote.PcClient.Pairing;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// PC側の接続ライフサイクル状態。Android子側 common.ConnectionState
/// （app/src/main/java/com/familyremote/app/common/ConnectionState.kt）をcase単位でそのまま移植した
/// もの。docs/plans/pc-client/connection-state-ui/PLAN.md §3参照。
/// `InputAvailable`（ビューオンリーモード）はここに含めない -- Android版と同様、Live/Reconnecting内
/// のみに乗る直交フラグ（docs/plans/pc-client/input-relay/PLAN.md §8、未実装）。
/// </summary>
public abstract record ConnectionState
{
    private ConnectionState()
    {
    }

    /// <summary>ペアリング未選択、または選択画面/手動ペアリング画面を表示中。</summary>
    public sealed record Idle : ConnectionState;

    /// <summary>選択したペアリング先からの着信を待ち受け中（タイムアウトなし、キャンセル可）。</summary>
    public sealed record WaitingForParent(PcPairingEntry Pairing) : ConnectionState;

    /// <summary>ハートビート監視が有効なライブセッション中。</summary>
    public sealed record Live(PcPairingEntry Pairing) : ConnectionState;

    /// <summary>ハートビート3回分（約15秒）欠落を検知し、再接続を待っている状態（最大60秒）。</summary>
    public sealed record Reconnecting(PcPairingEntry Pairing, DateTimeOffset Since) : ConnectionState;

    /// <summary>60秒以内に再接続できず切断確定。ペアリング選択への復帰のみが次の操作。</summary>
    public sealed record Disconnected(PcPairingEntry Pairing) : ConnectionState;
}
