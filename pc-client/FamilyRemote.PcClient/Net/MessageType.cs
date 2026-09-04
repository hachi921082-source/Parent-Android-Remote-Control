namespace FamilyRemote.PcClient.Net;

/// <summary>
/// Android側 com.familyremote.app.net.MessageType のタグ値とバイト単位で完全一致させる列挙体。
/// docs/plans/pc-client/README.md §5 参照。このtier（pairing-pc-side）が実際に(de)シリアライズする
/// のはPairingHello/PairingAckのみ（ハンドシェイクで使用）。残りのライブセッション系タグは、後続tier
/// （video-and-session/connectivity ほか）が実装で使う固定ワイヤ契約としてここに宣言しておく
/// -- Android側net/MessageType.ktが同じ理由で全タグを先出ししているのと同じ考え方（そちらのdoc
/// コメント参照）。値の追加/変更は行わない。
/// </summary>
public enum MessageType : byte
{
    // ライブセッションプロトコル（docs/plans/README.md §4）。ペイロードの(de)シリアライズは
    // 各機能が実装するまで未実装 -- タグ値のみここで固定する。
    SessionHello = 1,
    VideoFrame = 2,
    StatusHeartbeat = 3,
    StatusEvent = 4,
    AccessibilityStatus = 5,
    InputTap = 6,
    InputSwipe = 7,
    InputText = 8,
    TextInjectFailed = 9,
    SessionEnd = 10,
    InputKey = 11,
    SessionBusy = 12,

    // ペアリング時専用（一時的な別コネクション、ライブセッションプロトコルとは独立）。
    // このtierが実装する唯一の2値。
    PairingHello = 100,
    PairingAck = 101,
}
