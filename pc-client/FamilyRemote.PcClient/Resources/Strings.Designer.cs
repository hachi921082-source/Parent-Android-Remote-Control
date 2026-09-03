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

    // --- pairing-pc-side (docs/plans/pc-client/pairing-pc-side/PLAN.md §5/§7) ---

    public static string ManualPairingHostLabel =>
        ResourceManager.GetString(nameof(ManualPairingHostLabel), Culture) ?? nameof(ManualPairingHostLabel);

    public static string ManualPairingCodeLabel =>
        ResourceManager.GetString(nameof(ManualPairingCodeLabel), Culture) ?? nameof(ManualPairingCodeLabel);

    public static string ManualPairingButton =>
        ResourceManager.GetString(nameof(ManualPairingButton), Culture) ?? nameof(ManualPairingButton);

    public static string ManualPairingConnecting =>
        ResourceManager.GetString(nameof(ManualPairingConnecting), Culture) ?? nameof(ManualPairingConnecting);

    public static string ManualPairingErrorGeneric =>
        ResourceManager.GetString(nameof(ManualPairingErrorGeneric), Culture) ?? nameof(ManualPairingErrorGeneric);

    public static string ManualPairingTailscaleMissing =>
        ResourceManager.GetString(nameof(ManualPairingTailscaleMissing), Culture) ?? nameof(ManualPairingTailscaleMissing);

    public static string LabelPromptTitle =>
        ResourceManager.GetString(nameof(LabelPromptTitle), Culture) ?? nameof(LabelPromptTitle);

    public static string LabelPromptDefault =>
        ResourceManager.GetString(nameof(LabelPromptDefault), Culture) ?? nameof(LabelPromptDefault);

    public static string LabelPromptSaveButton =>
        ResourceManager.GetString(nameof(LabelPromptSaveButton), Culture) ?? nameof(LabelPromptSaveButton);

    public static string PairingListTitle =>
        ResourceManager.GetString(nameof(PairingListTitle), Culture) ?? nameof(PairingListTitle);

    public static string PairingListEmpty =>
        ResourceManager.GetString(nameof(PairingListEmpty), Culture) ?? nameof(PairingListEmpty);

    public static string PairingListAddNew =>
        ResourceManager.GetString(nameof(PairingListAddNew), Culture) ?? nameof(PairingListAddNew);

    public static string PairingListRename =>
        ResourceManager.GetString(nameof(PairingListRename), Culture) ?? nameof(PairingListRename);

    public static string PairingListDelete =>
        ResourceManager.GetString(nameof(PairingListDelete), Culture) ?? nameof(PairingListDelete);

    public static string PairingRenameDialogTitle =>
        ResourceManager.GetString(nameof(PairingRenameDialogTitle), Culture) ?? nameof(PairingRenameDialogTitle);

    public static string PairingDeleteDialogTitle =>
        ResourceManager.GetString(nameof(PairingDeleteDialogTitle), Culture) ?? nameof(PairingDeleteDialogTitle);

    public static string PairingDeleteDialogMessage =>
        ResourceManager.GetString(nameof(PairingDeleteDialogMessage), Culture) ?? nameof(PairingDeleteDialogMessage);

    public static string ActionOk =>
        ResourceManager.GetString(nameof(ActionOk), Culture) ?? nameof(ActionOk);

    public static string ActionCancel =>
        ResourceManager.GetString(nameof(ActionCancel), Culture) ?? nameof(ActionCancel);

    public static string ActionDelete =>
        ResourceManager.GetString(nameof(ActionDelete), Culture) ?? nameof(ActionDelete);

    public static string ActionRetry =>
        ResourceManager.GetString(nameof(ActionRetry), Culture) ?? nameof(ActionRetry);

    // --- connection-state-ui (docs/plans/pc-client/connection-state-ui/PLAN.md §5/§6/§7) ---

    public static string SessionWaitingTitle =>
        ResourceManager.GetString(nameof(SessionWaitingTitle), Culture) ?? nameof(SessionWaitingTitle);

    public static string SessionListenerError =>
        ResourceManager.GetString(nameof(SessionListenerError), Culture) ?? nameof(SessionListenerError);

    public static string SessionStatusPlaceholder =>
        ResourceManager.GetString(nameof(SessionStatusPlaceholder), Culture) ?? nameof(SessionStatusPlaceholder);

    public static string SessionStatusWifiConnected =>
        ResourceManager.GetString(nameof(SessionStatusWifiConnected), Culture) ?? nameof(SessionStatusWifiConnected);

    public static string SessionStatusWifiDisconnected =>
        ResourceManager.GetString(nameof(SessionStatusWifiDisconnected), Culture) ?? nameof(SessionStatusWifiDisconnected);

    public static string SessionStatusNetworkType =>
        ResourceManager.GetString(nameof(SessionStatusNetworkType), Culture) ?? nameof(SessionStatusNetworkType);

    public static string SessionStatusSignal =>
        ResourceManager.GetString(nameof(SessionStatusSignal), Culture) ?? nameof(SessionStatusSignal);

    public static string SessionStatusSsid =>
        ResourceManager.GetString(nameof(SessionStatusSsid), Culture) ?? nameof(SessionStatusSsid);

    public static string NetworkTypeWifi =>
        ResourceManager.GetString(nameof(NetworkTypeWifi), Culture) ?? nameof(NetworkTypeWifi);

    public static string NetworkTypeCellular =>
        ResourceManager.GetString(nameof(NetworkTypeCellular), Culture) ?? nameof(NetworkTypeCellular);

    public static string NetworkTypeNone =>
        ResourceManager.GetString(nameof(NetworkTypeNone), Culture) ?? nameof(NetworkTypeNone);

    public static string SessionReconnectingBanner =>
        ResourceManager.GetString(nameof(SessionReconnectingBanner), Culture) ?? nameof(SessionReconnectingBanner);

    public static string SessionDisconnectedTitle =>
        ResourceManager.GetString(nameof(SessionDisconnectedTitle), Culture) ?? nameof(SessionDisconnectedTitle);

    public static string SessionDisconnectedBack =>
        ResourceManager.GetString(nameof(SessionDisconnectedBack), Culture) ?? nameof(SessionDisconnectedBack);
}
