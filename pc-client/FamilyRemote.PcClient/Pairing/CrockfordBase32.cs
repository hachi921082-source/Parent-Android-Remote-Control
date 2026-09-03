using System.Text;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// 手動ペアリングコードの正規化。docs/plans/pc-client/pairing-pc-side/PLAN.md §4 参照。
/// docs/pc-client-detailed-spec.md §3: 区切り文字を除去、大文字化した後、I/L -&gt; 1、O -&gt; 0の
/// エイリアス補正を行う。'U'に対応するマッピングは存在しない -- Crockfordの標準アルファベットは
/// 単に'U'をアルファベットから除外しているだけでエイリアスしないため、誤入力された'U'はそのまま
/// 素通りし、生成済みの正当なコードとは一致しなくなる（意図された挙動）。
/// </summary>
public static class CrockfordBase32
{
    public static string Normalize(string input)
    {
        string stripped = input.Trim().Replace("-", "").Replace(" ", "").ToUpperInvariant();
        var sb = new StringBuilder(stripped.Length);
        foreach (char c in stripped)
        {
            sb.Append(c switch
            {
                'I' or 'L' => '1',
                'O' => '0',
                _ => c,
            });
        }

        return sb.ToString();
    }

    /// <summary>
    /// 正規化後の文字列が、Common.Constants.ManualCodeLength文字ちょうどで、かつ全文字が
    /// Common.Constants.CrockfordAlphabetに含まれるかを検査する（Pairボタン活性化のための
    /// クライアント側フォーマット事前チェック。実際の一致判定は親側が唯一の権威を持つ）。
    /// docs/plans/pc-client/pairing-pc-side/PLAN.md §4 参照。
    /// </summary>
    public static bool IsValidFormat(string normalized)
    {
        if (normalized.Length != Common.Constants.ManualCodeLength)
        {
            return false;
        }

        foreach (char c in normalized)
        {
            if (Common.Constants.CrockfordAlphabet.IndexOf(c) < 0)
            {
                return false;
            }
        }

        return true;
    }
}
