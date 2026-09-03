namespace FamilyRemote.PcClient.Common;

/// <summary>
/// 親が現在キャプチャしている画面の寸法。直近のVIDEO_FRAMEヘッダで報告された値
/// （docs/plans/README.md §4/§6）。Android側 common/FrameGeometry.kt をそのまま移植したもの。
/// <see cref="Video.VideoView"/>のレターボックス/ピラーボックス計算と、後続tier
/// （input-relay）のタップ座標スケーリングの双方が同じ値を参照する単一の共有表現
/// （docs/plans/pc-client/video-and-session/decode.PLAN.md §2）。
/// </summary>
public sealed record FrameGeometry(int ParentFrameWidth, int ParentFrameHeight);
