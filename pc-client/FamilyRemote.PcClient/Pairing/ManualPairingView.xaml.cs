using System.Windows;
using System.Windows.Controls;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// ManualPairingView.xamlのコードビハインド。docs/plans/pc-client/pairing-pc-side/PLAN.md §5/§6
/// 参照。フォーマット事前チェック → ハンドシェイク(§6) → ラベル入力 → PcPairingRepository保存の
/// 一連の流れをここで所有する。保存後に発火する<see cref="PairingCompleted"/>を購読して
/// WaitingForParentへ引き継ぐのはこのプラン外（connection-state-ui）。
/// </summary>
public partial class ManualPairingView : UserControl
{
    /// <summary>ハンドシェイク成功・ラベル保存まで完了したときに発火する。</summary>
    public event EventHandler<PcPairingEntry>? PairingCompleted;

    private readonly PcPairingRepository _repository = new();

    private Guid _pendingChildId;
    private string _pendingParentHost = string.Empty;
    private string _pendingSharedSecret = string.Empty;

    public ManualPairingView()
    {
        InitializeComponent();
    }

    private void OnInputChanged(object sender, TextChangedEventArgs e)
    {
        UpdatePairButtonEnabled();
    }

    private void UpdatePairButtonEnabled()
    {
        string host = HostTextBox.Text.Trim();
        string normalizedCode = CrockfordBase32.Normalize(CodeTextBox.Text);
        PairButton.IsEnabled = HostTextBox.IsEnabled &&
            host.Length > 0 &&
            CrockfordBase32.IsValidFormat(normalizedCode);
    }

    private async void OnPairButtonClick(object sender, RoutedEventArgs e)
    {
        string host = HostTextBox.Text.Trim();
        string normalizedCode = CrockfordBase32.Normalize(CodeTextBox.Text);

        SetFormEnabled(false);
        StatusText.Text = Strings.ManualPairingConnecting;

        string? childHost = PcTailscaleAddressResolver.Resolve();
        if (childHost is null)
        {
            StatusText.Text = Strings.ManualPairingTailscaleMissing;
            SetFormEnabled(true);
            return;
        }

        PairingHandshakeResult result =
            await PcPairingHandshakeClient.PerformHandshakeAsync(host, normalizedCode, childHost);
        if (!result.IsSuccess)
        {
            StatusText.Text = Strings.ManualPairingErrorGeneric;
            SetFormEnabled(true);
            return;
        }

        _pendingChildId = result.ChildId;
        _pendingParentHost = host;
        _pendingSharedSecret = result.SharedSecret;

        StatusText.Text = string.Empty;
        ShowLabelStep();
    }

    private void SetFormEnabled(bool enabled)
    {
        HostTextBox.IsEnabled = enabled;
        CodeTextBox.IsEnabled = enabled;
        UpdatePairButtonEnabled();
    }

    private void ShowLabelStep()
    {
        ConnectPanel.Visibility = Visibility.Collapsed;
        LabelPanel.Visibility = Visibility.Visible;
        LabelTextBox.Text = Strings.LabelPromptDefault;
        LabelTextBox.SelectAll();
        LabelTextBox.Focus();
    }

    private void OnSaveLabelClick(object sender, RoutedEventArgs e)
    {
        string label = LabelTextBox.Text.Trim();
        if (label.Length == 0)
        {
            label = Strings.LabelPromptDefault;
        }

        var entry = new PcPairingEntry(
            _pendingChildId,
            label,
            _pendingParentHost,
            _pendingSharedSecret,
            DateTimeOffset.Now);
        _repository.Add(entry);

        PairingCompleted?.Invoke(this, entry);
    }
}
