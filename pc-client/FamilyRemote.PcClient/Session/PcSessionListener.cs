using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using FamilyRemote.PcClient.Common;
using FamilyRemote.PcClient.Net;
using FamilyRemote.PcClient.Pairing;

namespace FamilyRemote.PcClient.Session;

/// <summary>
/// `WaitingForParent`中、Constants.SessionPort上で親からの着信を待ち受ける。
/// docs/plans/pc-client/video-and-session/connectivity.PLAN.md §4参照 -- Android側
/// ChildSessionListener（app/src/main/java/com/familyremote/app/child/ChildSessionListener.kt）を
/// C#に移植したもの。常時稼働の待ち受けではなく、PCクライアントが明示的にWaitingForParentへ
/// 入るたびに<see cref="Start"/>される（同PLAN.md §4 step 1）。
/// </summary>
public sealed class PcSessionListener
{
    /// <summary>
    /// SESSION_HELLOのSharedSecretチェックにおける読み取りタイムアウト -- 相手が接続だけして
    /// 何も送ってこない場合に待ち受けループをブロックし続けないための保険。
    /// </summary>
    private const int HandshakeTimeoutMs = 15_000;

    private TcpListener? _listener;
    private volatile bool _stoppedByUs;

    /// <summary>
    /// 待ち受けを開始する（既に開いているリスナーがあれば再利用 -- §4 step 4のReconnecting再武装、
    /// および同一WaitingForParent内での複数回呼び出しに対応）。ハンドシェイクが成立した接続ごとに
    /// <paramref name="onConnected"/>を1回呼び、その時点でこのループは終了する（呼び出し側が
    /// Reconnecting等で再度受け付けたければ、リスナーを開いたまま再度Startを呼ぶ）。
    /// </summary>
    /// <param name="entry">選択中のペアリング -- SharedSecretの照合に使う。</param>
    /// <param name="onConnected">ハンドシェイク成立時、確立済みチャネルを渡して1回だけ呼ばれる。</param>
    /// <param name="onFailed">
    /// リスナーの起動自体に失敗した場合、または（Stop()によるものではない）予期しない
    /// ソケットエラーで受け付けループが終了した場合に呼ばれる。Stop()によるクリーンな中断では
    /// 呼ばれない（PLAN.md §4 step 3）。
    /// </param>
    public void Start(PcPairingEntry entry, Action<SessionChannel> onConnected, Action<string> onFailed)
    {
        _stoppedByUs = false;
        Task.Run(() => AcceptLoop(entry, onConnected, onFailed));
    }

    private async Task AcceptLoop(PcPairingEntry entry, Action<SessionChannel> onConnected, Action<string> onFailed)
    {
        TcpListener listener;
        try
        {
            listener = EnsureBoundListener();
        }
        catch (IOException ex)
        {
            onFailed(ex.Message);
            return;
        }

        while (true)
        {
            TcpClient client;
            try
            {
                client = await listener.AcceptTcpClientAsync().ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is ObjectDisposedException or SocketException)
            {
                // Stop()がリスナーを閉じたことによる中断はクリーンなキャンセルとして扱い、
                // エラー報告しない（PLAN.md §4 step 3）。それ以外（OS都合の異常切断等）は報告する。
                if (!_stoppedByUs)
                {
                    onFailed(ex.Message);
                }

                return;
            }

            if (TryValidateHandshake(client, entry, out NetworkStream? stream))
            {
                onConnected(new SessionChannel(stream!));
                return;
            }

            // SharedSecret不一致/不正フレーム -- ロックアウトせず待ち受けを継続する
            // （PLAN.md §4 step 2、Android版ChildSessionListenerと同じ低セキュリティ方針）。
        }
    }

    private TcpListener EnsureBoundListener()
    {
        if (_listener is { } existing)
        {
            return existing;
        }

        string? bindAddress = PcTailscaleAddressResolver.Resolve();
        if (bindAddress is null)
        {
            throw new IOException("no_tailscale_address");
        }

        var listener = new TcpListener(IPAddress.Parse(bindAddress), Constants.SessionPort);
        listener.Start();
        _listener = listener;
        return listener;
    }

    private static bool TryValidateHandshake(TcpClient client, PcPairingEntry entry, out NetworkStream? stream)
    {
        try
        {
            client.ReceiveTimeout = HandshakeTimeoutMs;
            NetworkStream netStream = client.GetStream();
            MessageFrame frame = MessageFrame.ReadFrom(netStream);
            if (frame.Type != MessageType.SessionHello)
            {
                client.Close();
                stream = null;
                return false;
            }

            SessionHelloPayload hello = SessionHelloPayload.Parse(frame.PayloadAsUtf8String());
            if (hello.SharedSecret != entry.SharedSecret)
            {
                client.Close();
                stream = null;
                return false;
            }

            client.ReceiveTimeout = 0;
            stream = netStream;
            return true;
        }
        catch (Exception ex) when (ex is IOException or SocketException or JsonException or InvalidDataException)
        {
            client.Close();
            stream = null;
            return false;
        }
    }

    /// <summary>
    /// `WaitingForParent`のCancel操作、または待ち受けを完全に終了させたい場合に呼ぶ。
    /// TcpListener.Stop()が保留中のAcceptTcpClientAsync()を中断させ、AcceptLoop側で
    /// クリーンなキャンセルとして扱われる（PLAN.md §4 step 3）。§4 step 4の「Live/Reconnecting間は
    /// リスナーを開いたまま」という要件があるため、Liveへ遷移しただけではこれを呼ばないこと。
    /// </summary>
    public void Stop()
    {
        _stoppedByUs = true;
        try
        {
            _listener?.Stop();
        }
        catch (SocketException)
        {
        }

        _listener = null;
    }
}
