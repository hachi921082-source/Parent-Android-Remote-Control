using System.Security.Cryptography;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// ペアリングハンドシェイクで使う共有シークレットを生成する。
/// docs/plans/pc-client/pairing-pc-side/PLAN.md §6 point 6 参照 -- Android側
/// SecureTokenGenerator.generateSharedSecret()（app/src/main/java/com/familyremote/app/pairing/
/// SecureTokenGenerator.kt）とバイト単位で同じエンコーディング（256bit、base64url、パディング
/// なし）にする。この値は後で親側ストレージを経由して比較されるため、両実装で完全一致している
/// 必要がある。
/// </summary>
public static class SecureTokenGenerator
{
    private const int SharedSecretByteCount = 32;

    public static string GenerateSharedSecret()
    {
        byte[] bytes = RandomNumberGenerator.GetBytes(SharedSecretByteCount);
        return Convert.ToBase64String(bytes)
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');
    }
}
