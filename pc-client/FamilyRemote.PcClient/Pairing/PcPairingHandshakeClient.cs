using System.IO;
using System.Net.Sockets;
using System.Text.Json;
using FamilyRemote.PcClient.Common;
using FamilyRemote.PcClient.Net;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// PCが開始側となるペアリングハンドシェイク。docs/plans/pc-client/pairing-pc-side/PLAN.md §6
/// 参照 -- Android側 ChildPairingScanActivity/PairingClient
/// （app/src/main/java/com/familyremote/app/pairing/PairingClient.kt）と同じ手順を、QRスキャン
/// ではなくオペレーターの手入力（host+code）から開始する。
/// </summary>
public static class PcPairingHandshakeClient
{
    private const int ConnectTimeoutMs = 15_000;
    private const int SocketIoTimeoutMs = 15_000;

    /// <summary>
    /// PLAN.md §6の手順1-5を実行する。呼び出し前に正規化済み(<see cref="CrockfordBase32.Normalize"/>)
    /// のコードを渡すこと。ブロッキングソケットI/Oを使うため、バックグラウンドスレッド
    /// (<see cref="Task.Run(Action)"/>)上で実行する。
    /// </summary>
    public static Task<PairingHandshakeResult> PerformHandshakeAsync(
        string parentHost,
        string normalizedCode,
        string childHost) =>
        Task.Run(() => PerformHandshake(parentHost, normalizedCode, childHost));

    private static PairingHandshakeResult PerformHandshake(string parentHost, string normalizedCode, string childHost)
    {
        try
        {
            string proposedSecret = SecureTokenGenerator.GenerateSharedSecret();

            using var client = new TcpClient();
            Task connectTask = client.ConnectAsync(parentHost, Constants.PairingPort);
            if (!connectTask.Wait(ConnectTimeoutMs))
            {
                return PairingHandshakeResult.Failure("Connection timed out");
            }

            // Wait()がtrueを返した後にconnectTask自体を待つことで、接続失敗時の例外を伝播させる。
            connectTask.GetAwaiter().GetResult();

            using NetworkStream stream = client.GetStream();
            stream.ReadTimeout = SocketIoTimeoutMs;
            stream.WriteTimeout = SocketIoTimeoutMs;

            var hello = new PairingHelloPayload(normalizedCode, childHost, proposedSecret);
            MessageFrame.Of(MessageType.PairingHello, hello.ToJson()).WriteTo(stream);

            MessageFrame frame = MessageFrame.ReadFrom(stream);
            if (frame.Type != MessageType.PairingAck)
            {
                return PairingHandshakeResult.Failure($"Unexpected response type: {frame.Type}");
            }

            PairingAckPayload ack = PairingAckPayload.Parse(frame.PayloadAsUtf8String());
            return PairingHandshakeResult.Success(ack.ChildId, proposedSecret);
        }
        catch (Exception ex) when (ex is SocketException or IOException or ObjectDisposedException or JsonException)
        {
            // JsonExceptionはPairingAckPayload.Parseが不正/破損したPAIRING_ACK JSONを受け取った場合
            // （childIdフィールドのGuid形式不正を含む）にThrowHelper経由でも投げられる -- #13参照。
            return PairingHandshakeResult.Failure(ex.Message);
        }
    }
}
