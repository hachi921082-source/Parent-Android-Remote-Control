using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// parent -&gt; child/PC の SESSION_BUSY フレームのペイロード。GitHub issue #16参照 -- 親が既にLive
/// セッションを保持している状態で二台目（Android子/PCクライアントのどちらでも）が接続してきた際、
/// 敗者側に送られてソケットが閉じられる（再試行なし）。JSONフィールド名はAndroid側
/// SessionBusyPayload（app/src/main/java/com/familyremote/app/net/SessionHandshakeMessages.kt）と
/// 完全一致させる（フィールド名: "reason"）。このクライアントはSESSION_BUSYを送信することがない
/// （敗者判定は常に親側）ため、ToFrame()等の送信用メソッドは持たない -- パース専用。
/// </summary>
public sealed record SessionBusyPayload(
    [property: JsonPropertyName("reason")] string Reason)
{
    public static SessionBusyPayload Parse(string raw) =>
        JsonSerializer.Deserialize<SessionBusyPayload>(raw)
        ?? throw new JsonException("Empty SESSION_BUSY payload");
}
