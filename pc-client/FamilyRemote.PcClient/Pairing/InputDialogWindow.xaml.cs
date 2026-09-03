using System.Windows;

namespace FamilyRemote.PcClient.Pairing;

/// <summary>
/// InputDialogWindow.xamlのコードビハインド。呼び出し側は<see cref="ShowDialog"/>後、
/// <c>DialogResult == true</c>のとき<see cref="Value"/>を読む。
/// </summary>
public partial class InputDialogWindow : Window
{
    public string Value => ValueTextBox.Text.Trim();

    public InputDialogWindow(string title, string initialValue)
    {
        InitializeComponent();
        Title = title;
        ValueTextBox.Text = initialValue;
        ValueTextBox.SelectAll();
        Loaded += (_, _) => ValueTextBox.Focus();
    }

    private void OnOkClick(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
    }

    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
