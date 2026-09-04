using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// INPUT_KEYが中継する固定キー集合。docs/pc-client-detailed-spec.md §11 / docs/plans/pc-client/
/// physical-key-relay-pc-side/PLAN.md §4参照 -- 一般キーパススルーではない。ワイヤ表現は
/// <see cref="RelayKeyJsonConverter"/>参照。
/// </summary>
[JsonConverter(typeof(RelayKeyJsonConverter))]
public enum RelayKey
{
    ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    Backspace,
    Delete,
    Enter,
    Tab,
    Escape,
}

/// <summary>
/// child/PC -&gt; parent の INPUT_KEY フレームのペイロード。PLAN.md §4参照 -- JSONフィールド名/値は
/// Android側 InputKeyMessage.kt（RelayKey enum + InputKeyPayload）と完全一致させる
/// （フィールド名"key"、値はSCREAMING_SNAKE_CASE文字列）。
/// </summary>
public sealed record InputKeyPayload(
    [property: JsonPropertyName("key")] RelayKey Key)
{
    public MessageFrame ToFrame() => MessageFrame.Of(MessageType.InputKey, JsonSerializer.Serialize(this));
}
