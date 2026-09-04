using System.Windows;
using System.Windows.Input;
using FamilyRemote.PcClient.Common;
using FamilyRemote.PcClient.Video;

namespace FamilyRemote.PcClient.Zoom;

/// <summary>
/// マウスホイールのズームジェスチャーを<see cref="ZoomState"/>へ反映し、<see cref="Video.VideoView"/>
/// へ即座に描画させる。zoom-magnifier PLAN.md §2/§3/§4参照。Session/LiveView.xaml.csのコンストラクタ
/// から対象のVideoViewを渡して構築され、そのVideoViewインスタンスと1:1、同じ寿命で生存する
/// （decode.PLAN.md tierの<c>Video.Attach(decoder)</c>は後からデコーダを差し替える余地のある設計だが、
/// こちらはコンストラクタで一括結線する -- ZoomControllerとVideoViewの対応は生成時に固定で
/// 後から変わることがないため）。
///
/// VideoView.xaml/.xaml.csは一切編集しない制約（zoom-magnifier PLAN.md §6のオーケストレーション注記）
/// の下で、公開されている<see cref="Video.VideoView.ContentRectOverride"/>/
/// <see cref="UIElement.PreviewMouseWheel"/>/<see cref="FrameworkElement.SizeChanged"/>のみを
/// 外側から購読・設定して機能を実現する。
/// </summary>
public sealed class ZoomController
{
    /// <summary>ホイール1ノッチあたりのズーム倍率増減。zoom-magnifier PLAN.md §3の「例: 10%」に準拠。
    /// Android側に対応する値がなく、このtier内だけで完結するUI挙動の詳細のため、Common/Constants.cs
    /// （他tierとも数値を共有する定数専用）ではなくこちらに置く。</summary>
    private const double ZoomStepIncrement = 0.1;

    /// <summary><see cref="ForceRecompute"/>が行う「刻んで戻す」疑似リサイズの刻み幅（px）。WPFの
    /// レイアウト系が「実際に変化した」と判定できればよいだけの値のため、画面には現れない極小値で
    /// 十分（実装時にWPF単体テストプログラムで実測: 0.01px相当の変化でも
    /// <see cref="FrameworkElement.SizeChanged"/>は確実に発火し、最終的なActualWidth/Heightは
    /// 常に元の値へ正確に戻る）。</summary>
    private const double RecomputeNudge = 0.01;

    private readonly VideoView _video;
    private readonly ZoomState _state = new();

    /// <summary>
    /// 操作者がホイールで「要求した」ズーム倍率 -- fit-to-windowキャップ（PLAN.md §4）でクランプ
    /// される前の値。<see cref="_state"/>.Levelは常に<c>Math.Min(_desiredLevel, 現在のキャップ)</c>
    /// （<see cref="ReapplyLevel"/>参照）-- キャップより先に「クランプ済みのLevel」自体をホイール
    /// 操作の起点にしてしまうと、ウィンドウリサイズで一度キャップにより頭打ちになった値がその後
    /// ウィンドウが再び大きくなってキャップが緩んでも自動では戻らず、操作者が改めてスクロールし直す
    /// までズームが必要以上に低いまま「貼りつく」-- リサイズがWidth/Heightを別々のSizeChangedとして
    /// 順番に配送し、その中間状態（片方だけ変化した一時的に小さいサイズ）で一瞬だけ強いキャップが
    /// かかるケースで顕在化する。要求値と実効値を分離しておけば、キャップが変化するたび
    /// （<see cref="ReapplyLevel"/>を呼ぶだけで）実効値を要求値方向へ再評価でき、この「貼りつき」が
    /// 起きない。フロアのみで頭打ち（<see cref="ZoomState.MinLevel"/>未満にはならない）で、上限は
    /// 意図的に付けていない -- キャップより上まで要求値が伸びていても実際に描画される
    /// <see cref="ZoomState.Level"/>はキャップで抑えられるため実害はなく、後でウィンドウが十分
    /// 大きくなった時点でその要求値まで自然に追従できる方が望ましいため。
    /// </summary>
    private double _desiredLevel = ZoomState.MinLevel;

    /// <summary>
    /// 直近に<see cref="ComputeContentRect"/>（=<see cref="Video.VideoView.ContentRectOverride"/>）が
    /// 受け取ったFrameGeometry。ホイール操作時にVideoView外部から改めて問い合わせる公開手段がない
    /// （<see cref="MediaFoundationDecoder.GeometryChanged"/>はVideoView内部購読のみで外部非公開）ため
    /// ここへキャッシュしておく。まだ一度もフレームジオメトリが届いていない間はnull -- その間は
    /// ホイール操作を無視する（ズームすべき映像がまだない）。
    /// </summary>
    private FrameGeometry? _lastFrame;

    public ZoomController(VideoView video)
    {
        _video = video;
        _video.ContentRectOverride = ComputeContentRect;
        _video.PreviewMouseWheel += OnPreviewMouseWheel;
        _video.SizeChanged += OnSizeChanged;
    }

