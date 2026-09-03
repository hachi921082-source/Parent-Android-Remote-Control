namespace FamilyRemote.PcClient.Session;

/// <summary>
/// <see cref="ParentStatusPanel"/>が描画する、親デバイス状態の最新スナップショット。
/// docs/plans/pc-client/connection-state-ui/PLAN.md §6参照 -- ワイヤ上は
/// Net/StatusHeartbeatPayload.cs と Net/StatusEventPayload.cs の2つの別メッセージ型で届くが
/// （フィールド形状は同一）、表示側はどちらから来たかを気にしない一本のスナップショットとして扱う
/// （Android側 child/ConnectionStateViewModel.latestStatus: StateFlow&lt;StatusPayload?&gt; と同じ設計。
/// あちらは1つの net.StatusPayload をSTATUS_HEARTBEAT/STATUS_EVENT両方で共用しているのに対し、
/// このtierではNet/側を1メッセージ型1ファイルの既存規約
/// （Net/SessionHelloPayload.cs参照）に合わせて分けたため、表示用の型をここに独立させて集約する）。
/// </summary>
public sealed record ParentStatusSnapshot(
    bool WifiConnected,
    string NetworkType,
    int? SignalStrength,
    string? Ssid);
