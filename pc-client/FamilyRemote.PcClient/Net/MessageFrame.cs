using System.Buffers.Binary;
using System.IO;
using System.Text;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// 1メッセージフレーム: [4バイトbig-endian長][1バイトMessageType][ペイロード]。
/// docs/plans/README.md §4 / Android側 app/src/main/java/com/familyremote/app/net/MessageFrame.kt
/// と同一のワイヤ形式を持つ。lengthはtypeバイト+ペイロードの合計。
/// 同期I/O（Stream.ReadExactly/Write）で実装 -- 呼び出し側（PcPairingHandshakeClient等）が
/// バックグラウンドスレッドから呼ぶ前提（Android版がDispatchers.IOのブロッキングソケットで
/// 実装しているのと同じ設計）。
/// </summary>
public sealed class MessageFrame
{
    /// <summary>不正/破損ストリームで巨大バッファを確保しないための安全上限。</summary>
    private const int MaxFrameLength = 16 * 1024 * 1024;

    public MessageType Type { get; }
    public byte[] Payload { get; }

    public MessageFrame(MessageType type, byte[] payload)
    {
        Type = type;
        Payload = payload;
    }

    public static MessageFrame Of(MessageType type, string payloadJson) =>
        new(type, Encoding.UTF8.GetBytes(payloadJson));

    public string PayloadAsUtf8String() => Encoding.UTF8.GetString(Payload);

    public void WriteTo(Stream stream)
    {
        int length = 1 + Payload.Length;
        Span<byte> header = stackalloc byte[5];
        BinaryPrimitives.WriteInt32BigEndian(header, length);
        header[4] = (byte)Type;
        stream.Write(header);
        stream.Write(Payload);
        stream.Flush();
    }

    public static MessageFrame ReadFrom(Stream stream)
    {
        Span<byte> lengthBuffer = stackalloc byte[4];
        stream.ReadExactly(lengthBuffer);
        int length = BinaryPrimitives.ReadInt32BigEndian(lengthBuffer);
        if (length is < 1 or > MaxFrameLength)
        {
            throw new InvalidDataException($"Invalid frame length: {length}");
        }

        Span<byte> typeBuffer = stackalloc byte[1];
        stream.ReadExactly(typeBuffer);
        var type = (MessageType)typeBuffer[0];

        byte[] payload = new byte[length - 1];
        stream.ReadExactly(payload);
        return new MessageFrame(type, payload);
    }
}
