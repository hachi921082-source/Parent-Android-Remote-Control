using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の SESSION_HELLO フレームのペイロード。docs/plans/pc-client/video-and-session/
/// connectivity.PLAN.md §4 point 2参照 -- 親がライブセッション接続直後に最初に送るフレームで、
/// どのペアリングとして接続しているか（childId）とシークレット（sharedSecret）を名乗る。
/// JSONフィールド名はAndroid側SessionHelloPayload
/// （app/src/main/java/com/familyremote/app/net/SessionHandshakeMessages.kt）と完全一致させる。
/// このtierではSESSION_ENDなど他のペイロード型は未使用のため定義しない（該当機能未実装のため、
/// 投機的なスタブは作らない方針）。
/// </summary>
public sealed record SessionHelloPayload(
    [property: JsonPropertyName("childId")] Guid ChildId,
    [property: JsonPropertyName("sharedSecret")] string SharedSecret)
{
    public static SessionHelloPayload Parse(string raw) =>
        JsonSerializer.Deserialize<SessionHelloPayload>(raw)
        ?? throw new JsonException("Empty SESSION_HELLO payload");
}
