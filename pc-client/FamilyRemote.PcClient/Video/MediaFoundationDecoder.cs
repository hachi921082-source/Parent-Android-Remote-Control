using System.Runtime.InteropServices;
using FamilyRemote.PcClient.Common;
using FamilyRemote.PcClient.Net;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace FamilyRemote.PcClient.Video;

/// <summary>
/// Windows Media Foundationの H.264デコーダMFT（Microsoft H.264 Decoder MFT、
/// CLSID_CMSH264DecoderMFT）を包む。Annex-B NALアクセスユニットを受け取り、RGB32ピクセルへ
/// デコードして<see cref="FrameDecoded"/>で通知する。docs/plans/pc-client/video-and-session/
/// decode.PLAN.md §3-5、Android側 app/src/main/java/com/familyremote/app/child/
/// VideoDecoderView.kt（の非View部分＝デコード制御ロジック）を移植したもの。
///
/// 所有: Session/ConnectionStateStore.cs（VideoViewではない） -- 新規Live突入時に1つだけ構築され、
/// Live/Reconnecting -&gt; Disconnected遷移で破棄される（decode.PLAN.md §4 step 1）。
///
/// スレッド設計: <see cref="FeedFrame"/>はSessionChannelの読み取りループ（バックグラウンドスレッド）
/// から直接呼ばれる想定 -- Android VideoDecoderView.onVideoFrameと同じ「デコードは呼び出しスレッド上で
/// 同期的に行い、UIに影響する最後の一手だけ呼び出し側がマーシャリングする」設計
/// （decode.PLAN.md §4 step 4）。<see cref="FrameDecoded"/>/<see cref="GeometryChanged"/>もその
/// 同じスレッドから発火するため、UIスレッドへのDispatcher.Invokeは購読側（VideoView）の責務。
///
/// MFTEnumEx（docs/plans/pc-client/video-and-session/decode.PLAN.md §3が許容する2つの選択肢の
/// もう一方）ではなく、既知のCLSIDを直接CoCreateInstanceする方式を採用 -- Vortice.MediaFoundationは
/// IMFActivateの配列マーシャリングを要するMFTEnumExより、単一MFTのCoCreateInstanceの方が
/// 単純に相互運用できるため（同PLAN §3「同一スコープの代替」として許容されている）。
/// </summary>
public sealed class MediaFoundationDecoder : IDisposable
{
    // Microsoft H.264 Decoder MFT（mfidl.h の CLSID_CMSH264DecoderMFT）。
    private static readonly Guid ClsidCmsH264DecoderMft = new("62CE7E72-4C71-4d20-B15D-452831A87D9D");

    // codecapi.h の CODECAPI_AVLowLatencyMode -- ICodecAPI.SetValueに渡すパラメータGUID
    // （decode.PLAN.md §5、Video/ICodecApi.cs参照）。
    private static readonly Guid CodecApiAvLowLatencyMode = new("9C27891A-ED7A-40E1-88E8-B22727A024EE");

    private const uint ClsctxInprocServer = 0x1;

    // MFT_OUTPUT_STREAM_PROVIDES_SAMPLES（mftransform.h）。立っていればMFT自身が出力サンプルを
    // 確保するため呼び出し側はSample未設定のOutputDataBufferを渡す -- ソフトウェアRGB32出力の
    // Microsoft H.264デコーダでは通常立たないが、念のため毎回チェックする。
    private const int MftOutputStreamProvidesSamples = 0x00000100;

    private IMFTransform? _transform;
    private FrameGeometry? _configuredGeometry;
    private int _outputWidth;
    private int _outputHeight;
    private int _outputStride;
    private bool _disposed;

    /// <summary>直近フィード成功時点のFrameGeometry -- VideoViewのコンテンツ矩形再計算に使う。</summary>
    public FrameGeometry? CurrentGeometry { get; private set; }

