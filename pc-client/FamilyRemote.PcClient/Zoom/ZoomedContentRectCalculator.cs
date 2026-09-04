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
/// WPFのPanel（Grid）はデフォルトでは子要素をクリップしないため、このはみ出しは自動的には見えなく
/// ならない -- <see cref="Session.LiveView"/>のコンストラクタがVideoViewインスタンス自身へ明示的に
/// <c>ClipToBounds = true</c>を設定していることで初めて、はみ出した部分がVideoViewの境界で
/// 実際にクリップされ見えなくなる（これが「ズームイン」の見た目の実体）。はみ出しをどこまで許容するかの上限は
/// <see cref="ZoomController"/>が§4のfit-to-windowキャップとして管理する（このクラス自身は
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
