using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の STATUS_EVENT フレームのペイロード（Wi-Fi/接続状態変化時に即時送信）。
/// docs/plans/pc-client/connection-state-ui/PLAN.md §6参照 -- JSONフィールド名はAndroid側
/// StatusPayload（app/src/main/java/com/familyremote/app/net/StatusPayload.kt）と完全一致させる。
/// STATUS_HEARTBEAT（Net/StatusHeartbeatPayload.cs）と形状は同一だが、Net/SessionHelloPayload.cs
/// 以来の「1メッセージ型につき1ファイル」の規約に合わせ、あえて別のレコード型として分けている
/// （表示側での共用は Session/ParentStatusSnapshot.cs 参照）。
/// </summary>
public sealed record StatusEventPayload(
    [property: JsonPropertyName("wifiConnected")] bool WifiConnected,
    [property: JsonPropertyName("networkType")] string NetworkType,
    [property: JsonPropertyName("signalStrength")] int? SignalStrength,
    [property: JsonPropertyName("ssid")] string? Ssid,
    [property: JsonPropertyName("timestampMs")] long TimestampMs)
{
    public static StatusEventPayload Parse(string raw) =>
        JsonSerializer.Deserialize<StatusEventPayload>(raw)
        ?? throw new JsonException("Empty STATUS_EVENT payload");
}
