namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// PcPairingHandshakeClient.PerformHandshakeAsyncの結果。Android側PairingClientResultの
/// sealed classに相当するが、C#では成功/失敗を1つのrecordにまとめ、IsSuccessで判別する
/// （実装上の選択、意味は同じ）。
/// </summary>
public sealed record PairingHandshakeResult(bool IsSuccess, Guid ChildId, string SharedSecret, string? ErrorMessage)
{
    public static PairingHandshakeResult Success(Guid childId, string sharedSecret) =>
        new(true, childId, sharedSecret, null);

    public static PairingHandshakeResult Failure(string message) =>
        new(false, Guid.Empty, string.Empty, message);
}
