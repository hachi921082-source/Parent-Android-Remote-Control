using System.Windows.Threading;
using FamilyRemote.PcClient.Net;

namespace FamilyRemote.PcClient.Input;

/// <summary>
/// InputAvailable（ビューオンリーモード）を追跡する小さなobservable。
/// docs/plans/pc-client/input-relay/PLAN.md §2/§8参照 -- Android側
/// child/InputAvailabilityViewModel.kt を移植したもの。<see cref="Session.ConnectionState"/>とは
/// 直交するフラグで、Live/Reconnectingの間だけ意味を持つ（呼び出し側=Session/LiveView.xaml.csが
/// outer ConnectionStateと合わせて判定する、PLAN.md §8最終箇条書き）。
///
/// スレッド設計: <see cref="OnAccessibilityStatusFrame"/>/<see cref="OnTextInjectFailedFrame"/>は
/// Net.SessionChannelの読み取りループ（バックグラウンドスレッド）から直接呼ばれる想定 -- 実際に
/// 状態を変える/イベントを発火する箇所は<paramref name="dispatcher"/>上へマーシャリングする
/// （Session/ConnectionStateStore.csのRunOnUiThreadと同じ方針）。
/// </summary>
public sealed class InputAvailabilityState
{
    private readonly Dispatcher _dispatcher;

    /// <summary>既定true（PLAN.md §8）。ACCESSIBILITY_STATUS受信で反転する。</summary>
    public bool InputAvailable { get; private set; } = true;

    /// <summary>常にUIスレッド上で発火する。</summary>
    public event Action<bool>? InputAvailableChanged;

    /// <summary>既定false（issue #38/#42）。親側に現在フォーカス中の入力欄があるかどうか --
    /// TextInputBarの表示/非表示はこれに連動させる（Android側hasFocusedInputFieldと同じ扱い）。</summary>
    public bool HasFocusedInputField { get; private set; }

    /// <summary>常にUIスレッド上で発火する。</summary>
    public event Action<bool>? HasFocusedInputFieldChanged;

    /// <summary>TEXT_INJECT_FAILED受信ごとに1回発火する一過性の通知（PLAN.md §7 -- InputAvailableの
    /// ような定常状態ではない）。常にUIスレッド上で発火する。</summary>
    public event Action? TextInjectFailed;

    public InputAvailabilityState(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
    }

    /// <summary>SessionChannel.RegisterHandler(MessageType.AccessibilityStatus, ...)から呼ぶ想定。</summary>
    public void OnAccessibilityStatusFrame(MessageFrame frame)
    {
        var payload = AccessibilityStatusPayload.Parse(frame.PayloadAsUtf8String());
        RunOnUiThread(() =>
        {
            InputAvailable = payload.Granted;
            InputAvailableChanged?.Invoke(payload.Granted);
            HasFocusedInputField = payload.HasFocusedInputField;
            HasFocusedInputFieldChanged?.Invoke(payload.HasFocusedInputField);
        });
    }

    /// <summary>SessionChannel.RegisterHandler(MessageType.TextInjectFailed, ...)から呼ぶ想定。</summary>
    public void OnTextInjectFailedFrame(MessageFrame frame)
    {
        RunOnUiThread(() => TextInjectFailed?.Invoke());
    }

    /// <summary>
    /// 既定の想定（利用可能）へ戻す -- ConnectionStateが新たにLiveへ遷移するたび（新規接続・
    /// 再接続いずれも）に呼ぶ想定。前の接続のInputAvailable値は既に失効しているため
    /// （PLAN.md §8、Android側InputAvailabilityViewModel.resetのAndroid ConnectionState.Live
    /// 遷移毎の呼び出しと同じルール）。呼び出し元（Session/LiveView.xaml.cs）は常にUIスレッド上。
    /// </summary>
    public void Reset()
    {
        InputAvailable = true;
        InputAvailableChanged?.Invoke(true);
        HasFocusedInputField = false;
        HasFocusedInputFieldChanged?.Invoke(false);
    }

    private void RunOnUiThread(Action action)
    {
        if (_dispatcher.CheckAccess())
        {
            action();
        }
        else
        {
            _dispatcher.Invoke(action);
        }
    }
}
