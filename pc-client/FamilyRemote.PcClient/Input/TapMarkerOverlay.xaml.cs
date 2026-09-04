using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Input;

/// <summary>
/// TapMarkerOverlay.xamlのコードビハインド。docs/plans/pc-client/input-relay/PLAN.md §6/§8参照 --
/// Android側 child/TapMarkerOverlay.kt を移植したもの。有効な（破棄されなかった）タップ/スワイプの
/// たびに、Input.PointerInputController.ValidGestureから渡されたPC操作者自身の生のマウス座標
/// （VideoViewローカルpx）でマーカーを描き、<see cref="Common.Constants.TapMarkerFadeMs"/>ミリ秒で
/// フェードアウトする。ビューオンリーモードのバナー（PLAN.md §8）もここで表示する --
/// Session/LiveView.xaml上、Video上に重ねて配置できる唯一の要素のため。
/// </summary>
public partial class TapMarkerOverlay : UserControl
{
    private const double MarkerRadius = 32;
    private const double StrokeWidth = 8;

    public TapMarkerOverlay()
    {
        InitializeComponent();
    }

    /// <summary>マーカーを表示する -- 単一点はタップ(円)、複数点はスワイプ経路(折れ線)。既に
    /// フェード中のマーカーがあれば打ち切り、新しいマーカーをフル不透明度から描き直す
    /// （同時に2つ表示しない、PLAN.md §6、Android版と同じルール）。</summary>
    public void ShowMarker(IReadOnlyList<Point> points)
    {
        if (points.Count == 0)
        {
            return;
        }

        MarkerCanvas.Children.Clear();

        Shape shape = points.Count == 1 ? CreateCircle(points[0]) : CreatePath(points);
        MarkerCanvas.Children.Add(shape);

        var animation = new DoubleAnimation(1.0, 0.0, TimeSpan.FromMilliseconds(Constants.TapMarkerFadeMs));
        shape.BeginAnimation(UIElement.OpacityProperty, animation);
    }

    /// <summary>ビューオンリーモードのバナー表示切り替え（PLAN.md §8）。</summary>
    public void SetInputUnavailable(bool unavailable) =>
        BannerBorder.Visibility = unavailable ? Visibility.Visible : Visibility.Collapsed;

    private static Ellipse CreateCircle(Point center)
    {
        var ellipse = new Ellipse
        {
            Width = MarkerRadius * 2,
            Height = MarkerRadius * 2,
            Stroke = Brushes.White,
            StrokeThickness = StrokeWidth,
        };
        Canvas.SetLeft(ellipse, center.X - MarkerRadius);
        Canvas.SetTop(ellipse, center.Y - MarkerRadius);
        return ellipse;
    }

    private static Polyline CreatePath(IReadOnlyList<Point> points)
    {
        var polyline = new Polyline
        {
            Stroke = Brushes.White,
            StrokeThickness = StrokeWidth,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
        };
        foreach (Point p in points)
        {
            polyline.Points.Add(p);
        }

        return polyline;
    }
}
