using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using FamilyRemote.PcClient.Resources;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// PairingListView.xamlのコードビハインド。docs/plans/pc-client/pairing-pc-side/PLAN.md §7
/// 参照。リネーム/削除は即座に永続化する（親には一切通信しない、docs/spec.md §5と同じ規則）。
/// </summary>
public partial class PairingListView : UserControl
{
    /// <summary>「新しいペアリングを追加」ボタンが押されたときに発火する。</summary>
    public event EventHandler? AddNewPairingRequested;

    /// <summary>
    /// 行が選択されたときに発火する。将来の接続開始ロジック（connection-state-ui所有）向けの
    /// フックであり、このコントロール自身はセッション開始や画面遷移を一切行わない。
    /// </summary>
    public event EventHandler<PcPairingEntry>? PairingEntrySelected;

    private readonly PcPairingRepository _repository = new();
    private readonly ObservableCollection<PcPairingEntry> _entries = new();

    public PairingListView()
    {
        InitializeComponent();
        EntryList.ItemsSource = _entries;
        RefreshList();
    }

    /// <summary>
    /// リポジトリの内容で表示を再構築する。データセットが小さいため全件差し替え
    /// （Android側PairingListFragment.refreshList()と同じ設計）。
    /// </summary>
    public void RefreshList()
    {
        _entries.Clear();
        foreach (PcPairingEntry entry in _repository.GetAll())
        {
            _entries.Add(entry);
        }

        EmptyText.Visibility = _entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EntryList.Visibility = _entries.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private void OnAddNewClick(object sender, RoutedEventArgs e)
    {
        AddNewPairingRequested?.Invoke(this, EventArgs.Empty);
    }

    private void OnSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (EntryList.SelectedItem is PcPairingEntry entry)
        {
            PairingEntrySelected?.Invoke(this, entry);
        }
    }

    /// <summary>
    /// WPF's Selector class-handles row selection on MouseDown with handledEventsToo=true, so
    /// clicking the Rename/Delete buttons inside a row's DataTemplate would *also* select that row
    /// (firing <see cref="PairingEntrySelected"/>) before the button's own Click even runs -- harmless
    /// while nothing consumed that event, but as of
    /// docs/plans/pc-client/connection-state-ui/PLAN.md §4, row selection now means "start a
    /// connection to this entry," so without this guard clicking Delete would also kick off a
    /// WaitingForParent attempt. Marking the tunneling PreviewMouseLeftButtonDown handled here
    /// suppresses the paired bubbling MouseDown before Selector ever sees it, while leaving the
    /// button's own Click (fired on MouseUp) untouched.
    /// </summary>
    private void OnRowActionButtonPreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
    }

    private void OnRenameClick(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).Tag is not PcPairingEntry entry)
        {
            return;
        }

        var dialog = new InputDialogWindow(Strings.PairingRenameDialogTitle, entry.Label)
        {
            Owner = Window.GetWindow(this),
        };

        if (dialog.ShowDialog() == true && dialog.Value.Length > 0)
        {
            _repository.UpdateLabel(entry.Id, dialog.Value);
            RefreshList();
        }
    }

    private void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).Tag is not PcPairingEntry entry)
        {
            return;
        }

        MessageBoxResult result = MessageBox.Show(
            Strings.PairingDeleteDialogMessage,
            Strings.PairingDeleteDialogTitle,
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);

        if (result == MessageBoxResult.Yes)
        {
            _repository.Delete(entry.Id);
            RefreshList();
        }
    }
}
