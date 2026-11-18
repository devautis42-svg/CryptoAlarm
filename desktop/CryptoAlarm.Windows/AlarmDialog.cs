using System.Drawing;

namespace CryptoAlarm.Windows;

public sealed class AlarmDialog : Form
{
    private readonly ComboBox marketCombo;
    private readonly SymbolPicker symbolBox;
    private readonly ComboBox directionCombo;
    private readonly NumericUpDown percent;
    private readonly NumericUpDown minutes;
    public AlarmRule? CreatedRule { get; private set; }

    public AlarmDialog(MarketKind defaultMarket, BinanceApi api)
    {
        Text = "Новый будильник Crypto Alarm";
        Size = new Size(430, 440);
        MinimumSize = Size;
        MaximumSize = Size;
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        BackColor = Color.FromArgb(12, 24, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 11);
        Padding = new Padding(23, 16, 23, 18);

        var table = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 7,
            BackColor = BackColor, Padding = new Padding(0),
        };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 38));
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 62));
        for (int i = 0; i < 5; i++) table.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        table.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));
        table.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        marketCombo = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList };
        marketCombo.Items.AddRange(new object[] { "Spot", "Futures USDT-M" });
        marketCombo.SelectedIndex = defaultMarket == MarketKind.Spot ? 0 : 1;

        symbolBox = new SymbolPicker(api, defaultMarket, "ETHUSDT");
        marketCombo.SelectedIndexChanged += (_, _) =>
            symbolBox.SetMarket(marketCombo.SelectedIndex == 0 ? MarketKind.Spot : MarketKind.Futures);
        directionCombo = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList };
        directionCombo.Items.AddRange(new object[] { "📉 Падение", "📈 Рост" });
        directionCombo.SelectedIndex = 0;

        percent = new NumericUpDown
        {
            DecimalPlaces = 2, Increment = .1m, Minimum = .1m, Maximum = 99m, Value = .5m
        };
        minutes = new NumericUpDown
        {
            Minimum = 1, Maximum = 999, Value = 1
        };
        AddRow(table, 0, "Рынок", marketCombo);
        AddRow(table, 1, "Монета", symbolBox);
        AddRow(table, 2, "Событие", directionCombo);
        AddRow(table, 3, "Изменение, %", percent);
        AddRow(table, 4, "Период, мин", minutes);
        table.Controls.Add(new Label
        {
            Text = "Пример: ETH падает на 0.5% в течение 1 минуты → звуковая тревога.",
            Dock = DockStyle.Fill, ForeColor = Color.FromArgb(157, 175, 198),
            Font = new Font("Segoe UI", 9), AutoSize = false, TextAlign = ContentAlignment.MiddleLeft
        }, 0, 5);
        table.SetColumnSpan(table.GetControlFromPosition(0, 5)!, 2);

        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft
        };
        var ok = new Button
        {
            Text = "Создать", DialogResult = DialogResult.None, Width = 118, Height = 37,
            BackColor = Color.FromArgb(78, 109, 255), ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };
        var cancel = new Button
        {
            Text = "Отмена", DialogResult = DialogResult.Cancel, Width = 110, Height = 37,
            BackColor = Color.FromArgb(30, 46, 67), ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };
        ok.Click += (_, _) =>
        {
            var symbol = symbolBox.Text.Trim().ToUpperInvariant().Replace("/", "").Replace(" ", "");
            if (!symbol.EndsWith("USDT", StringComparison.Ordinal)) symbol += "USDT";
            if (!symbolBox.IsKnownSymbol(symbol))
            {
                MessageBox.Show(this, "Выберите доступную USDT-пару из подсказок. Если список ещё загружается, попробуйте немного позже.",
                    "Монета", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            CreatedRule = new AlarmRule
            {
                Symbol = symbol,
                Market = marketCombo.SelectedIndex == 0 ? MarketKind.Spot : MarketKind.Futures,
                Direction = directionCombo.SelectedIndex == 0 ? AlarmDirection.Drop : AlarmDirection.Rise,
                ThresholdPercent = percent.Value,
                WindowMinutes = (int)minutes.Value,
                Enabled = true
            };
            DialogResult = DialogResult.OK;
            Close();
        };
        actions.Controls.Add(ok);
        actions.Controls.Add(cancel);
        table.Controls.Add(actions, 0, 6);
        table.SetColumnSpan(actions, 2);
        Controls.Add(table);
        AcceptButton = ok;
        CancelButton = cancel;
    }

    private static void AddRow(TableLayoutPanel table, int row, string title, Control input)
    {
        var label = new Label
        {
            Text = title, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Color.FromArgb(200, 209, 225)
        };
        input.Dock = DockStyle.Fill;
        input.Font = new Font("Segoe UI", 11);
        input.BackColor = Color.FromArgb(22, 39, 61);
        input.ForeColor = Color.White;
        input.Margin = new Padding(3, 6, 3, 7);
        table.Controls.Add(label, 0, row);
        table.Controls.Add(input, 1, row);
    }
}
