using System.Drawing;
using Microsoft.Win32;

namespace CryptoAlarm.Windows;

public sealed partial class MainForm
{
    private void BuildHome()
    {
        var body = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true, BackColor = Bg,
            Padding = new Padding(0, 18, 0, 0)
        };
        content.Controls.Add(body);
        content.Controls.Add(SectionHeader("Главная", "Мониторинг цен Binance • Windows"));

        var headline = TextLabel("Крипторынок под контролем", 21, true);
        headline.Margin = new Padding(0, 10, 0, 14);
        body.Controls.Add(headline);

        var row = new FlowLayoutPanel { Width = 870, Height = 132, BackColor = Bg };
        body.Controls.Add(row);
        row.Controls.Add(StatisticCard("Активные будильники",
            settings.Rules.Count(r => r.Enabled).ToString(), "Правила Spot и Futures", Blue));
        row.Controls.Add(StatisticCard("Мониторинг",
            monitor.IsRunning ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН",
            "Работает в системном трее", monitor.IsRunning ? Green : Muted));
        row.Controls.Add(StatisticCard("Источник данных",
            "WS + REST", "Автоматический резерв", Green));

        var buttons = new FlowLayoutPanel { Width = 870, Height = 65, BackColor = Bg };
        body.Controls.Add(buttons);
        buttons.Controls.Add(MakeButton("▶ Запустить", (_, _) =>
        {
            monitor.Start();
            ShowTab("Главная");
        }, true, 160));
        buttons.Controls.Add(MakeButton("■ Остановить", (_, _) =>
        {
            monitor.Stop();
            ShowTab("Главная");
        }, false, 160));
        buttons.Controls.Add(MakeButton("＋ Будильник", (_, _) =>
        {
            ShowTab("Будильники");
            AddAlarm();
        }, false, 170));
        body.Controls.Add(TextLabel("Что уже работает", 18, true));
        var desc = TextLabel(
            "• Spot и USDⓈ-M Futures, пары USDT\n\n" +
            "• Уведомления о росте/падении за N минут\n\n" +
            "• Сортировка рынка по объёму и изменению за 24 часа\n\n" +
            "• Свечной график: масштаб колесом, сдвиг мышью, линейка Shift + перетаскивание\n\n" +
            "• WebSocket-мониторинг с автоматическим REST-резервом\n\n" +
            "• Звуковая тревога и уведомления Windows даже при свёрнутом окне",
            11, false, Muted);
        desc.Margin = new Padding(0, 20, 0, 0);
        body.Controls.Add(desc);
    }

    private Panel StatisticCard(string heading, string value, string subtitle, Color accent)
    {
        var card = new Panel
        {
            Width = 275, Height = 117, BackColor = Surface,
            Margin = new Padding(0, 0, 14, 0), Padding = new Padding(15)
        };
        var headingLabel = TextLabel(heading, 10, false, Muted);
        headingLabel.Location = new Point(14, 13);
        card.Controls.Add(headingLabel);
        var valueLabel = TextLabel(value, 21, true, accent);
        valueLabel.Location = new Point(14, 35);
        card.Controls.Add(valueLabel);
        var sub = TextLabel(subtitle, 9, false, Muted);
        sub.Location = new Point(14, 85);
        card.Controls.Add(sub);
        return card;
    }

    private void BuildAlarms()
    {
        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top, Height = 58,
            BackColor = Bg, Padding = new Padding(0, 3, 0, 9)
        };
        toolbar.Controls.Add(MakeButton("＋ Добавить", (_, _) => AddAlarm(), true, 160));
        toolbar.Controls.Add(MakeButton("Вкл / выкл", (_, _) => ToggleAlarm(), false, 150));
        toolbar.Controls.Add(MakeButton("Удалить", (_, _) => DeleteAlarm(), false, 130));
        toolbar.Controls.Add(MakeButton("▶ Мониторинг", (_, _) => monitor.Start(), false, 150));
        toolbar.Controls.Add(MakeButton("■ Стоп", (_, _) => monitor.Stop(), false, 95));

        alarmGrid = MakeGrid();
        content.Controls.Add(alarmGrid);
        content.Controls.Add(toolbar);
        content.Controls.Add(SectionHeader("Будильники", "Уведомления о движении цены за заданное время"));
        FillAlarms();
    }

    private void FillAlarms()
    {
        if (alarmGrid is null) return;
        alarmGrid.DataSource = settings.Rules.Select(r => new
        {
            Id = r.Id,
            Рынок = r.Market == MarketKind.Spot ? "Spot" : "Futures",
            Монета = r.Symbol,
            Событие = r.Direction == AlarmDirection.Drop ? "📉 Падение" : "📈 Рост",
            Порог = r.ThresholdPercent.ToString("0.##") + "%",
            Период = r.WindowMinutes.ToString() + " мин",
            Состояние = r.Enabled ? "Включён" : "Выключен"
        }).ToList();
        if (alarmGrid.Columns.Contains("Id"))
            alarmGrid.Columns["Id"]!.Visible = false;
    }

    private string? SelectedAlarmId()
    {
        if (alarmGrid?.CurrentRow is null) return null;
        return alarmGrid.CurrentRow.Cells["Id"].Value?.ToString();
    }

    private void AddAlarm()
    {
        using var dialog = new AlarmDialog(market, api);
        if (dialog.ShowDialog(this) != DialogResult.OK || dialog.CreatedRule is null) return;
        settings.Rules.Add(dialog.CreatedRule);
        Save();
        FillAlarms();
    }

    private void ToggleAlarm()
    {
        string? id = SelectedAlarmId();
        var rule = settings.Rules.FirstOrDefault(r => r.Id == id);
        if (rule is null) return;
        rule.Enabled = !rule.Enabled;
        Save();
        FillAlarms();
    }

    private void DeleteAlarm()
    {
        string? id = SelectedAlarmId();
        if (id is null) return;
        settings.Rules.RemoveAll(r => r.Id == id);
        Save();
        FillAlarms();
    }

    private void BuildMarket()
    {
        marketGrid = MakeGrid();
        marketGrid.Columns.Add("symbol", "Монета");
        marketGrid.Columns.Add("price", "Цена USDT");
        marketGrid.Columns.Add("change", "Изменение 24ч");
        marketGrid.Columns.Add("volume", "Объём USDT 24ч");
        marketGrid.CellDoubleClick += (_, args) =>
        {
            if (args.RowIndex < 0 || marketGrid?.Rows[args.RowIndex].Tag is not Ticker coin) return;
            selectedSymbol = coin.Symbol;
            ShowTab("График");
        };

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top, Height = 94, BackColor = Bg,
            Padding = new Padding(0, 4, 0, 8)
        };
        var marketChoice = MarketCombo();
        marketChoice.SelectedIndexChanged += async (_, _) =>
        {
            market = marketChoice.SelectedIndex == 0 ? MarketKind.Spot : MarketKind.Futures;
            settings.LastMarket = market;
            Save();
            tickers = new();
            tickersAt = DateTimeOffset.MinValue;
            FillMarket();
            await RefreshTickersAsync();
        };
        toolbar.Controls.Add(marketChoice);

        marketSearch = new TextBox
        {
            Width = 210, Height = 39,
            PlaceholderText = "Поиск: BTC, ETH, SOL…",
            BackColor = Surface, ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 12), Margin = new Padding(7, 7, 8, 0)
        };
        marketSearch.TextChanged += (_, _) => FillMarket();
        toolbar.Controls.Add(marketSearch);
        toolbar.Controls.Add(MakeButton("Объём ↓↑", (_, _) => SortMarket("volume"), false, 125));
        toolbar.Controls.Add(MakeButton("Изменение % ↓↑", (_, _) => SortMarket("change"), false, 165));
        toolbar.Controls.Add(MakeButton("Обновить", async (_, _) =>
        {
            tickersAt = DateTimeOffset.MinValue;
            await RefreshTickersAsync();
        }, true, 125));

        var note = new Label
        {
            Text = "Двойной клик по монете — открыть график. Сортировка переключается повторным нажатием.",
            Dock = DockStyle.Top, Height = 25,
            ForeColor = Muted, BackColor = Bg, Font = new Font("Segoe UI", 9)
        };
        content.Controls.Add(marketGrid);
        content.Controls.Add(note);
        content.Controls.Add(toolbar);
        content.Controls.Add(SectionHeader("Рынок", "Binance Spot / Futures • все доступные USDT-пары"));
        FillMarket();
    }

    private ComboBox MarketCombo()
    {
        var combo = new ComboBox
        {
            DropDownStyle = ComboBoxStyle.DropDownList,
            Width = 135, Height = 38, BackColor = Surface,
            ForeColor = Color.White, FlatStyle = FlatStyle.Flat,
            Font = new Font("Segoe UI", 11),
            Margin = new Padding(0, 7, 7, 0)
        };
        combo.Items.AddRange(new object[] { "Spot", "Futures USDT" });
        combo.SelectedIndex = market == MarketKind.Spot ? 0 : 1;
        return combo;
    }

    private void SortMarket(string by)
    {
        if (sortBy == by) descending = !descending;
        else { sortBy = by; descending = true; }
        FillMarket();
    }

    private void SetMarketError(string message)
    {
        if (marketGrid is null) return;
        marketGrid.Rows.Clear();
        marketGrid.Rows.Add(message, "—", "—", "—");
    }

    private void FillMarket()
    {
        if (marketGrid is null || marketGrid.IsDisposed) return;
        string search = marketSearch?.Text.Trim().ToUpperInvariant() ?? "";
        IEnumerable<Ticker> view = tickers.Where(t => t.Symbol.Contains(search, StringComparison.OrdinalIgnoreCase));
        view = sortBy == "change"
            ? descending ? view.OrderByDescending(t => t.ChangePercent) : view.OrderBy(t => t.ChangePercent)
            : descending ? view.OrderByDescending(t => t.QuoteVolume) : view.OrderBy(t => t.QuoteVolume);
        marketGrid.SuspendLayout();
        try
        {
            marketGrid.Rows.Clear();
            foreach (var coin in view)
            {
                int index = marketGrid.Rows.Add(coin.Symbol.Replace("USDT", ""), Price(coin.Price),
                    $"{coin.ChangePercent:+0.00;-0.00;0.00}%", Compact(coin.QuoteVolume));
                var row = marketGrid.Rows[index];
                row.Tag = coin;
                row.Cells[2].Style.ForeColor = coin.ChangePercent >= 0 ? Green : Red;
            }
        }
        finally { marketGrid.ResumeLayout(); }
    }

    private void BuildChart()
    {
        chartView = new CandleChart { Dock = DockStyle.Fill, Margin = new Padding(0) };
        var controls = new FlowLayoutPanel
        {
            Dock = DockStyle.Top, Height = 58,
            BackColor = Bg, Padding = new Padding(0, 3, 0, 6)
        };
        var marketChoice = MarketCombo();
        controls.Controls.Add(marketChoice);

        var pair = new SymbolPicker(api, market, selectedSymbol) { Width = 180 };
        pair.Margin = new Padding(6, 7, 8, 0);
        pair.SymbolChosen += symbol =>
        {
            if (selectedSymbol == symbol) return;
            selectedSymbol = symbol;
            _ = RefreshChartAsync(true);
        };
        marketChoice.SelectedIndexChanged += (_, _) =>
        {
            market = marketChoice.SelectedIndex == 0 ? MarketKind.Spot : MarketKind.Futures;
            settings.LastMarket = market;
            Save();
            pair.SetMarket(market);
            _ = RefreshChartAsync(true);
        };
        controls.Controls.Add(pair);

        var frames = new ComboBox
        {
            Width = 102, BackColor = Surface, ForeColor = Color.White,
            DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat,
            Font = new Font("Segoe UI", 11), Margin = new Padding(4, 7, 7, 0)
        };
        frames.Items.AddRange(new object[] { "1m", "5m", "15m", "1h", "4h", "1d", "1w" });
        frames.SelectedItem = selectedInterval;
        frames.SelectedIndexChanged += (_, _) =>
        {
            selectedInterval = frames.SelectedItem?.ToString() ?? "1m";
            chartCandles.Clear();
            _ = RefreshChartAsync(true);
        };
        controls.Controls.Add(frames);
        controls.Controls.Add(MakeButton("К последней", (_, _) => chartView?.GoToLatest(), false, 140));
        controls.Controls.Add(MakeButton("Обновить", async (_, _) => await RefreshChartAsync(true), true, 135));

        var bottom = new Panel { Dock = DockStyle.Bottom, Height = 55, BackColor = Surface2 };
        chartStatus = TextLabel("Подключение к Binance…", 10, false, Muted);
        chartStatus.Location = new Point(15, 18);
        bottom.Controls.Add(chartStatus);
        chartPrice = TextLabel($"{selectedSymbol} / USDT", 15, true, Green);
        chartPrice.Location = new Point(15, 9);

        var priceBar = new Panel { Dock = DockStyle.Top, Height = 51, BackColor = Bg };
        priceBar.Controls.Add(chartPrice);
        content.Controls.Add(chartView);
        content.Controls.Add(bottom);
        content.Controls.Add(priceBar);
        content.Controls.Add(controls);
        content.Controls.Add(SectionHeader("График", "LIVE • колесо — масштаб • мышь — свободное перемещение"));
    }

    private void BuildSettings()
    {
        var list = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown,
            WrapContents = false, AutoScroll = true,
            BackColor = Bg, Padding = new Padding(0, 10, 0, 0)
        };
        content.Controls.Add(list);
        content.Controls.Add(SectionHeader("Настройки", "Звук, фоновый мониторинг и автозапуск"));

        var text = TextLabel("Резервный REST-скан (секунды)", 13, true);
        text.Margin = new Padding(0, 10, 0, 8);
        list.Controls.Add(text);
        var scan = new NumericUpDown
        {
            Minimum = 1, Maximum = 30, Value = Math.Clamp(settings.ScanSeconds, 1, 30),
            Width = 170, Height = 35, BackColor = Surface,
            ForeColor = Color.White, Font = new Font("Segoe UI", 12)
        };
        scan.ValueChanged += (_, _) =>
        {
            settings.ScanSeconds = (int)scan.Value;
            Save();
        };
        list.Controls.Add(scan);
        list.Controls.Add(TextLabel("WebSocket проверяет сделки в реальном времени. REST включается при потере данных.",
            10, false, Muted));

        list.Controls.Add(SettingCheck("Запускать мониторинг при открытии Crypto Alarm",
            settings.StartMonitoringOnLaunch, enabled =>
            {
                settings.StartMonitoringOnLaunch = enabled;
                Save();
            }));
        list.Controls.Add(SettingCheck("При закрытии окна оставаться в трее",
            settings.MinimizeToTray, enabled =>
            {
                settings.MinimizeToTray = enabled;
                Save();
            }));

        bool windowsAutostart = false;
        try
        {
            using var reg = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            windowsAutostart = reg?.GetValue("CryptoAlarm") is not null;
        }
        catch { }
        list.Controls.Add(SettingCheck("Запускать Crypto Alarm вместе с Windows",
            windowsAutostart, enabled =>
            {
                try
                {
                    using var key = Registry.CurrentUser.OpenSubKey(
                        @"Software\Microsoft\Windows\CurrentVersion\Run", true);
                    if (enabled) key?.SetValue("CryptoAlarm", "\"" + Environment.ProcessPath + "\"");
                    else key?.DeleteValue("CryptoAlarm", false);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "Автозапуск");
                }
            }));

        var sound = TextLabel("Мелодия тревоги (.wav)", 13, true);
        sound.Margin = new Padding(0, 24, 0, 8);
        list.Controls.Add(sound);
        var filename = TextLabel(string.IsNullOrEmpty(settings.AlarmWavFile)
            ? "Стандартная мелодия Windows" : settings.AlarmWavFile,
            10, false, Muted);
        list.Controls.Add(filename);
        var buttons = new FlowLayoutPanel { Width = 690, Height = 70, BackColor = Bg, Padding = new Padding(0, 10, 0, 0) };
        buttons.Controls.Add(MakeButton("Выбрать WAV", (_, _) =>
        {
            using var dialog = new OpenFileDialog
            {
                Filter = "Wave audio (*.wav)|*.wav", Title = "Мелодия Crypto Alarm"
            };
            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                settings.AlarmWavFile = dialog.FileName;
                filename.Text = dialog.FileName;
                Save();
            }
        }, false, 150));
        buttons.Controls.Add(MakeButton("▶ Проверить", (_, _) => PlayAlarm(), true, 140));
        buttons.Controls.Add(MakeButton("■ Остановить звук", (_, _) => SilenceAlarm(), false, 175));
        list.Controls.Add(buttons);

        var info = TextLabel(
            "Crypto Alarm Windows v0.2.0\n\n" +
            "Данные: публичный Binance API. Ключ API не требуется.\n" +
            "Будильники и настройки сохраняются только на этом компьютере:\n" +
            LocalStore.Folder,
            10, false, Muted);
        info.Margin = new Padding(0, 35, 0, 5);
        list.Controls.Add(info);
    }

    private CheckBox SettingCheck(string caption, bool initial, Action<bool> changed)
    {
        var check = new CheckBox
        {
            Text = caption, Checked = initial, AutoSize = false,
            Width = 780, Height = 46,
            Font = new Font("Segoe UI", 11),
            ForeColor = Color.White, BackColor = Bg, Margin = new Padding(0, 13, 0, 0)
        };
        check.CheckedChanged += (_, _) => changed(check.Checked);
        return check;
    }
}
