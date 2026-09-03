using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// PC側ペアリングエントリ一覧のCRUD。docs/plans/pc-client/pairing-pc-side/PLAN.md §3 参照。
/// %LOCALAPPDATA%\FamilyRemote.PcClient\pairing_entries.json に、DPAPI
/// (ProtectedData.Protect/Unprotect, DataProtectionScope.CurrentUser) で暗号化したJSON配列として
/// 保存する -- Android側EncryptedSharedPreferencesと同程度の保護強度を、OS標準APIのみで実現する
/// 軽量な対応（同PLAN.md §3参照）。データセットが極小なので、変更のたびに全件読み書きする
/// （Android側PairingRepositoryと同じ設計判断）。
/// </summary>
public sealed class PcPairingRepository
{
    private static readonly string StorageDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "FamilyRemote.PcClient");

    private static readonly string StorageFilePath = Path.Combine(StorageDirectory, "pairing_entries.json");

    public IReadOnlyList<PcPairingEntry> GetAll()
    {
        if (!File.Exists(StorageFilePath))
        {
            return Array.Empty<PcPairingEntry>();
        }

        byte[] encrypted = File.ReadAllBytes(StorageFilePath);
        byte[] json;
        try
        {
            json = ProtectedData.Unprotect(encrypted, optionalEntropy: null, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException)
        {
            // 破損した、または別Windowsユーザーが書き込んだブロブ -- クラッシュさせず空扱いにする。
            return Array.Empty<PcPairingEntry>();
        }

        return JsonSerializer.Deserialize<List<PcPairingEntry>>(json) ?? new List<PcPairingEntry>();
    }

    public void Add(PcPairingEntry entry)
    {
        List<PcPairingEntry> entries = GetAll().ToList();
        entries.Add(entry);
        SaveAll(entries);
    }

    public void UpdateLabel(Guid id, string newLabel)
    {
        List<PcPairingEntry> entries = GetAll()
            .Select(e => e.Id == id ? e with { Label = newLabel } : e)
            .ToList();
        SaveAll(entries);
    }

    public void Delete(Guid id)
    {
        List<PcPairingEntry> entries = GetAll().Where(e => e.Id != id).ToList();
        SaveAll(entries);
    }

    private static void SaveAll(List<PcPairingEntry> entries)
    {
        Directory.CreateDirectory(StorageDirectory);
        byte[] json = JsonSerializer.SerializeToUtf8Bytes(entries);
        byte[] encrypted = ProtectedData.Protect(json, optionalEntropy: null, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(StorageFilePath, encrypted);
    }
}