    /// <summary>
    /// デコード済み1フレームが得られるたびに発火する（RGB32、行方向の詰め物は除去済み --
    /// pixels.Length == width * height * 4）。呼び出しスレッドは<see cref="FeedFrame"/>を呼んだ
    /// スレッド（デコードループ）そのもの -- クラス冒頭のスレッド設計の注記を参照。
    /// </summary>
    public event Action<byte[], int, int>? FrameDecoded;

    /// <summary>VIDEO_FRAMEヘッダのdimensionが変化した（=新しいFrameGeometry）ときに発火する。
    /// 発火スレッドは<see cref="FrameDecoded"/>と同じ。</summary>
    public event Action<FrameGeometry>? GeometryChanged;

    public MediaFoundationDecoder()
    {
        MediaFactory.MFStartup(false);
    }

    /// <summary>
    /// 1件のVIDEO_FRAMEを供給する。ConnectionStateStore.OnConnectedが登録するMessageType.VideoFrame
    /// ハンドラから、SessionChannelの読み取りループ上で直接呼ばれる想定。
    /// </summary>
    public void FeedFrame(VideoFrameMessage message)
    {
        if (_disposed)
        {
            return;
        }

        var newGeometry = new FrameGeometry(message.FrameWidth, message.FrameHeight);
        if (CurrentGeometry != newGeometry)
        {
            CurrentGeometry = newGeometry;
            GeometryChanged?.Invoke(newGeometry);
            ReconfigureDecoder(newGeometry);
        }

        FeedDecoder(message.NalData, message.PresentationTimeUs);
    }