    /// <summary>
    /// <see cref="Video.VideoView.ContentRectOverride"/>に割り当てられる本体。VideoViewが自身の
    /// （private）RecomputeContentRect()を呼ぶたび -- 新規フレームジオメトリ受信時、実際の
    /// <see cref="FrameworkElement.SizeChanged"/>時、および<see cref="ForceRecompute"/>が誘発する
    /// 疑似SizeChanged時 -- に呼ばれる。baseContentRectは素のレターボックス矩形（未ズーム）、frameは
    /// 直近のFrameGeometry -- 呼ばれるたび両方とも最新値へ更新する。
    /// </summary>
    private RenderedContentRect ComputeContentRect(RenderedContentRect baseContentRect, FrameGeometry frame)
    {
        _lastFrame = frame;
        ReapplyLevel(_video.ActualWidth, _video.ActualHeight, frame);
        return ZoomedContentRectCalculator.Compute(_state, baseContentRect, frame);
    }

    /// <summary>
    /// マウスホイール1ノッチ = <see cref="ZoomStepIncrement"/>分のズーム段階変更（PLAN.md §3）。
    /// カーソル位置下のコンテンツ点が変化後も同じ画面位置に留まるよう<see cref="ZoomState"/>の
    /// オフセットを再計算してから<see cref="ForceRecompute"/>で即座に描画へ反映する。
    /// </summary>
    private void OnPreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        e.Handled = true;

        if (_lastFrame is not { } frame)
        {
            return; // 最初のVIDEO_FRAME到達前 -- ズームすべき映像がまだない。
        }

        double viewW = _video.ActualWidth;
        double viewH = _video.ActualHeight;
        if (viewW <= 0 || viewH <= 0)
        {
            return;
        }

        RenderedContentRect baseRect = LetterboxLayoutCalculator.ComputeContentRect(viewW, viewH, frame);
        RenderedContentRect currentRect = ZoomedContentRectCalculator.Compute(_state, baseRect, frame);

        double maxLevel = ComputeMaxZoomLevel(viewW, viewH, baseRect);
        double delta = Math.Sign(e.Delta) * ZoomStepIncrement;
        _desiredLevel = Math.Max(ZoomState.MinLevel, _desiredLevel + delta);
        double newLevel = Math.Min(_desiredLevel, maxLevel);
        if (newLevel == _state.Level)
        {
            return; // フロアで頭打ち、またはキャップで頭打ち（要求値だけがさらに伸びた）
                     // -- PLAN.md §3「floorはno-op、エラーではない」を上限側にも同じ扱いで適用している。
        }

        // ズーム変化後も同じ画面位置に留めたいコンテンツ上の点 -- フレーム全体（=currentRectが表す
        // 領域）に対する正規化フラクション。currentRect.Width/Heightは常に>0（baseRect.Width/Height>0
        // が前提のため）。
        Point cursor = e.GetPosition(_video);
        double contentFracX = Clamp01((cursor.X - currentRect.Left) / currentRect.Width);
        double contentFracY = Clamp01((cursor.Y - currentRect.Top) / currentRect.Height);

        // 導出（標準的な「カーソル位置固定ズーム」= オフセット+倍率モデル）: 新しいズーム矩形の
        // 左上をbaseRect.Left/Top + newOffsetX/Y * baseRect.Width/Heightとし
        // （ZoomedContentRectCalculator.Compute参照）、contentFracX/Yが指す点がカーソルの実際の
        // 画面位置(cursor.X, cursor.Y)に一致する条件から逆算する:
        //   cursor.X = baseRect.Left + newOffsetX*baseRect.Width + contentFracX*(baseRect.Width*newLevel)
        //   => newOffsetX = (cursor.X - baseRect.Left)/baseRect.Width - contentFracX*newLevel
        // 重要な点: contentFracXは毎回「今カーソル直下にある実際のコンテンツ点」を
        // currentRect（＝直前のOffsetX/Levelで描画されていた矩形）から求め直しており、newOffsetXも
        // 毎回baseRect基準で再導出しているため、既にズーム中の状態から更にスクロールしてもカーソルの
        // 現在位置に正しく一致する（前回スクロール時点のOffsetXをそのまま参照し続ける「中心
        // フラクションを使い回す」方式は、間にカーソルが動いた場合にズレる -- 実装時に検出・修正）。
        double newOffsetX = ((cursor.X - baseRect.Left) / baseRect.Width) - (contentFracX * newLevel);
        double newOffsetY = ((cursor.Y - baseRect.Top) / baseRect.Height) - (contentFracY * newLevel);

