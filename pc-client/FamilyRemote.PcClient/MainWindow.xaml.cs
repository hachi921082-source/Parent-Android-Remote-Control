using System.ComponentModel;
using System.Windows;

namespace FamilyRemote.PcClient;

/// <summary>
/// 唯一のトップレベルウィンドウ。ウィンドウchrome（タイトル・最小サイズ・リサイズ可否）のみを
/// 所有し、コンテンツ自体は後続tier（connection-state-ui）がRootContentへ配置する。
/// docs/plans/pc-client/app-shell/PLAN.md §3 参照。
/// </summary>
public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Closing += MainWindow_Closing;
    }

    /// <summary>
    /// ウィンドウを閉じると常にアプリを終了する。「トレイに最小化して裏で動かし続ける」分岐は
    /// 存在しない（docs/pc-client-spec.md §3 / docs/pc-client-detailed-spec.md §8）。
    /// </summary>
    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        // TODO(connection-state-ui): アクティブセッションがあればここでセッション終了処理を
        // 呼び出す（docs/plans/pc-client/connection-state-ui/PLAN.md 所有のセッション終了処理）。
        // 現tierにはまだセッション概念が存在しないため、フック位置のみ残す。

        // アプリ終了は無条件（Cancelしない）。プロセスはWPFの通常のシャットダウンフローで終了する。
    }
}
