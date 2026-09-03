using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の TEXT_INJECT_FAILED フレームのペイロード。docs/plans/pc-client/input-relay/
/// PLAN.md §7参照 -- INPUT_TEXT注入に失敗した（親側でフォーカス中の編集可能ノードが無かった）ときに
/// 送られる、送信の都度の一過性通知（InputAvailableのような定常状態ではない）。JSONフィールド名は
/// Android側 TextInjectFailedPayload（app/src/main/java/com/familyremote/app/net/InputMessages.kt）
/// と完全一致させる。
/// </summary>
public sealed record TextInjectFailedPayload(
    [property: JsonPropertyName("reason")] string Reason)
{
    public static TextInjectFailedPayload Parse(string raw) =>
        JsonSerializer.Deserialize<TextInjectFailedPayload>(raw)
        ?? throw new JsonException("Empty TEXT_INJECT_FAILED payload");
}
