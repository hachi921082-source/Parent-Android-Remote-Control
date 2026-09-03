using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の ACCESSIBILITY_STATUS フレームのペイロード。docs/plans/pc-client/
/// input-relay/PLAN.md §8参照 -- 親側のAccessibility Service権限の許諾状態が変化するたびに送られる
/// （毎回の定期チェックではない）。JSONフィールド名はAndroid側 AccessibilityStatusPayload
/// （app/src/main/java/com/familyremote/app/net/AccessibilityStatusPayload.kt）と完全一致させる
/// （フィールド名: "granted"）。
/// </summary>
public sealed record AccessibilityStatusPayload(
    [property: JsonPropertyName("granted")] bool Granted)
{
    public static AccessibilityStatusPayload Parse(string raw) =>
        JsonSerializer.Deserialize<AccessibilityStatusPayload>(raw)
        ?? throw new JsonException("Empty ACCESSIBILITY_STATUS payload");
}
