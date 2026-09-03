using System.IO;
using System.Net.Sockets;

namespace FamilyRemote.PcClient.Net;

/// <summary>
/// 確立済み（Live）セッションソケット上のフレーム読み取りループを所有する。
/// docs/plans/pc-client/video-and-session/connectivity.PLAN.md §4 step 2参照 -- Android側
/// ChildSessionChannel（app/src/main/java/com/familyremote/app/child/ChildSessionChannel.kt）と
/// 同じ役割だが、そちら（decode/connection-state-ui/input-relay向けの個別コールバック引数を
/// 最初から持つ設計）とは異なり、このtierの時点ではどのハンドラも存在しないため、
/// MessageType別のディスパッチ機構そのものだけを提供する: <see cref="RegisterHandler"/> で
/// 型ごとにハンドラを登録し、<see cref="Start"/> で読み取りループを開始する。未登録の
/// MessageTypeのフレームは黙って無視する（後続tierがハンドラを登録すれば拾われるようになる）。
/// </summary>
public sealed class SessionChannel
{
    private readonly NetworkStream _stream;
    private readonly Dictionary<MessageType, Action<MessageFrame>> _handlers = new();
    private readonly object _writeLock = new();
    private volatile bool _closedByUs;

    public SessionChannel(NetworkStream stream)
    {
        _stream = stream;
    }

    /// <summary>
    /// 指定MessageTypeのフレームを受信した際に呼ばれるハンドラを登録する。<see cref="Start"/>より前に
    /// 呼ぶこと（読み取りループはバックグラウンドスレッドで動くため）。同じ型を再登録すると上書きする。
    /// </summary>
    public void RegisterHandler(MessageType type, Action<MessageFrame> handler)
    {
        _handlers[type] = handler;
    }

    /// <summary>読み取りループをバックグラウンドタスクとして開始する。</summary>
    public void Start()
    {
        Task.Run(ReadLoop);
    }

    /// <summary>
    /// 読み取りループがエラー（相手切断/ソケット例外等）で終了したことを通知する -- 自分から
    /// <see cref="Close"/>した場合は発火しない。connection-state-ui（未実装）がこれを購読して
    /// Reconnecting/Disconnected遷移を判断する想定。
    /// </summary>
    public event Action? ConnectionLost;

    private void ReadLoop()
    {
        try
        {
            while (true)
            {
                MessageFrame frame = MessageFrame.ReadFrom(_stream);
                if (_handlers.TryGetValue(frame.Type, out Action<MessageFrame>? handler))
                {
                    handler(frame);
                }
            }
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or SocketException)
        {
            if (!_closedByUs)
            {
                ConnectionLost?.Invoke();
            }
        }
    }

    /// <summary>
    /// このチャネル越しにフレームを送信する（input-relay等がINPUT_TAP等を送るのに使う想定）。
    /// ベストエフォート -- 失敗してもここでは再接続等の判断はせず、読み取りループ側の
    /// ConnectionLost通知に任せる（Android版ChildSessionChannel.sendFrameと同じ方針）。
    /// </summary>
    public bool SendFrame(MessageFrame frame)
    {
        if (_closedByUs)
        {
            return false;
        }

        try
        {
            lock (_writeLock)
            {
                frame.WriteTo(_stream);
            }

            return true;
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or SocketException)
        {
            return false;
        }
    }

    /// <summary>ソケットを閉じる -- セッション終了が既知の場合に使う（ConnectionLostは発火させない）。</summary>
    public void Close()
    {
        _closedByUs = true;
        try
        {
            _stream.Close();
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or SocketException)
        {
        }
    }
}
