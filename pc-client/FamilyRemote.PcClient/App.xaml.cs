using System.Globalization;
using System.Threading;
using System.Windows;

namespace FamilyRemote.PcClient;

/// <summary>
/// アプリケーションのエントリポイント。起動シーケンス（単一インスタンス強制 → UIカルチャ解決 →
/// MainWindow生成・表示）を所有する。docs/plans/pc-client/app-shell/PLAN.md §6 参照。
/// </summary>
public partial class App : Application
{
    // Global\ プレフィックスによりセッションをまたいで有効な名前付きMutex。
    // プロセス生存期間中保持し続け、明示的にReleaseしない（プロセス終了時にOSが自動解放する）。
    private const string SingleInstanceMutexName = @"Global\FamilyRemote.PcClient.SingleInstance";

    // static fieldに保持することでGCによる回収・ファイナライズを防ぐ（保持し続ける必要があるため）。
    private static Mutex? _singleInstanceMutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (!AcquireSingleInstanceLock())
        {
            MessageBox.Show(
                "Family Remote is already running.",
                "Family Remote",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
            Shutdown();
            return;
        }

        ResolveAndApplyUiCulture();

        var mainWindow = new MainWindow();
        mainWindow.Show();
    }

    /// <summary>
    /// 名前付きMutexを取得する。既に他インスタンスが保持していればfalseを返す。
    /// </summary>
    private static bool AcquireSingleInstanceLock()
    {
        _singleInstanceMutex = new Mutex(initiallyOwned: true, name: SingleInstanceMutexName, out bool createdNew);
        return createdNew;
    }

    /// <summary>
    /// OS UIカルチャからja/zh/既定(英語)の3バケットへマッピングし、
    /// スレッドのCurrentUICulture/CurrentCultureに適用する。MainWindow構築前に呼ぶこと。
    /// </summary>
    private static void ResolveAndApplyUiCulture()
    {
        string twoLetterIso = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;

        CultureInfo resolved = twoLetterIso switch
        {
            "ja" => new CultureInfo("ja"),
            "zh" => new CultureInfo("zh"),
            _ => new CultureInfo("en"),
        };

        Thread.CurrentThread.CurrentUICulture = resolved;
        Thread.CurrentThread.CurrentCulture = resolved;
    }
}
