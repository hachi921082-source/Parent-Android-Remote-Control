namespace FamilyRemote.PcClient.Common;

/// <summary>
/// アプリ全体で共有される定数。docs/plans/pc-client/README.md §6 参照。
/// Android側 app/src/main/java/com/familyremote/app/common/Constants.kt と数値を完全一致させる
/// （別々に導出しない）。まだ使う機能が実装されていない値も、単一の参照元を保つため先に定義しておく
/// （Android版と同じ方針）。このファイルはpairing-pc-side tierが最初に必要とするため新規作成したが、
/// 以降のtierも自分の担当領域の定数をここへ追記する。
/// </summary>
public static class Constants
{
    // --- Pairing (このtier). ---

    /// <summary>ペアリング専用の一時TCPポート。docs/plans/pc-client/pairing-pc-side/PLAN.md §6 参照。</summary>
    public const int PairingPort = 47322;

    /// <summary>手動コード/QRの tempToken、およびそれを待つ親側リスナーの有効期間。</summary>
    public const long PairingCodeValidityMs = 300_000;

    /// <summary>手動ペアリングコードの文字数。docs/pc-client-detailed-spec.md §3 参照。</summary>
    public const int ManualCodeLength = 8;

    /// <summary>Crockford Base32アルファベット（I/L/O/Uを除く32文字）。</summary>
    public const string CrockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /// <summary>
    /// TailscaleのCGNAT IPv4レンジ（100.64.0.0/10）。PcTailscaleAddressResolverが使用する。
    /// Android側Constants.ktのTAILSCALE_CGNAT_*と同値。
    /// </summary>
    public const byte TailscaleCgnatFirstOctet = 100;
    public const byte TailscaleCgnatSecondOctetMin = 64;
    public const byte TailscaleCgnatSecondOctetMax = 127;

    // --- Session (video-and-session/connectivity tier). ---

    /// <summary>ライブセッションTCPポート。docs/plans/pc-client/README.md §6参照（Android側SESSION_PORTと同値）。</summary>
    public const int SessionPort = 47321;

    // --- Connection-state-ui (このtier). docs/plans/pc-client/connection-state-ui/PLAN.md §7参照。 ---

    /// <summary>親からのSTATUS_HEARTBEAT送信間隔（ミリ秒）。Android側HEARTBEAT_INTERVAL_MSと同値。</summary>
    public const long HeartbeatIntervalMs = 5000;

    /// <summary>Live→Reconnecting遷移のしきい値（ハートビート欠落回数）。Android側MISSED_HEARTBEAT_THRESHOLDと同値。</summary>
    public const int MissedHeartbeatThreshold = 3;

    /// <summary>Reconnecting→Disconnected遷移までの猶予（ミリ秒）。Android側RECONNECT_WINDOW_MSと同値。</summary>
    public const long ReconnectWindowMs = 60_000;

    // --- Input-relay (このtier). docs/plans/pc-client/input-relay/PLAN.md §6参照. ---

    /// <summary>タップ/スワイプのローカルマーカーのフェードアウト時間（ミリ秒）。Android側TAP_MARKER_FADE_MSと同値。</summary>
    public const long TapMarkerFadeMs = 300;
}
