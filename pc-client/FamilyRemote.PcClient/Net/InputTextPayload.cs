using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// child/PC -&gt; parent の INPUT_TEXT フレームのペイロード。docs/plans/pc-client/input-relay/PLAN.md
/// §7参照（Decision #9 -- 全文を組んでから一度だけ送る、キーストロークごとの逐次送信はしない）。
/// JSONフィールド名はAndroid側 InputTextPayload
/// （app/src/main/java/com/familyremote/app/net/InputMessages.kt）と完全一致させる。
/// </summary>
public sealed record InputTextPayload(
    [property: JsonPropertyName("text")] string Text)
{
    public MessageFrame ToFrame() => MessageFrame.Of(MessageType.InputText, JsonSerializer.Serialize(this));
}
