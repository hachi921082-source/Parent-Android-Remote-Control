using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// child/PC -&gt; parent の INPUT_TAP フレームのペイロード。docs/plans/pc-client/input-relay/PLAN.md
/// §3/§4参照 -- JSONフィールド名はAndroid側 InputTapPayload
/// （app/src/main/java/com/familyremote/app/net/InputMessages.kt）と完全一致させる。座標は既に
/// 親フレームのピクセル整数へスケール済み（0..1正規化ではない、PLAN.md §3 -- 親は自分の現在フレーム
/// 寸法を既に知っているため、正規化は往復精度の懸念を増やすだけで利点がない）。
/// </summary>
public sealed record InputTapPayload(
    [property: JsonPropertyName("x")] int X,
    [property: JsonPropertyName("y")] int Y)
{
    public MessageFrame ToFrame() => MessageFrame.Of(MessageType.InputTap, JsonSerializer.Serialize(this));
}
