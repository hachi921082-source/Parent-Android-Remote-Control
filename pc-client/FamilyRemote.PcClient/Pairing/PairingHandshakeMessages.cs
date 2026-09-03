using System.Text.Json;
using System.Text.Json.Serialization;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// child/PC -&gt; parent の PAIRING_HELLO フレームのペイロード。
/// docs/plans/pc-client/pairing-pc-side/PLAN.md §6 point 3 参照。JSONフィールド名はAndroid側
/// PairingHelloPayload（app/src/main/java/com/familyremote/app/pairing/PairingHandshakeMessages.kt）
/// と完全一致させる（tempToken/childHost/proposedSecret）。このクライアントではtempTokenの値は
/// 正規化済み8文字の手動コードだが、フィールド名もメッセージ種別も新設しない -- 親側は値の形状
/// （8文字Crockford英数字か、より長いトークンか）で判別する。
/// </summary>
public sealed record PairingHelloPayload(
    [property: JsonPropertyName("tempToken")] string TempToken,
    [property: JsonPropertyName("childHost")] string ChildHost,
    [property: JsonPropertyName("proposedSecret")] string ProposedSecret)
{
    public string ToJson() => JsonSerializer.Serialize(this);
}

/// <summary>parent -&gt; child/PC の PAIRING_ACK フレームのペイロード。同PLAN.md §6 point 4 参照。</summary>
public sealed record PairingAckPayload(
    [property: JsonPropertyName("childId")] Guid ChildId)
{
    public static PairingAckPayload Parse(string raw) =>
        JsonSerializer.Deserialize<PairingAckPayload>(raw)
        ?? throw new JsonException("Empty PAIRING_ACK payload");
}
