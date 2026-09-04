using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// RelayKey &lt;-&gt; ワイヤ文字列（SCREAMING_SNAKE_CASE）の明示的な対応表による変換。
/// docs/plans/pc-client/physical-key-relay-pc-side/PLAN.md §4参照 -- Android側 RelayKey
/// （app/src/main/java/com/familyremote/app/net/InputKeyMessage.kt）は
/// <c>enum class RelayKey { ARROW_UP, ARROW_DOWN, ... }</c>で、送信時は<c>key.name</c>
/// （そのままSCREAMING_SNAKE_CASE文字列）をJSON値に使う。C#側の<see cref="RelayKey"/>は
/// 慣用的なPascalCase命名（ArrowUp等）を保つため、System.Text.Jsonの既定/
/// <see cref="JsonStringEnumConverter"/>のPascalCase出力（"ArrowUp"）ではなく、
/// このコンバータで値ごとに明示マッピングする。
/// </summary>
public sealed class RelayKeyJsonConverter : JsonConverter<RelayKey>
{
    private static readonly Dictionary<RelayKey, string> ToWire = new()
    {
        [RelayKey.ArrowUp] = "ARROW_UP",
        [RelayKey.ArrowDown] = "ARROW_DOWN",
        [RelayKey.ArrowLeft] = "ARROW_LEFT",
        [RelayKey.ArrowRight] = "ARROW_RIGHT",
        [RelayKey.Backspace] = "BACKSPACE",
        [RelayKey.Delete] = "DELETE",
        [RelayKey.Enter] = "ENTER",
        [RelayKey.Tab] = "TAB",
        [RelayKey.Escape] = "ESCAPE",
    };

    private static readonly Dictionary<string, RelayKey> FromWire =
        ToWire.ToDictionary(kv => kv.Value, kv => kv.Key);

    public override RelayKey Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        string? raw = reader.GetString();
        if (raw != null && FromWire.TryGetValue(raw, out RelayKey key))
        {
            return key;
        }

        throw new JsonException($"Unknown RelayKey wire value: {raw}");
    }

    public override void Write(Utf8JsonWriter writer, RelayKey value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(ToWire[value]);
    }
}
