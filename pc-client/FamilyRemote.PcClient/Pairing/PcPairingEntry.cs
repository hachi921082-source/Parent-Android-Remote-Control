namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// PCクライアント側でのペアリング済み親デバイス1件分の永続レコード。
/// Android子側の PairingEntry（app/src/main/java/com/familyremote/app/pairing/PairingEntry.kt、
/// docs/plans/pairing-and-onboarding/PLAN.md §2.1）とフィールド単位で対応するが、独立した
/// PC専用リスト。docs/plans/pc-client/pairing-pc-side/PLAN.md §2 参照。
/// </summary>
/// <param name="Id">= pairingId。親の PAIRING_ACK から受け取る。</param>
/// <param name="Label">自由記述、いつでも編集可能（docs/spec.md §5）。</param>
/// <param name="ParentHost">親のTailscale IPまたはMagicDNS名。オペレーターが手入力。</param>
/// <param name="SharedSecret">このクライアントが生成する256bitランダムトークン（base64url）。</param>
/// <param name="CreatedAt">作成日時。</param>
public sealed record PcPairingEntry(
    Guid Id,
    string Label,
    string ParentHost,
    string SharedSecret,
    DateTimeOffset CreatedAt);
