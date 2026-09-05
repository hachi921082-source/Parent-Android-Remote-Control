using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の ACCESSIBILITY_STATUS フレームのペイロード。docs/plans/pc-client/
/// input-relay/PLAN.md §8参照 -- 親側のAccessibility Service権限の許諾状態が変化するたびに送られる
/// （毎回の定期チェックではない）。JSONフィールド名はAndroid側 AccessibilityStatusPayload
/// （app/src/main/java/com/familyremote/app/net/AccessibilityStatusPayload.kt）と完全一致させる
/// （フィールド名: "granted"/"hasFocusedInputField"）。
/// <see cref="HasFocusedInputField"/>はissue #38（Android側）/#42（PC側）で追加 -- 親側に現在
/// フォーカス中の入力欄があるかどうか。省略時は`false`（デフォルト値）にフォールバックするため、
/// 万が一この項目を含まない古い形式のペイロードを受け取っても例外にはならない。
/// </summary>
public sealed record AccessibilityStatusPayload(
    [property: JsonPropertyName("granted")] bool Granted,
    [property: JsonPropertyName("hasFocusedInputField")] bool HasFocusedInputField = false)
{
    public static AccessibilityStatusPayload Parse(string raw) =>
        JsonSerializer.Deserialize<AccessibilityStatusPayload>(raw)
        ?? throw new JsonException("Empty ACCESSIBILITY_STATUS payload");
}
