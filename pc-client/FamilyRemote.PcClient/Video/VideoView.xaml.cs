using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FamilyRemote.PcClient.Common;

namespace FamilyRemote.PcClient.Video;

/// <summary>
/// VideoView.xamlのコードビハインド。docs/plans/pc-client/video-and-session/decode.PLAN.md §2/§6
/// 参照。Android側 VideoDecoderView.kt のView側の役割（デコード制御自体は
/// <see cref="MediaFoundationDecoder"/>が持つ）に相当する -- <see cref="MediaFoundationDecoder.FrameDecoded"/>
/// /<see cref="MediaFoundationDecoder.GeometryChanged"/>を購読し、<see cref="WriteableBitmap"/>への
/// ピクセルコピーと<see cref="RenderedContentRect"/>再計算をUIスレッドへホップして行う
/// （decode.PLAN.md §4 step 4、Android側の<c>post {}</c>ホップと同じ役割）。
///
/// XAMLで宣言的に配置できるよう既定コンストラクタを持ち、<see cref="Attach"/>で
/// <see cref="MediaFoundationDecoder"/>を後から結びつける（Session/LiveView.xaml/.xaml.cs参照）。
/// <see cref="MediaFoundationDecoder"/>自体のライフサイクルはConnectionStateStoreが所有する
/// （decode.PLAN.md §4 step 1）-- このコントロールは購読するだけで破棄責任は持たない。
/// </summary>
public partial class VideoView : UserControl
{
    private MediaFoundationDecoder? _decoder;
    private WriteableBitmap? _bitmap;
    private FrameGeometry? _lastGeometry;

    public VideoView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    /// <summary>
    /// このVideoViewが描画対象とするデコーダを結びつける。LiveView.xaml.csのコンストラクタから、
    /// 生成直後に一度だけ呼ばれる想定（<c>ConnectionStateStore.Decoder</c>は新規Live突入時点で
    /// 既に非null -- decode.PLAN.md §4 step 1）。
    /// </summary>
    public void Attach(MediaFoundationDecoder decoder)
    {
        _decoder = decoder;
        if (IsLoaded)
        {
            Subscribe();
        }
    }

    private void OnLoaded(object sender, RoutedEventArgs e) => Subscribe();

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        if (_decoder == null)
        {
            return;
        }

        _decoder.FrameDecoded -= OnFrameDecoded;
        _decoder.GeometryChanged -= OnGeometryChanged;
    }

    private void Subscribe()
    {
        if (_decoder == null)
        {
            return;
        }

        _decoder.FrameDecoded += OnFrameDecoded;
        _decoder.GeometryChanged += OnGeometryChanged;

        // 既にライブ中の再接続直後などで、購読前に最初のジオメトリが届いている場合の初期同期
        // （Live<->Reconnecting往復でVideoView自体は再構築されないため、通常は不要だが安全のため）。
        if (_decoder.CurrentGeometry is { } geometry)
        {
            _lastGeometry = geometry;
            RecomputeContentRect();
        }
    }

    private void OnSizeChanged(object sender, SizeChangedEventArgs e) => RecomputeContentRect();

    /// <summary>MediaFoundationDecoderのデコードループスレッド（バックグラウンド）から呼ばれる。</summary>
    private void OnGeometryChanged(FrameGeometry geometry)
    {
        Dispatcher.BeginInvoke(() =>
        {
            _lastGeometry = geometry;
            RecomputeContentRect();
        });
    }

    /// <summary>MediaFoundationDecoderのデコードループスレッド（バックグラウンド）から呼ばれる。
    /// <paramref name="pixels"/>はRGB32（詰め物なし、pixels.Length == width * height * 4）。</summary>
    private void OnFrameDecoded(byte[] pixels, int width, int height)
    {
        Dispatcher.BeginInvoke(() =>
        {
            EnsureBitmap(width, height);
            _bitmap!.WritePixels(new Int32Rect(0, 0, width, height), pixels, width * 4, 0);
        });
    }

    private void EnsureBitmap(int width, int height)
    {
        if (_bitmap != null && _bitmap.PixelWidth == width && _bitmap.PixelHeight == height)
        {
            return;
        }

        // MFVideoFormat_RGB32のメモリレイアウト（B,G,R,Xバイト順の32bpp、アルファなし）は
        // WPFのPixelFormats.Bgr32とそのまま一致する（decode.PLAN.md §4 step 2）。
        _bitmap = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgr32, null);
        FrameImage.Source = _bitmap;
    }

    private void RecomputeContentRect()
    {
        if (_lastGeometry is not { } geometry || ActualWidth <= 0 || ActualHeight <= 0)
        {
            return;
        }

        RenderedContentRect rect = LetterboxLayoutCalculator.ComputeContentRect(ActualWidth, ActualHeight, geometry);
        FrameImage.Margin = new Thickness(rect.Left, rect.Top, 0, 0);
        FrameImage.Width = rect.Width;
        FrameImage.Height = rect.Height;
    }
}