        _state.Set(newLevel, newOffsetX, newOffsetY);
        ForceRecompute();
    }

    /// <summary>
    /// ウィンドウリサイズ実況時の再評価（PLAN.md §4「continuously re-evaluated, not just at the
    /// moment zoom is applied」）。<see cref="ComputeContentRect"/>自体も呼ばれるたびに同じ再評価を
    /// 行うため描画結果はこのハンドラの有無にかかわらず常に正しいが、<see cref="ZoomState.Level"/>
    /// 自体をここでも永続的に更新しておかないと、次回のホイール操作が古い（キャップ変化前の）Level
    /// を起点に増減してしまい、再ズームインした瞬間に見た目が不連続に飛ぶ -- そのため状態自体の
    /// 更新はここで明示的に行う。
    /// </summary>
    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_lastFrame is { } frame)
        {
            ReapplyLevel(_video.ActualWidth, _video.ActualHeight, frame);
        }
    }

    /// <summary>
    /// <see cref="ZoomState.Level"/>を<c>Math.Min(<see cref="_desiredLevel"/>, 現在のキャップ)</c>へ
    /// 揃え直す。キャップに対して常に要求値から再評価する（クランプ済みのLevelを起点に「down方向
    /// のみ」動かすのではない）ことで、ウィンドウが縮んでキャップが下がった後に再び大きくなった
    /// 場合でも、要求値まで自動的に戻る（<see cref="_desiredLevel"/>のコメント参照）。
    /// </summary>
    private void ReapplyLevel(double viewW, double viewH, FrameGeometry frame)
    {
        if (viewW <= 0 || viewH <= 0)
        {
            return;
        }

        RenderedContentRect baseRect = LetterboxLayoutCalculator.ComputeContentRect(viewW, viewH, frame);
        double maxLevel = ComputeMaxZoomLevel(viewW, viewH, baseRect);
        double effectiveLevel = Math.Min(_desiredLevel, maxLevel);
        if (effectiveLevel != _state.Level)
        {
            _state.Set(effectiveLevel, _state.OffsetX, _state.OffsetY);
        }
    }

    /// <summary>
    /// 最大許容ズーム倍率（PLAN.md §4「zoomed content's on-screen pixel size can never exceed the
    /// VideoView control's current bounds」）。baseRectはアスペクト比を保ってview内に収まる最大の矩形
    /// （<see cref="LetterboxLayoutCalculator"/>）のため、Width/Heightのどちらか一方は必ずview寸法と
    /// 一致し（レターボックス/ピラーボックスの「詰まっている」軸）、もう一方はview寸法以下（黒帯の
    /// 余白がある「余裕がある」軸）になる。ズームは両軸を同倍率で拡大するため、詰まっている軸は
    /// 拡大した瞬間からview外へはみ出す（=その軸ではみ出した部分が見えなくなることこそがズームインの
    /// 実体、<see cref="ZoomedContentRectCalculator"/>のコメント参照）一方、余裕がある軸は黒帯を
    /// 使い切るところまでは拡大してもview内に収まる。上限は「余裕がある軸の黒帯をちょうど使い切る」
    /// 倍率 = max(viewW/baseRect.Width, viewH/baseRect.Height)（2つの比のうち小さい方は詰まっている軸
    /// で常にちょうど1.0なので、実質的に大きい方=余裕がある軸の比がそのまま上限になる）。ウィンドウを
    /// 大きくするほどこの余裕（延いては上限）が増えるため、docs/pc-client-detailed-spec.md §10の
    /// 「さらにズームしたければウィンドウをリサイズ/最大化する」という挙動とちょうど一致する。
    /// </summary>
    private static double ComputeMaxZoomLevel(double viewW, double viewH, RenderedContentRect baseRect)
    {
        if (baseRect.Width <= 0 || baseRect.Height <= 0)
        {
            return ZoomState.MinLevel;
        }

        return Math.Max(viewW / baseRect.Width, viewH / baseRect.Height);
    }

    /// <summary>
    /// <see cref="Video.VideoView"/>を一切編集できない制約下で、<see cref="ZoomState"/>変更後に
    /// 即座に再描画させるための手段。VideoViewのRecomputeContentRect()（private）は実際の
    /// ActualWidth/Height変化による<see cref="FrameworkElement.SizeChanged"/>と新規FrameGeometry
    /// 受信時にしか呼ばれない（VideoView.xaml.cs ContentRectOverrideのXMLコメント参照）-- ホイール
    /// 操作単独ではどちらも自然発生しない。<see cref="SizeChangedEventArgs"/>はpublicコンストラクタを
    /// 持たずWPF側の設計により外部から偽装できないため、実際に極小(<see cref="RecomputeNudge"/>px)だけ
    /// Width/Heightを変えてから即座にAuto(NaN)へ戻す、という本物のリサイズ往復でSizeChangedを誘発する
    /// （Video自体はLiveView.xaml上でWidth/Height未指定=Autoのままstretchで配置されているため安全に
    /// NaNへ戻せる）。2回のUpdateLayout()はいずれもディスパッチャが描画スレッドへ制御を戻す前に同期
    /// 実行されるため中間状態が画面に一瞬でも出ることはなく、最終的なActualWidth/Heightも完全に元通り
    /// になる（実装時に単体のWPFテストプログラムで実測済み）。
    /// </summary>
    private void ForceRecompute()
    {
        double w = _video.ActualWidth;
        double h = _video.ActualHeight;
        if (w <= 0 || h <= 0)
        {
            return;
        }

        _video.Width = w - RecomputeNudge;
        _video.Height = h - RecomputeNudge;
        _video.UpdateLayout();
        _video.Width = double.NaN;
        _video.Height = double.NaN;
        _video.UpdateLayout();
    }

    private static double Clamp01(double value) => Math.Clamp(value, 0.0, 1.0);
}
