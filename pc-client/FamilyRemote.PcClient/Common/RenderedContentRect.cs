namespace FamilyRemote.PcClient.Common;

/// <summary>
/// <see cref="Video.LetterboxLayoutCalculator"/>が計算する、実際に映像が描画されるコントロール内
/// サブ矩形（コントロールローカルのピクセル座標）。docs/plans/pc-client/video-and-session/
/// decode.PLAN.md §2/§6参照。WPFの<c>System.Windows.Rect</c>を直接使わず独自の値型としているのは、
/// 後続tier（input-relay/zoom-magnifier、docs/plans/pc-client/video-and-session/decode.PLAN.md §10）
/// もこの値を消費するため、WPF非依存のフレームワーク中立な形にしておくため。
/// </summary>
public readonly record struct RenderedContentRect(double Left, double Top, double Width, double Height);
