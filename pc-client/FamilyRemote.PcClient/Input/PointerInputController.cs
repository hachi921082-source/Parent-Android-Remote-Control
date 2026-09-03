using System.Windows;
using System.Windows.Input;
using FamilyRemote.PcClient.Common;
using FamilyRemote.PcClient.Net;
using FamilyRemote.PcClient.Video;

namespace FamilyRemote.PcClient.Input;

/// <summary>
/// <see cref="Video.VideoView"/>の映像面上でマウスジェスチャ（タップ=クリック、スワイプ=ドラッグ）を
/// 捕捉し、INPUT_TAP/INPUT_SWIPEとして中継する。docs/plans/pc-client/input-relay/PLAN.md §3/§4/§5
/// 参照 -- Android側 child/TapInputController.kt を移植したもの。
///
/// 座標マッピング（PLAN.md §3）は<see cref="Video.VideoView.CurrentContentRect"/>/
/// <see cref="Video.VideoView.ContentRectChanged"/>を外部から購読するだけ -- VideoView自体は
/// 一切変更しない（zoom-magnifier tier用の<c>ContentRectOverride</c>フックはVideoView内部で
/// 既に吸収済みのため、このクラスはズーム有無を意識しない、decode.PLAN.md §7）。
///
/// マーカー表示（<see cref="ValidGesture"/>経由）はPC操作者自身の生のマウス座標
/// （VideoViewのコントロールローカルpx、既にズーム/レターボックス後の実描画空間）を使う --
/// 親へ送るpayloadの座標（親フレームpxへスケール後）とは別物（PLAN.md §6）。
/// </summary>
public sealed class PointerInputController
{
    // Android側TapInputControllerの定数と同値（docs/plans/remote-input-control/PLAN.md §11 as-built）。
    private const double SampleIntervalMs = 16;
    private const int MaxPathPoints = 64;

    private readonly VideoView _video;
    private readonly Func<bool> _isInputAllowed;
    private readonly Func<MessageFrame, bool> _sendFrame;
    private readonly Func<FrameGeometry?> _currentGeometry;
    private readonly List<Point> _pathPoints = new();

    private RenderedContentRect? _contentRect;
    private Point _downPoint;
    private DateTime _downTimeUtc;
    private DateTime _lastSampleTimeUtc;
    private bool _dragging;

    /// <summary>有効な（破棄されなかった）タップ/スワイプが成立するたびに、操作者の生のマウス座標列
    /// （VideoViewローカルpx -- 単発ならタップ、複数ならスワイプ経路）で発火する。
    /// Input.TapMarkerOverlay.ShowMarker呼び出し用（PLAN.md §6）。</summary>
    public event Action<IReadOnlyList<Point>>? ValidGesture;

    public PointerInputController(
        VideoView video,
        Func<bool> isInputAllowed,
        Func<MessageFrame, bool> sendFrame,
        Func<FrameGeometry?> currentGeometry)
    {
        _video = video;
        _isInputAllowed = isInputAllowed;
        _sendFrame = sendFrame;
        _currentGeometry = currentGeometry;

        _contentRect = video.CurrentContentRect;
        video.ContentRectChanged += rect => _contentRect = rect;

        // Previewマウスイベントを使う理由はphysical-key-relay-pc-side/PLAN.md のPreviewKeyDown
        // 選択と同じ（子コントロールの既定処理より先に確実に見るため） -- PLAN.md §4参照。
        video.PreviewMouseLeftButtonDown += OnMouseDown;
        video.PreviewMouseMove += OnMouseMove;
        video.PreviewMouseLeftButtonUp += OnMouseUp;
    }

    private void OnMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (!_isInputAllowed())
        {
            return;
        }

        _downPoint = e.GetPosition(_video);
        _downTimeUtc = DateTime.UtcNow;
        _lastSampleTimeUtc = _downTimeUtc;
        _dragging = true;
        _pathPoints.Clear();
        _pathPoints.Add(_downPoint);

