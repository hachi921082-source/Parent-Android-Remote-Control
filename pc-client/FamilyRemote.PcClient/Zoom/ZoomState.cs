namespace FamilyRemote.PcClient.Zoom;

/// <summary>
/// 現在のズーム倍率と、ズーム矩形の位置（オフセット）を保持する単一の真実の源。zoom-magnifier
/// PLAN.md §2参照。Session/ConnectionStateStore.csの`event Action&lt;T&gt;?`パターンに倣い、Rx等を
/// 使わないプレーンなC#イベントで変更を通知する（このtierでは購読者は今のところ
/// <see cref="ZoomController"/>のみだが、将来ズーム率表示等のUIが追加された場合に備え、
/// ConnectionStateStore.StateChanged等と同じ形の公開APIにしておく）。
///
/// ライフサイクル: <see cref="ZoomController"/>と共にSession/LiveView.xaml.csのコンストラクタで毎回
/// 新規生成される。MainWindow.xaml.csの分析により、Live&lt;-&gt;Reconnecting往復ではLiveView自体が
/// 再構築されず、新規Live突入時のみ新しいLiveViewインスタンス（=新しいZoomState）が作られる --
/// これによりzoom-magnifier PLAN.md §6が要求する「新規Live突入毎にLevel=1.0へリセット」を、
/// 追加のConnectionState購読/リセットロジックなしに自動的に満たす。
/// </summary>
public sealed class ZoomState
{
    /// <summary>ズーム未適用時の値であり、同時に下限（フロア）でもある（PLAN.md §3）。</summary>
    public const double MinLevel = 1.0;

    /// <summary>現在のズーム倍率。<see cref="MinLevel"/>が下限。</summary>
    public double Level { get; private set; } = MinLevel;

    /// <summary>
    /// ズーム矩形の左端が、未ズーム時のcontent rect（<c>baseContentRect</c>）の左端からどれだけ
    /// ずれているかを、<c>baseContentRect.Width</c>に対する比で表したオフセット
    /// （<see cref="ZoomedContentRectCalculator.Compute"/>参照: <c>zoomedRect.Left = baseContentRect.Left
    /// + OffsetX * baseContentRect.Width</c>）。「ズーム中サブ領域の中心点」ではなく敢えて
    /// 左上基準のオフセットとして持つのは、<see cref="ZoomController"/>のカーソル追従ズーム
    /// （PLAN.md §3）が「現在のズーム済み矩形の中でカーソル直下にあるコンテンツ点を、次のズーム
    /// レベルでも同じ画面位置に留める」という漸化的な計算になり、単純な「フレーム全体に対する中心
    /// フラクション」を毎回そのまま再利用する方式では2回目以降の連続スクロール（カーソル移動を伴う
    /// もの）でズレが生じるため（実装時に検出・修正 -- ZoomController.OnPreviewMouseWheelのコメント
    /// 参照）。<see cref="Level"/>が<see cref="MinLevel"/>の間はどの値でも描画結果に影響しない。
    /// </summary>
    public double OffsetX { get; private set; }

    /// <summary><see cref="OffsetX"/>のY版（<c>baseContentRect.Height</c>に対する比）。</summary>
    public double OffsetY { get; private set; }

    /// <summary><see cref="Level"/>/<see cref="OffsetX"/>/<see cref="OffsetY"/>のいずれかが変わるたびに発火する。</summary>
    public event Action? Changed;

    /// <summary>
    /// <see cref="ZoomController"/>専用の更新口。フロア/上限クランプ済みの値を渡す想定 --
    /// このクラス自身はバリデーションを行わない単純な保持役に徹する。
    /// </summary>
    public void Set(double level, double offsetX, double offsetY)
    {
        if (level == Level && offsetX == OffsetX && offsetY == OffsetY)
        {
            return;
        }

        Level = level;
        OffsetX = offsetX;
        OffsetY = offsetY;
        Changed?.Invoke();
    }
}
