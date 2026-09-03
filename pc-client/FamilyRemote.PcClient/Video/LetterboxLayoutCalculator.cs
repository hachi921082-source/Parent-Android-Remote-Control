using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Video;

/// <summary>
/// レターボックス/ピラーボックス配置の純粋な幾何計算。Android側
/// app/src/main/java/com/familyremote/app/child/LetterboxLayoutCalculator.kt の
/// <c>computeContentRect</c>のみを移植したもの（<c>mapTouchToParentCoords</c>側は
/// input-relay tierの担当範囲のためここには含めない -- docs/plans/pc-client/video-and-session/
/// decode.PLAN.md §2参照）。<c>float</c>の代わりに<c>double</c>を使う以外はAndroid版と同一の
/// アルゴリズム。WPFのView/Controlに依存しない純粋関数として保つことで、<see cref="VideoView"/>
/// （描画）と、後続tierの入力マッピングが常に同じ計算結果を参照できるようにする。
/// </summary>
public static class LetterboxLayoutCalculator
{
    /// <summary>
    /// <paramref name="viewW"/>x<paramref name="viewH"/>のコントロール内で、<paramref name="frame"/>を
    /// 歪みなく（アスペクト比を保って）描画できるサブ矩形（コントロールローカルのピクセル座標）。
    /// 反対軸には黒帯が入る（<paramref name="frame"/>がコントロールより相対的に横長ならレターボックス、
    /// 縦長ならピラーボックス）。
    /// </summary>
    public static RenderedContentRect ComputeContentRect(double viewW, double viewH, FrameGeometry frame)
    {
        if (viewW <= 0 || viewH <= 0 || frame.ParentFrameWidth <= 0 || frame.ParentFrameHeight <= 0)
        {
            return new RenderedContentRect(0, 0, viewW, viewH);
        }

        double viewAspect = viewW / viewH;
        double frameAspect = (double)frame.ParentFrameWidth / frame.ParentFrameHeight;

        if (frameAspect > viewAspect)
        {
            // frameがviewより相対的に横長: 幅に合わせてフィットし、上下に黒帯。
            double h = viewW / frameAspect;
            return new RenderedContentRect(0, (viewH - h) / 2, viewW, h);
        }
        else
        {
            // frameがviewより相対的に縦長/幅狭: 高さに合わせてフィットし、左右に黒帯。
            double w = viewH * frameAspect;
            return new RenderedContentRect((viewW - w) / 2, 0, w, viewH);
        }
    }
}