    private void ReconfigureDecoder(FrameGeometry geometry)
    {
        if (_configuredGeometry == geometry && _transform != null)
        {
            return;
        }

        ReleaseTransform();
        try
        {
            IMFTransform transform = CreateTransform();
            ConfigureLowLatency(transform);
            ConfigureInputType(transform, geometry);
            ConfigureOutputType(transform);
            transform.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
            transform.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);

            _transform = transform;
            _configuredGeometry = geometry;
        }
        catch (Exception ex) when (ex is SharpGenException or COMException)
        {
            // このgeometryでの構成に失敗（例: 一時的なコーデックリソース競合） --
            // Android VideoDecoderView.reconfigureDecoderと同じ割り切りで、_transformをnullの
            // ままにして戻る。CurrentGeometryは既に更新済みのため、同一解像度が続く限り
            // 再試行はされない（Android版の既存の挙動をそのまま移植 -- このtierで新たに
            // 頑健化はしない）。
            ReleaseTransform();
        }
    }

    private static IMFTransform CreateTransform()
    {
        Guid clsid = ClsidCmsH264DecoderMft;
        Guid iid = typeof(IMFTransform).GUID;
        int hr = CoCreateInstance(ref clsid, IntPtr.Zero, ClsctxInprocServer, ref iid, out IntPtr ppv);
        Marshal.ThrowExceptionForHR(hr);
        return new IMFTransform(ppv);
    }

    /// <summary>
    /// CODECAPI_AVLowLatencyMode = true を設定する（decode.PLAN.md §5、firm product requirement）。
    /// MFT作成直後・最初のProcessInputより前に必ず呼ぶこと。
    /// </summary>
    private static void ConfigureLowLatency(IMFTransform transform)
    {
        object codecApiObj;
        try
        {
            codecApiObj = Marshal.GetTypedObjectForIUnknown(transform.NativePointer, typeof(ICodecApi));
        }
        catch (InvalidCastException)
        {
            // このMFT実装がICodecAPIを公開しない場合（実運用のMicrosoft H.264デコーダでは
            // 起こらない想定だが、念のためのガード）-- 低遅延設定なしで続行するしかない。
            return;
        }

        try
        {
            var codecApi = (ICodecApi)codecApiObj;
            Guid api = CodecApiAvLowLatencyMode;
            object value = true;
            codecApi.SetValue(ref api, ref value);
        }
        finally
        {
            Marshal.ReleaseComObject(codecApiObj);
        }
    }

    private static void ConfigureInputType(IMFTransform transform, FrameGeometry geometry)
    {
        IMFMediaType inputType = MediaFactory.MFCreateMediaType();
        try
        {
            inputType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            inputType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
            inputType.Set(MediaTypeAttributeKeys.FrameSize, PackFrameSize(geometry.ParentFrameWidth, geometry.ParentFrameHeight));
            // csd-0/csd-1は与えない -- SPS/PPSはAnnex-B開始コード付きでNALストリームにin-bandで
            // 載る（decode.PLAN.md §4冒頭、送信側=Android parentの実装と同一）。
            transform.SetInputType(0, inputType, 0);
        }
        finally
        {
            inputType.Dispose();
        }
    }

    /// <summary>
    /// 出力タイプをRGB32で確定する（decode.PLAN.md §4 step 2 -- WriteableBitmapへ渡す前の
    /// 別途のCPU側色変換ステップを避けるための選択）。交渉成立後の実際のFrameSize/
    /// DefaultStrideを<see cref="_outputWidth"/>/<see cref="_outputHeight"/>/<see cref="_outputStride"/>
    /// に記録する -- H.264のマクロブロック整列により、入力geometryとMFTが実際に報告する出力
    /// dimension/ストライドが厳密には一致しない可能性があるため、<see cref="EmitFrame"/>は
    /// 必ずこちらを信頼する。
    /// </summary>
    private void ConfigureOutputType(IMFTransform transform)
    {
        for (int i = 0; ; i++)
        {
            IMFMediaType candidate;
            try
            {
                candidate = transform.GetOutputAvailableType(0, i);
            }
            catch (SharpGenException)
            {
                throw new InvalidOperationException("H.264 decoder MFT offers no RGB32 output type.");
            }

            try
            {
                if (candidate.GetGUID(MediaTypeAttributeKeys.Subtype) != VideoFormatGuids.Rgb32)
                {
                    continue;
                }

                transform.SetOutputType(0, candidate, 0);
                ulong frameSize = candidate.GetUInt64(MediaTypeAttributeKeys.FrameSize);
                _outputWidth = (int)(frameSize >> 32);
                _outputHeight = (int)(frameSize & 0xFFFFFFFF);
                _outputStride = TryGetStride(candidate, _outputWidth);
                return;
            }
            finally
            {
                candidate.Dispose();
            }
        }
    }

    private static int TryGetStride(IMFMediaType type, int width)
    {
        try
        {
            uint stride = type.GetUInt32(MediaTypeAttributeKeys.DefaultStride);
            return unchecked((int)stride);
        }
        catch (SharpGenException)
        {
            // MF_MT_DEFAULT_STRIDE属性が未設定 -- RGB32の詰め物なし既定ストライドを仮定する。
            return width * 4;
        }
    }

    private static ulong PackFrameSize(int width, int height) => ((ulong)(uint)width << 32) | (uint)height;

    private void FeedDecoder(byte[] nalData, long presentationTimeUs)
    {
        IMFTransform? transform = _transform;
        if (transform == null)
        {
            return;
        }

        try
        {
            IMFMediaBuffer buffer = MediaFactory.MFCreateMemoryBuffer(nalData.Length);
            try
            {
                buffer.Lock(out IntPtr ptr, out int _, out int _);
                try
                {
                    Marshal.Copy(nalData, 0, ptr, nalData.Length);
                }
                finally
                {
                    buffer.Unlock();
                }
                buffer.CurrentLength = nalData.Length;

                IMFSample sample = MediaFactory.MFCreateSample();
                try
                {
                    sample.AddBuffer(buffer);
                    sample.SampleTime = presentationTimeUs * 10; // マイクロ秒 -> Media Foundationの100ns単位。
                    transform.ProcessInput(0, sample, 0);
                }
                finally
                {
                    sample.Dispose();
                }
            }
            finally
            {
                buffer.Dispose();
            }

            DrainOutput(transform);
        }
        catch (Exception ex) when (ex is SharpGenException or COMException)
        {
            // 破損/順序不正なNAL（TCP経由では通常起こらないが、再接続直後の境界などで万一
            // 起きた場合）-- このフレームを捨てて続行する。次のIDR（2秒間隔、
            // docs/plans/screen-sharing-core/PLAN.md §5）で自己修復する（decode.PLAN.md §4 step 5）。
        }
    }

    private void DrainOutput(IMFTransform transform)
    {
        OutputStreamInfo streamInfo = transform.GetOutputStreamInfo(0);
        bool providesSamples = (streamInfo.Flags & MftOutputStreamProvidesSamples) != 0;

        while (true)
        {
            IMFSample? ownedSample = null;
            if (!providesSamples)
            {
                IMFMediaBuffer ownedBuffer = MediaFactory.MFCreateMemoryBuffer(streamInfo.Size);
                ownedSample = MediaFactory.MFCreateSample();
                ownedSample.AddBuffer(ownedBuffer);
                ownedBuffer.Dispose();
            }

            var dataBuffer = new OutputDataBuffer
            {
                StreamID = 0,
                Sample = ownedSample!,
            };

            Result result = transform.ProcessOutput(ProcessOutputFlags.None, 1, ref dataBuffer, out ProcessOutputStatus _);
            if (result.Failure)
            {
                // MF_E_TRANSFORM_NEED_MORE_INPUT（もっとよくある）や、その他の一時的な失敗 --
                // このProcessInput分の出力はもう無い。MFTは内部バッファリングを行うため、
                // 1入力につき0個・1個・複数個のいずれの出力もあり得る（decode.PLAN.md §4 step 4）。
                dataBuffer.Sample?.Dispose();
                break;
            }

            EmitFrame(dataBuffer.Sample);
            dataBuffer.Sample?.Dispose();
        }
    }

    private void EmitFrame(IMFSample? sample)
    {
        if (sample == null || _outputWidth <= 0 || _outputHeight <= 0)
        {
            return;
        }

        IMFMediaBuffer contiguous = sample.ConvertToContiguousBuffer();
        try
        {
            contiguous.Lock(out IntPtr ptr, out int _, out int currentLength);
            try
            {
                int tightStride = _outputWidth * 4;
                int stride = _outputStride > 0 ? _outputStride : tightStride;
                byte[] pixels = new byte[tightStride * _outputHeight];

                if (stride == tightStride)
                {
                    int copyLength = Math.Min(currentLength, pixels.Length);
                    Marshal.Copy(ptr, pixels, 0, copyLength);
                }
                else
                {
                    // ストライドに詰め物がある（幅がマクロブロック境界に切り上げられている等）場合、
                    // 行ごとにコピーして詰め物のないバッファへ正規化する -- FrameDecodedの契約
                    // （pixels.Length == width * height * 4）を常に満たすため。
                    for (int row = 0; row < _outputHeight; row++)
                    {
                        IntPtr rowPtr = ptr + row * stride;
                        Marshal.Copy(rowPtr, pixels, row * tightStride, tightStride);
                    }
                }

                FrameDecoded?.Invoke(pixels, _outputWidth, _outputHeight);
            }
            finally
            {
                contiguous.Unlock();
            }
        }
        finally
        {
            contiguous.Dispose();
        }
    }

    private void ReleaseTransform()
    {
        if (_transform != null)
        {
            try
            {
                _transform.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero);
            }
            catch (Exception ex) when (ex is SharpGenException or COMException)
            {
            }

            _transform.Dispose();
            _transform = null;
        }

        _configuredGeometry = null;
        _outputWidth = 0;
        _outputHeight = 0;
        _outputStride = 0;
    }

    /// <summary>
    /// ConnectionStateStore.OnDisconnectedDetected / Teardownから呼ばれる（decode.PLAN.md §4 step 1）。
    /// </summary>
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        ReleaseTransform();
        MediaFactory.MFShutdown();
    }

    [DllImport("ole32.dll", ExactSpelling = true)]
    private static extern int CoCreateInstance(
        ref Guid rclsid, IntPtr pUnkOuter, uint dwClsContext, ref Guid riid, out IntPtr ppv);
}
