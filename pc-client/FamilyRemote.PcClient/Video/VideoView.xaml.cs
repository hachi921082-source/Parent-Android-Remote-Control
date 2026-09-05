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
    /// 現在実際にvideoが描画されている矩形（コントロールローカルpx）-- ズーム未適用時は
    /// <see cref="Video.LetterboxLayoutCalculator"/>がそのまま返す素のレターボックス/ピラーボックス矩形、
    /// <see cref="ContentRectOverride"/>が設定されていればその戻り値。decode.PLAN.md §7 /
    /// zoom-magnifier PLAN.md §5 / input-relay PLAN.md §3が前提とする「両tierが購読する単一の
    /// current content rect observable」はこのプロパティ+<see cref="ContentRectChanged"/>がそれに当たる
    /// -- 幾何値がまだ届いていない（最初のVIDEO_FRAME受信前）場合はnull。
    /// </summary>
    public RenderedContentRect? CurrentContentRect { get; private set; }

    /// <summary>
    /// <see cref="CurrentContentRect"/>が変化するたびに発火する。常にUIスレッド上で呼ばれる
    /// （<see cref="RecomputeContentRect"/>自体がUIスレッド上でのみ呼ばれるため）。
    /// </summary>
    public event Action<RenderedContentRect>? ContentRectChanged;

    /// <summary>
    /// issue #39（Android側）/#43（PC側）: 描画中の映像がほぼ黒一色に見えるかどうか -- 親側で
    /// FLAG_SECURE保護されたアプリ（銀行アプリ等）が開かれていると純黒フレームになるという前提
    /// （Android側VideoDecoderView.checkContentBlankと同じ判定根拠）。PC側は
    /// <see cref="OnFrameDecoded"/>で既に生のRGB32ピクセルバッファへ直接アクセスできるため、
    /// Android側のようなGPUテクスチャの読み出し（TextureView.getBitmap()）は不要 -- 独立した
    /// ポーリングタイマーも設けず、デコード済みフレームが届くたびその場でサンプリングする。
    /// </summary>
    public bool IsContentBlank { get; private set; }

    /// <summary><see cref="IsContentBlank"/>が変化するたびに発火する。常にUIスレッド上。</summary>
    public event Action<bool>? IsContentBlankChanged;

    /// <summary>
    /// 素のレターボックス矩形をズーム後の矩形へ差し替えるためのフック(zoom-magnifier tier用)。
    /// 非nullなら<see cref="Video.LetterboxLayoutCalculator.ComputeContentRect"/>の結果と現在の
    /// <see cref="FrameGeometry"/>を渡し、その戻り値を実際の描画/<see cref="CurrentContentRect"/>として
    /// 使う。未設定（既定null）の間は素のレターボックス矩形がそのまま使われ、input-relay
    /// tierが先にzoom-magnifierを待たず単独で実装・動作できる（zoom未実装の間は恒等変換と同義）。
    /// </summary>
    public Func<RenderedContentRect, FrameGeometry, RenderedContentRect>? ContentRectOverride { get; set; }

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
        bool isBlank = IsFrameBlank(pixels);
        Dispatcher.BeginInvoke(() =>
        {
            EnsureBitmap(width, height);
            _bitmap!.WritePixels(new Int32Rect(0, 0, width, height), pixels, width * 4, 0);

            if (isBlank != IsContentBlank)
            {
                IsContentBlank = isBlank;
                SecureHintButton.Visibility = isBlank ? Visibility.Visible : Visibility.Collapsed;
                IsContentBlankChanged?.Invoke(isBlank);
            }
        });
    }

    /// <summary>
    /// 粗いサンプリングで十分（issue #43） -- 保護されたコンテンツは純黒(B=G=R=0)フレームになる
    /// ことが前提なので、フレーム全体を走査せず均等間隔でサンプリングするだけで確実に判定できる。
    /// デコードループのバックグラウンドスレッドから呼ぶ（UIスレッドへのホップ前、<see cref="OnFrameDecoded"/>
    /// 参照）-- 単純な配列読み取りのみでUI要素に触れないため安全。
    /// </summary>
    private static bool IsFrameBlank(byte[] pixels)
    {
        if (pixels.Length == 0)
        {
            return false;
        }

        int pixelCount = pixels.Length / BytesPerPixel;
        int sampleCount = Math.Min(BlankSampleCount, pixelCount);
        int stride = Math.Max(1, pixelCount / sampleCount);
        for (int i = 0; i < pixelCount; i += stride)
        {
            int offset = i * BytesPerPixel;
            if (pixels[offset] > BlankChannelThreshold ||
                pixels[offset + 1] > BlankChannelThreshold ||
                pixels[offset + 2] > BlankChannelThreshold)
            {
                return false;
            }
        }
        return true;
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

        RenderedContentRect baseRect = LetterboxLayoutCalculator.ComputeContentRect(ActualWidth, ActualHeight, geometry);
        RenderedContentRect rect = ContentRectOverride?.Invoke(baseRect, geometry) ?? baseRect;

        FrameImage.Margin = new Thickness(rect.Left, rect.Top, 0, 0);
        FrameImage.Width = rect.Width;
        FrameImage.Height = rect.Height;

        CurrentContentRect = rect;
        ContentRectChanged?.Invoke(rect);
    }

    private const int BytesPerPixel = 4;
    private const int BlankSampleCount = 64;
    private const int BlankChannelThreshold = 4;
}
