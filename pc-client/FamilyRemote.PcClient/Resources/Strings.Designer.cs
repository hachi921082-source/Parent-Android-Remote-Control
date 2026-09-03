// ------------------------------------------------------------------------------
// このファイルは自動生成コードに相当する形で手書きしたものです（このプロジェクトはVisual
// Studioなしでビルドされるため、標準のResXFileCodeGenerator/PublicResXFileCodeGeneratorは
// 実行されません）。docs/plans/pc-client/app-shell/PLAN.md §5 参照。
//
// Resources\Strings.resx にキーを追加する各tierは、対応するstaticプロパティをこのファイルにも
// 追加すること（.ja.resx / .zh.resx 側は翻訳のみで、このDesignerクラスの変更は不要）。
// ------------------------------------------------------------------------------

#nullable enable

using System.Globalization;
using System.Reflection;
using System.Resources;

namespace FamilyRemote.PcClient.Resources;

/// <summary>
/// ローカライズされたリソース文字列への型安全なアクセスを提供する。
/// カルチャの解決はSystem.Resources.ResourceManagerの標準機構（CurrentUICulture、
/// 中立(既定=英語)へのフォールバック）に従う。カルチャの決定自体はApp.OnStartupが
/// 起動時に一度だけ行う（docs/plans/pc-client/app-shell/PLAN.md §5/§6）。
/// </summary>
public static class Strings
{
    private static readonly ResourceManager ResourceManager =
        new("FamilyRemote.PcClient.Resources.Strings", Assembly.GetExecutingAssembly());

    public static CultureInfo? Culture { get; set; }

    public static string AppTitle =>
        ResourceManager.GetString(nameof(AppTitle), Culture) ?? nameof(AppTitle);
}
