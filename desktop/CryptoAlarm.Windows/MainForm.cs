using System.Drawing;
using System.Media;
using Microsoft.Win32;

namespace CryptoAlarm.Windows;

public sealed partial class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(7, 17, 29);
    private static readonly Color Surface = Color.FromArgb(16, 28, 44);
    private static readonly Color Surface2 = Color.FromArgb(11, 23, 38);
    private static readonly Color Border = Color.FromArgb(49, 64, 88);
    private static readonly Color Muted = Color.FromArgb(154, 166, 188);
    private static readonly Color Blue = Color.FromArgb(78, 109, 255);
    private static readonly Color Green = Color.FromArgb(25, 215, 160);
    private static readonly Color Red = Color.FromArgb(255, 91, 107);
    private readonly AppSettings settings = LocalStore.Load();
    private readonly BinanceApi api = new();
    private readonly MarketMonitor monitor;
    private readonly NotifyIcon tray;
    private readonly Panel content = new();
    private readonly Label liveStatus = new();
    private readonly Dictionary<string, Button> navButtons = new();
    private readonly System.Windows.Forms.Timer tickTimer = new() { Interval = 2500 };
    private readonly System.Windows.Forms.Timer repeatAlarmTimer = new() { Interval = 1800 };
    private string currentTab = "Главная";
    private bool exiting, firstTrayMessage;
    private MarketKind market = MarketKind.Spot;
    private string selectedSymbol = "BTCUSDT", selectedInterval = "1m";
    private List<Ticker> tickers = new();
    private List<Candle> chartCandles = new();
    private DateTimeOffset tickersAt = DateTimeOffset.MinValue;
    private bool loadingTickers, loadingChart;
    private DataGridView? marketGrid, alarmGrid;
    private TextBox? marketSearch;
    private string sortBy = "volume";
    private bool descending = true;
    private CandleChart? chartView;
    private Label? chartStatus, chartPrice;
    private SoundPlayer? alarmPlayer;
    private readonly CancellationTokenSource lifetime = new();

    public MainForm()
    {
        Text = "Crypto Alarm — Windows";
        Icon = SystemIcons.Application;
        MinimumSize = new Size(980, 670);
        Size = new Size(1230, 790);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Bg;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10);
        market = settings.LastMarket;
        monitor = new MarketMonitor(api, () => settings);

        var sidebar = new Panel { Dock = DockStyle.Left, Width = 210, BackColor = Surface2 };
        Controls.Add(content);
        Controls.Add(sidebar);
        content.Dock = DockStyle.Fill;
        content.BackColor = Bg;
        content.Padding = new Padding(24, 22, 24, 20);

        var brand = new Label
        {
            Text = "◈  CRYPTO ALARM", ForeColor = Color.White,
            Font = new Font("Segoe UI", 17, FontStyle.Bold),
            Location = new Point(19, 26), Size = new Size(185, 39)
        };
        sidebar.Controls.Add(brand);
        sidebar.Controls.Add(new Label
        {
            Text = "WINDOWS  •  v0.1.0", ForeColor = Muted,
            Font = new Font("Segoe UI", 9, FontStyle.Bold),
            Location = new Point(23, 72), Size = new Size(175, 28)
        });

        string[] tabs = { "Главная", "Будильники", "Рынок", "График", "Настройки" };
        for (int i = 0; i < tabs.Length; i++)
        {
            string tab = tabs[i];
            var button = new Button
            {
                Text = new[] { "⌂   Главная", "◉   Будильники", "▥   Рынок", "⌁   График", "⚙   Настройки" }[i],
                FlatStyle = FlatStyle.Flat, TextAlign = ContentAlignment.MiddleLeft,
                Location = new Point(13, 128 + i * 58),
                Size = new Size(182, 48),
                Font = new Font("Segoe UI", 11, FontStyle.Bold),
                ForeColor = Muted, BackColor = Surface2,
                Cursor = Cursors.Hand, Padding = new Padding(14, 0, 0, 0)
            };
            button.FlatAppearance.BorderSize = 0;
            button.Click += (_, _) => ShowTab(tab);
            sidebar.Controls.Add(button);
            navButtons[tab] = button;
        }

        liveStatus.Dock = DockStyle.Bottom;
        liveStatus.Height = 82;
        liveStatus.Padding = new Padding(17, 13, 12, 10);
        liveStatus.Text = "●  Мониторинг выключен";
        liveStatus.ForeColor = Muted;
        liveStatus.BackColor = Surface2;
        liveStatus.Font = new Font("Segoe UI", 9);
        sidebar.Controls.Add(liveStatus);

        var trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Открыть Crypto Alarm", null, (_, _) => RestoreWindow());
        trayMenu.Items.Add("Запустить мониторинг", null, (_, _) => monitor.Start());
        trayMenu.Items.Add("Остановить мониторинг", null, (_, _) => monitor.Stop());
        trayMenu.Items.Add("Остановить звук", null, (_, _) => SilenceAlarm());
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add("Выход", null, (_, _) => ExitApp());
        tray = new NotifyIcon
        {
            Icon = SystemIcons.Application, Text = "Crypto Alarm",
            Visible = true, ContextMenuStrip = trayMenu
        };
        tray.DoubleClick += (_, _) => RestoreWindow();
        monitor.Alarm += ev => OnAlarm(ev);
        monitor.StatusChanged += msg => UI(() =>
        {
            liveStatus.Text = (monitor.IsRunning ? "●  " : "○  ") + msg;
            liveStatus.ForeColor = monitor.IsRunning ? Green : Muted;
        });
        monitor.PriceChanged += (kind, symbol, price) => UI(() =>
        {
            if (currentTab == "График" && chartPrice is not null &&
                kind == market && symbol == selectedSymbol)
                chartPrice.Text = $"{symbol}   {Price(price)} USDT";
        });

        tickTimer.Tick += async (_, _) =>
        {
            if (currentTab == "Рынок" && DateTimeOffset.UtcNow - tickersAt > TimeSpan.FromSeconds(17))
                await RefreshTickersAsync();
            if (currentTab == "График") await RefreshChartAsync(false);
        };
        tickTimer.Start();
        repeatAlarmTimer.Tick += (_, _) => SystemSounds.Exclamation.Play();
        ShowTab("Главная");
        if (settings.StartMonitoringOnLaunch) monitor.Start();
    }

    private void UI(Action action)
    {
        if (IsDisposed || Disposing) return;
        try
        {
            if (InvokeRequired)
            {
                if (IsHandleCreated) BeginInvoke(action);
            }
            else action();
        }
        catch (InvalidOperationException) { }
        catch (ObjectDisposedException) { }
    }

    private void ShowTab(string tab)
    {
        currentTab = tab;
        foreach (var kv in navButtons)
        {
            bool on = kv.Key == tab;
            kv.Value.BackColor = on ? Color.FromArgb(45, 64, 119) : Surface2;
            kv.Value.ForeColor = on ? Color.White : Muted;
        }
        content.Controls.Clear();
        content.AutoScroll = false;
        marketGrid = null;
        alarmGrid = null;
        chartView = null;
        marketSearch = null;
        chartStatus = null;
        chartPrice = null;
        switch (tab)
        {
            case "Главная": BuildHome(); break;
            case "Будильники": BuildAlarms(); break;
            case "Рынок": BuildMarket(); _ = RefreshTickersAsync(); break;
            case "График": BuildChart(); _ = RefreshChartAsync(true); break;
            case "Настройки": BuildSettings(); break;
        }
    }

    private async Task RefreshTickersAsync()
    {
        if (loadingTickers) return;
        loadingTickers = true;
        try
        {
            var selectedMarket = market;
            var data = await api.GetTickersAsync(selectedMarket, lifetime.Token);
            if (currentTab == "Рынок" && market == selectedMarket)
            {
                tickers = data;
                tickersAt = DateTimeOffset.UtcNow;
                FillMarket();
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { if (currentTab == "Рынок") SetMarketError("Не удалось загрузить рынок: " + ex.Message); }
        finally { loadingTickers = false; }
    }

    private async Task RefreshChartAsync(bool all)
    {
        if (loadingChart || chartView is null) return;
        loadingChart = true;
        var kind = market;
        var symbol = selectedSymbol;
        var frame = selectedInterval;
        try
        {
            var fresh = await api.GetCandlesAsync(kind, symbol, frame, all ? 300 : 3, lifetime.Token);
            if (currentTab == "График" && chartView is not null &&
                kind == market && symbol == selectedSymbol && frame == selectedInterval)
            {
                if (all || chartCandles.Count == 0) chartCandles = fresh;
                else
                {
                    var map = chartCandles.ToDictionary(c => c.OpenTime);
                    foreach (var candle in fresh) map[candle.OpenTime] = candle;
                    chartCandles = map.Values.OrderBy(c => c.OpenTime).TakeLast(500).ToList();
                }
                chartView.SetCandles(chartCandles);
                chartStatus!.Text = "↻  REST LIVE  •  Binance " + (kind == MarketKind.Spot ? "Spot" : "Futures") +
                    "  •  обновление ~2.5 сек";
                chartStatus.ForeColor = Green;
                if (chartCandles.Count > 0) chartPrice!.Text = $"{symbol}   {Price(chartCandles[^1].Close)} USDT";
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (currentTab == "График" && chartStatus is not null)
            {
                chartStatus.Text = "Нет связи: " + ex.Message;
                chartStatus.ForeColor = Red;
            }
        }
        finally { loadingChart = false; }
    }

    private void OnAlarm(AlertEvent alarm)
    {
        UI(() =>
        {
            string coin = alarm.Rule.Symbol.Replace("USDT", "");
            string direction = alarm.Rule.Direction == AlarmDirection.Drop ? "падает" : "растёт";
            string title = $"⚠ {coin} {direction}!";
            string message = $"{alarm.Rule.Market} • {alarm.ChangePercent:+0.00;-0.00;0.00}% " +
                $"за {alarm.Rule.WindowMinutes} мин • {Price(alarm.Price)} USDT";
            try
            {
                tray.BalloonTipTitle = title;
                tray.BalloonTipText = message;
                tray.ShowBalloonTip(12000);
            }
            catch { }
            PlayAlarm();
            // Important alerts are visible in foreground and still sound in tray.
            if (Visible && WindowState != FormWindowState.Minimized)
            {
                MessageBox.Show(this, message, title, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        });
    }

    private void PlayAlarm()
    {
        SilenceAlarm();
        try
        {
            string chosen = settings.AlarmWavFile;
            string defaultWav = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.Windows), "Media", "Alarm01.wav");
            string wav = File.Exists(chosen) ? chosen : defaultWav;
            if (File.Exists(wav))
            {
                alarmPlayer = new SoundPlayer(wav);
                alarmPlayer.PlayLooping();
                return;
            }
        }
        catch { }
        repeatAlarmTimer.Start();
        SystemSounds.Exclamation.Play();
    }

    private void SilenceAlarm()
    {
        repeatAlarmTimer.Stop();
        try { alarmPlayer?.Stop(); alarmPlayer?.Dispose(); } catch { }
        alarmPlayer = null;
    }

    private void RestoreWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        ShowInTaskbar = true;
        Activate();
    }

    private void ExitApp()
    {
        exiting = true;
        monitor.Stop();
        SilenceAlarm();
        tray.Visible = false;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!exiting && settings.MinimizeToTray && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!firstTrayMessage)
            {
                tray.BalloonTipTitle = "Crypto Alarm работает";
                tray.BalloonTipText = "Мониторинг продолжается в трее. Для выхода используйте меню значка.";
                tray.ShowBalloonTip(5000);
                firstTrayMessage = true;
            }
            return;
        }
        tickTimer.Stop();
        repeatAlarmTimer.Stop();
        lifetime.Cancel();
        monitor.Dispose();
        api.Dispose();
        tray.Visible = false;
        tray.Dispose();
        base.OnFormClosing(e);
    }

    private void Save()
    {
        try { LocalStore.Save(settings); }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Не удалось сохранить настройки",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static string Price(decimal value) => value switch
    {
        >= 1000 => value.ToString("#,0.00"),
        >= 1 => value.ToString("0.000"),
        _ => value.ToString("0.########")
    };

    private static string Compact(decimal value)
    {
        if (value >= 1_000_000_000m) return $"{value / 1_000_000_000m:0.##}B";
        if (value >= 1_000_000m) return $"{value / 1_000_000m:0.##}M";
        if (value >= 1_000m) return $"{value / 1_000m:0.##}K";
        return $"{value:0.##}";
    }

    private Label TextLabel(string text, int size = 11, bool bold = false, Color? color = null)
        => new()
        {
            Text = text, AutoSize = true, MaximumSize = new Size(930, 0),
            Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
            ForeColor = color ?? Color.White, BackColor = Color.Transparent
        };

    private Button MakeButton(string title, EventHandler handler, bool primary = false, int width = 130)
    {
        var button = new Button
        {
            Text = title, Width = width, Height = 39,
            BackColor = primary ? Blue : Surface, ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            Margin = new Padding(0, 0, 10, 0)
        };
        button.FlatAppearance.BorderColor = primary ? Blue : Border;
        button.FlatAppearance.BorderSize = 1;
        button.Click += handler;
        return button;
    }

    private static DataGridView MakeGrid()
    {
        var grid = new DataGridView
        {
            Dock = DockStyle.Fill, BackgroundColor = Bg, BorderStyle = BorderStyle.None,
            GridColor = Border, EnableHeadersVisualStyles = false,
            RowHeadersVisible = false, AllowUserToAddRows = false,
            AllowUserToDeleteRows = false, AllowUserToResizeRows = false,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            MultiSelect = false, ReadOnly = true,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
            ColumnHeadersHeight = 45, RowTemplate = { Height = 39 },
            ColumnHeadersDefaultCellStyle =
                new DataGridViewCellStyle { BackColor = Surface2, ForeColor = Muted,
                    Font = new Font("Segoe UI", 10, FontStyle.Bold), SelectionBackColor = Surface2 },
            DefaultCellStyle =
                new DataGridViewCellStyle { BackColor = Surface, ForeColor = Color.White,
                    SelectionBackColor = Color.FromArgb(41, 61, 103),
                    SelectionForeColor = Color.White, Font = new Font("Segoe UI", 10) }
        };
        grid.AlternatingRowsDefaultCellStyle.BackColor = Surface2;
        return grid;
    }

    private Panel SectionHeader(string title, string subtitle)
    {
        var head = new Panel { Dock = DockStyle.Top, Height = 101, BackColor = Bg };
        var titleLabel = TextLabel(title, 29, true);
        titleLabel.Location = new Point(0, 2);
        head.Controls.Add(titleLabel);
        var sub = TextLabel(subtitle, 11, false, Muted);
        sub.Location = new Point(1, 53);
        head.Controls.Add(sub);
        return head;
    }
}
