using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Zoom;

/// <summary>
/// <see cref="ZoomState"/>が指すズームサブ領域の、baseContentRectと同じview-localピクセル座標系
/// 上での実際の描画矩形を計算する純粋関数。zoom-magnifier PLAN.md §5参照。<see cref="Video.VideoView"/>
/// の<see cref="Video.VideoView.ContentRectOverride"/>（<see cref="ZoomController"/>経由）が描画用に、
/// docs/plans/pc-client/input-relay/PLAN.md §3の座標マッピングが入力用に、それぞれ同じ結果を参照する
/// （zoom-magnifier PLAN.md §5「layered on top of, not a replacement for」の関係）。
///
/// 幾何モデル: FrameImageは常にデコード済みフレーム全体を指したまま（Stretch="Fill"）で、
/// Width/Height/Marginだけを変える -- つまり「フレーム画像そのものをLevel倍に拡大し、
/// <see cref="ZoomState.OffsetX"/>/<see cref="ZoomState.OffsetY"/>が指す位置へ平行移動する」という
/// 単純なアフィン変換（拡大 + 平行移動、パン/スクロールは持たない）である（サブ領域を別途クロップ
/// したビットマップを作るわけではない）。Level&gt;1では、レターボックスの「詰まっている」軸
/// （baseContentRectのWidth/HeightのうちVideoView寸法と一致する方。詳細は
/// <see cref="ZoomController.ComputeMaxZoomLevel"/>のコメント参照）は必然的にVideoViewの外へはみ出す。
///
/// このはみ出しがTextInputSlot側まで実際に描画されてしまわない理由はWPFのExplicit clip設定
/// （<c>UIElement.ClipToBounds</c>）ではなく、Grid/Panelのarrange時に標準で働く自動レイアウトクリップ
/// である: FrameImageはHorizontalAlignment="Left"/VerticalAlignment="Top"（非Stretch）のため、
/// arrange時のDesiredSizeは明示的なWidth/Height（Level倍された値）でそのまま決まるが、そのDesiredSize
/// が親から渡されたarrange slot（VideoView自身のGridセル＝VideoViewの実寸）を超える場合、WPFの
/// FrameworkElement.ArrangeCoreはそのarrange slotのサイズへ自動的にクリップを適用する
/// （このクリップは子のサイズが親のarrange slotを超えるケース全般で標準的に働く既定動作であり、
/// Grid/StackPanel/Border等どのPanelでも、ClipToBoundsの値に関わらず発生する）。実機クラス
/// （VideoView/LiveViewの実XAML構造）を使った再検証ハーネスで、<c>ClipToBounds=true/false</c>いずれでも
/// TextInputSlot側への描画漏れが一切発生しないことを確認済み。<c>ClipToBounds</c>が意味を持つのは、
/// これとは別の話 -- RenderTransformによる拡大やCanvas上の絶対配置など、通常のarrange経由をバイパスして
/// 要素の描画結果だけが自身のレイアウトサイズを超えるケースで、要素自身の境界へクリップしたい場合である
/// （今回のFrameImageは明示Width/Height経由の通常arrangeなので該当しない）。はみ出しをどこまで許容するかの
/// 上限は<see cref="ZoomController"/>が§4のfit-to-windowキャップとして管理する（このクラス自身は
/// <see cref="ZoomState.Level"/>/<see cref="ZoomState.OffsetX"/>/<see cref="ZoomState.OffsetY"/>を
/// 検証・クランプしない -- 渡された値をそのまま信頼する純粋関数）。
/// </summary>
public static class ZoomedContentRectCalculator
{
    /// <summary>
    /// <paramref name="frame"/>は現在の幾何計算そのものには使わない（<see cref="ZoomState"/>と
    /// <paramref name="baseContentRect"/>だけで完結する）が、<see cref="Video.VideoView.ContentRectOverride"/>
    /// のデリゲート形状 -- ひいてはzoom-magnifier PLAN.md §2の「ZoomState + 現在のRenderedContentRect/
    /// FrameGeometry」というシグネチャ -- に合わせて引数として残している。
    /// </summary>
    public static RenderedContentRect Compute(ZoomState zoomState, RenderedContentRect baseContentRect, FrameGeometry frame)
    {
        if (zoomState.Level <= ZoomState.MinLevel)
        {
            return baseContentRect;
        }

        double width = baseContentRect.Width * zoomState.Level;
        double height = baseContentRect.Height * zoomState.Level;

        // OffsetX/OffsetYはbaseContentRectに対する比としてzoomedRectの左上端の位置を直接表す
        // （ZoomState.OffsetXのドキュメント参照）。ZoomControllerは「今カーソル直下にあるコンテンツ点」
        // が新しいLevelでも同じ画面位置に留まるようOffsetX/OffsetYを毎回導出するため、ここでは
        // それをそのまま適用するだけでよい -- 拡大率(1-Level)を織り込んだ間接的な式にしないのが
        // ポイント（連続スクロール時の位置ズレを防ぐ。ZoomController.OnPreviewMouseWheel参照）。
        double left = baseContentRect.Left + (zoomState.OffsetX * baseContentRect.Width);
        double top = baseContentRect.Top + (zoomState.OffsetY * baseContentRect.Height);

        return new RenderedContentRect(left, top, width, height);
    }
}
