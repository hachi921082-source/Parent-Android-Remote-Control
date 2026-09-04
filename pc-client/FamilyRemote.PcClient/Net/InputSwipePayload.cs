using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// INPUT_SWIPEの1経路点 -- 既に親フレームのピクセル座標へスケール済み。<see cref="InputSwipePayload.PathPoints"/>
/// の要素としてのみ使う独立性の低い値のため、Android側InputMessages.ktと同様、専用ファイルには分けず
/// ここに同居させる。
/// </summary>
public sealed record InputPathPoint(
    [property: JsonPropertyName("x")] int X,
    [property: JsonPropertyName("y")] int Y);

/// <summary>
/// child/PC -&gt; parent の INPUT_SWIPE フレームのペイロード。docs/plans/pc-client/input-relay/PLAN.md
/// §3/§5参照 -- JSONフィールド名はAndroid側 InputSwipePayload
/// （app/src/main/java/com/familyremote/app/net/InputMessages.kt）と完全一致させる。
/// </summary>
public sealed record InputSwipePayload(
    [property: JsonPropertyName("pathPoints")] IReadOnlyList<InputPathPoint> PathPoints,
    [property: JsonPropertyName("durationMs")] long DurationMs)
{
    public MessageFrame ToFrame() => MessageFrame.Of(MessageType.InputSwipe, JsonSerializer.Serialize(this));
}
