using System.Drawing;

namespace CryptoAlarm.Windows;

/// <summary>Non-modal, topmost acknowledgement window. It never owns or stops the monitor.</summary>
public sealed class AlarmPopup : Form
{
    private readonly Label title = new();
    private readonly Label change = new();
    private readonly Label detail = new();
    private readonly Label time = new();
    private readonly Label countdown = new();
    private int signals;
    public event Action? StopRequested;

    public AlarmPopup()
    {
        Text = "Crypto Alarm — тревога";
        Icon = SystemIcons.Warning;
        TopMost = true;
        ShowInTaskbar = true;
        StartPosition = FormStartPosition.Manual;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(426, 286);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Color.FromArgb(12, 23, 39);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 11);
        KeyPreview = true;

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 6,
            Padding = new Padding(18, 10, 18, 9), BackColor = BackColor
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        foreach (int h in new[] { 32, 60, 38, 28, 62 })
            layout.RowStyles.Add(new RowStyle(SizeType.Absolute, h));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Setup(title, 14, FontStyle.Bold, Color.White);
        Setup(change, 29, FontStyle.Bold, Color.OrangeRed);
        Setup(detail, 10, FontStyle.Regular, Color.White);
        Setup(time, 9, FontStyle.Regular, Color.FromArgb(170, 186, 206));
        Setup(countdown, 9, FontStyle.Regular, Color.FromArgb(170, 186, 206));
        layout.Controls.Add(title, 0, 0);
        layout.Controls.Add(change, 0, 1);
        layout.Controls.Add(detail, 0, 2);
        layout.Controls.Add(time, 0, 3);

        var stop = new Button
        {
            Text = "■  ОСТАНОВИТЬ ЗВУК",
            AccessibleName = "Остановить звук тревоги",
            Dock = DockStyle.Fill, Margin = new Padding(0, 4, 0, 4),
            BackColor = Color.FromArgb(204, 56, 76), ForeColor = Color.White,
            Font = new Font("Segoe UI", 14, FontStyle.Bold),
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand
        };
        stop.FlatAppearance.BorderSize = 0;
        stop.Click += (_, _) => StopRequested?.Invoke();
        AcceptButton = stop;
        layout.Controls.Add(stop, 0, 4);
        layout.Controls.Add(countdown, 0, 5);
        Controls.Add(layout);
        KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Escape)
            {
                e.Handled = true;
                StopRequested?.Invoke();
            }
        };
    }

    // Avoid taking keyboard focus from the user's current application on a price signal.
    protected override bool ShowWithoutActivation => true;

    private static void Setup(Label label, float size, FontStyle style, Color color)
    {
        label.AutoSize = false;
        label.Dock = DockStyle.Fill;
        label.Margin = new Padding(0);
        label.TextAlign = ContentAlignment.MiddleLeft;
        label.Font = new Font("Segoe UI", size, style);
        label.ForeColor = color;
        label.AutoEllipsis = true;
    }

    public void SetAlert(string heading, string value, string description, string timestamp, Color accent, bool newSession)
    {
        signals = newSession ? 1 : signals + 1;
        title.Text = heading;
        change.Text = value;
        change.ForeColor = accent;
        detail.Text = description;
        time.Text = timestamp + (signals > 1 ? $"  •  Сигналов: {signals}" : "");
    }

    public void SetRemaining(int seconds)
    {
        countdown.Text = $"Автостоп через {seconds} сек.  •  Мониторинг продолжится.";
    }
}
