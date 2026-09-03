using System.Buffers.Binary;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// VIDEO_FRAME（parent -&gt; child/PC）メッセージのペイロード形式。docs/plans/README.md §4:
/// <c>[4バイトframeWidth][4バイトframeHeight][8バイトpresentationTimeUs][Annex-B NALバイト列]</c>
/// （すべてbig-endian -- Android側がDataOutputStream.writeInt/writeLongで書き出すため。
/// Net/MessageFrame.csが自身のヘッダをBinaryPrimitives.WriteInt32BigEndianで扱っているのと同じ規約）。
/// Android側 app/src/main/java/com/familyremote/app/net/VideoFrameMessage.kt の<c>parse</c>を
/// 移植したもの -- PC側はVIDEO_FRAMEを送らないためパース方向のみ実装する。
/// </summary>
public sealed record VideoFrameMessage(int FrameWidth, int FrameHeight, long PresentationTimeUs, byte[] NalData)
{
    private const int HeaderSizeBytes = 4 + 4 + 8;

    public static VideoFrameMessage Parse(MessageFrame frame)
    {
        if (frame.Type != MessageType.VideoFrame)
        {
            throw new ArgumentException($"Not a VIDEO_FRAME message: {frame.Type}");
        }

        ReadOnlySpan<byte> payload = frame.Payload;
        int frameWidth = BinaryPrimitives.ReadInt32BigEndian(payload);
        int frameHeight = BinaryPrimitives.ReadInt32BigEndian(payload[4..]);
        long presentationTimeUs = BinaryPrimitives.ReadInt64BigEndian(payload[8..]);
        byte[] nalData = payload[HeaderSizeBytes..].ToArray();
        return new VideoFrameMessage(frameWidth, frameHeight, presentationTimeUs, nalData);
    }
}