        // ウィンドウ外へマウスが出てもドラッグ継続を追跡できるようにする -- Androidのタッチが
        // ACTION_DOWNを受けたViewへ以後のイベントを配送し続けるのと同じ効果を得るためのWPF側の
        // 一般的な作法（PLAN.md自身が許容する「WPF固有のUIコントロール選択」の一つ）。
        _video.CaptureMouse();
    }

    private void OnMouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragging || !_isInputAllowed())
        {
            return;
        }

        DateTime now = DateTime.UtcNow;
        if ((now - _lastSampleTimeUtc).TotalMilliseconds >= SampleIntervalMs && _pathPoints.Count < MaxPathPoints)
        {
            _lastSampleTimeUtc = now;
            _pathPoints.Add(e.GetPosition(_video));
        }
    }

    private void OnMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!_dragging)
        {
            return;
        }

        _dragging = false;
        _video.ReleaseMouseCapture();

        if (!_isInputAllowed())
        {
            _pathPoints.Clear();
            return;
        }

        Point upPoint = e.GetPosition(_video);
        double durationMs = (DateTime.UtcNow - _downTimeUtc).TotalMilliseconds;
        double displacement = (upPoint - _downPoint).Length;

        if (displacement < SystemParameters.MinimumHorizontalDragDistance)
        {
            HandleTap(upPoint);
        }
        else
        {
            _pathPoints.Add(upPoint); // 離した点を必ず経路の終点にする（Android版の as-built ルール）。
            HandleSwipe(_pathPoints.ToList(), durationMs);
        }

        _pathPoints.Clear();
    }

    private void HandleTap(Point p)
    {
        if (_currentGeometry() is not { } geometry || _contentRect is not { } rect)
        {
            return;
        }

        if (MapPointToParentCoords(p, rect, geometry) is not { } mapped)
        {
            return;
        }

        ValidGesture?.Invoke(new[] { p });
        _sendFrame(new InputTapPayload(mapped.x, mapped.y).ToFrame());
    }

    private void HandleSwipe(List<Point> localPoints, double durationMs)
    {
        if (_currentGeometry() is not { } geometry || _contentRect is not { } rect)
        {
            return;
        }

        var mappedPoints = new List<InputPathPoint>(localPoints.Count);
        foreach (Point p in localPoints)
        {
            if (MapPointToParentCoords(p, rect, geometry) is not { } mapped)
            {
                // 1点でも枠外(レターボックス/ピラーボックスの黒帯)ならジェスチャ全体を破棄する
                // -- 一部だけ登録されたスワイプはクランプと同じくらい紛らわしいため、
                // PLAN.md §5 / Decision #11の拡張として、部分的な破棄はしない。
                return;
            }

            mappedPoints.Add(new InputPathPoint(mapped.x, mapped.y));
        }

        ValidGesture?.Invoke(localPoints);
        _sendFrame(new InputSwipePayload(mappedPoints, (long)durationMs).ToFrame());
    }

    /// <summary>PLAN.md §3のマッピング式そのまま。矩形外は破棄（null） -- クランプしない
    /// （Decision #11、docs/detailed-spec.md §15）。</summary>
    private static (int x, int y)? MapPointToParentCoords(Point p, RenderedContentRect contentRect, FrameGeometry frame)
    {
        if (contentRect.Width <= 0 || contentRect.Height <= 0)
        {
            return null;
        }

        if (p.X < contentRect.Left || p.X >= contentRect.Left + contentRect.Width ||
            p.Y < contentRect.Top || p.Y >= contentRect.Top + contentRect.Height)
        {
            return null;
        }

        double fracX = (p.X - contentRect.Left) / contentRect.Width;
        double fracY = (p.Y - contentRect.Top) / contentRect.Height;
        return ((int)(fracX * frame.ParentFrameWidth), (int)(fracY * frame.ParentFrameHeight));
    }
}
