namespace CryptoAlarm.Windows;

// Native editable combo: actual exchange symbols, keyboard selection and suggestions.
public sealed class SymbolPicker : ComboBox
{
    private readonly BinanceApi api;
    private MarketKind market;
    private string[] symbols = Array.Empty<string>();
    private int loadId;
    private CancellationTokenSource? lookup;
    public event Action<string>? SymbolChosen;

    public SymbolPicker(BinanceApi api, MarketKind market, string initial)
    {
        this.api = api;
        this.market = market;
        DropDownStyle = ComboBoxStyle.DropDown;
        FlatStyle = FlatStyle.Flat;
        BackColor = Color.FromArgb(22, 39, 61);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 11);
        MaxDropDownItems = 12;
        IntegralHeight = false;
        Text = initial;
        AutoCompleteMode = AutoCompleteMode.SuggestAppend;
        AutoCompleteSource = AutoCompleteSource.CustomSource;
        SelectionChangeCommitted += (_, _) => { if (SelectedItem is string s) SymbolChosen?.Invoke(s); };
        KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter) return;
            string symbol = Normalize(Text);
            if (IsKnownSymbol(symbol))
            {
                Text = symbol;
                SymbolChosen?.Invoke(symbol);
            }
            // Allow Enter in a dialog to submit the form once the symbol is chosen.
        };
        HandleCreated += (_, _) => _ = LoadSymbols();
    }

    private static string Normalize(string text)
    {
        string s = text.Trim().ToUpperInvariant().Replace("/", "").Replace(" ", "");
        return s.EndsWith("USDT", StringComparison.Ordinal) ? s : s + "USDT";
    }
    public bool IsKnownSymbol(string symbol) => symbols.Contains(symbol, StringComparer.OrdinalIgnoreCase);

    public void SetMarket(MarketKind value)
    {
        market = value;
        if (IsHandleCreated) _ = LoadSymbols();
    }

    private async Task LoadSymbols()
    {
        int request = ++loadId;
        lookup?.Cancel();
        lookup?.Dispose();
        lookup = new CancellationTokenSource();
        var ct = lookup.Token;
        symbols = Array.Empty<string>();
        Items.Clear();
        AutoCompleteCustomSource = new AutoCompleteStringCollection();
        try
        {
            var data = await api.GetSymbolsAsync(market, ct);
            if (IsDisposed || request != loadId) return;
            string before = Text;
            symbols = data;
            BeginUpdate();
            try
            {
                Items.AddRange(data.Cast<object>().ToArray());
                var source = new AutoCompleteStringCollection();
                source.AddRange(data);
                AutoCompleteCustomSource = source;
                Text = before;
            }
            finally { EndUpdate(); }
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (!IsDisposed && request == loadId)
                Items.Add("Не удалось загрузить пары");
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { ++loadId; lookup?.Cancel(); lookup?.Dispose(); }
        base.Dispose(disposing);
    }
}
