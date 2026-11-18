using System.Drawing.Drawing2D;
using System.Globalization;

namespace CryptoAlarm.Windows;

/// <summary>Candlestick chart with fractional zoom, drag, crosshair and Shift+ruler.</summary>
public sealed class CandleChart : Control
{
    private readonly Color bg = Color.FromArgb(9, 19, 33);
    private readonly Color grid = Color.FromArgb(37, 53, 71);
    private readonly Color faded = Color.FromArgb(158, 174, 194);
    private readonly Color green = Color.FromArgb(25, 215, 160);
    private readonly Color red = Color.FromArgb(255, 91, 107);
    private List<Candle> candles = new();
    private double span = 68d;
    private double end;
    private bool dragging;
    private bool measuring;
    private Point lastPoint;
    private Point? pointer, rulerStart, rulerEnd;

    public CandleChart()
    {
        DoubleBuffered = true;
        BackColor = bg;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10);
        ResizeRedraw = true;
        TabStop = true;
    }

    public void SetCandles(List<Candle> incoming)
    {
        bool follow = end >= candles.Count - 0.5 || candles.Count == 0;
        candles = incoming.OrderBy(c => c.OpenTime).ToList();
        span = Math.Clamp(span, 8, Math.Max(8, Math.Min(240, candles.Count)));
        if (follow) end = candles.Count;
        else end = Math.Clamp(end, span, Math.Max(span, candles.Count));
        Invalidate();
    }

    public void GoToLatest()
    {
        end = candles.Count;
        Invalidate();
    }

    private RectangleF Plot => new(14, 18, Math.Max(100, Width - 112), Math.Max(90, Height - 58));
    private double Start => end - span;
    private double MinSpan => Math.Min(8, Math.Max(1, candles.Count));
    private double MaxSpan => Math.Min(240, Math.Max(1, candles.Count));

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        Focus();
        if (e.Button != MouseButtons.Left) return;
        dragging = true;
        measuring = ModifierKeys.HasFlag(Keys.Shift);
        lastPoint = e.Location;
        pointer = e.Location;
        if (measuring) rulerStart = rulerEnd = e.Location;
        Capture = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        pointer = e.Location;
        if (dragging)
        {
            if (measuring)
            {
                rulerEnd = e.Location;
            }
            else if (candles.Count > 0)
            {
                var dx = e.X - lastPoint.X;
                var start = Start - dx * span / Plot.Width;
                start = Math.Clamp(start, 0, Math.Max(0, candles.Count - span));
                end = start + span;
            }
        }
        lastPoint = e.Location;
        Invalidate();
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);
        dragging = false;
        measuring = false;
        Capture = false;
        Invalidate();
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        if (!dragging) { pointer = null; Invalidate(); }
    }

    protected override void OnMouseWheel(MouseEventArgs e)
    {
        base.OnMouseWheel(e);
        if (candles.Count < 2) return;
        var p = Plot;
        var focal = Math.Clamp((e.X - p.Left) / p.Width, 0f, 1f);
        var oldSpan = span;
        var focalCandle = Start + focal * oldSpan;
        var factor = Math.Pow(1.12, e.Delta / 120.0);
        span = Math.Clamp(oldSpan / factor, MinSpan, MaxSpan);
        var newStart = Math.Clamp(focalCandle - focal * span, 0, Math.Max(0, candles.Count - span));
        end = newStart + span;
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var g = e.Graphics;
        g.Clear(bg);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        var p = Plot;
        if (candles.Count == 0)
        {
            DrawText(g, "Загрузка свечей Binance…", faded, new PointF(p.Left + 20, p.Top + p.Height / 2));
            return;
        }

        span = Math.Clamp(span, MinSpan, MaxSpan);
        end = Math.Clamp(end, span, Math.Max(span, candles.Count));
        int first = Math.Clamp((int)Math.Floor(Start), 0, candles.Count - 1);
        int after = Math.Clamp((int)Math.Ceiling(end), first + 1, candles.Count);
        var visible = candles.GetRange(first, after - first);
        decimal high = visible.Max(c => c.High);
        decimal low = visible.Min(c => c.Low);
        if (high <= low) { high += 1; low -= 1; }
        decimal pad = (high - low) * 0.065m;
        high += pad; low -= pad;
        var scale = (double)(high - low);

        float X(int idx) => p.Left + (float)((idx + 0.5 - Start) / span * p.Width);
        float Y(decimal price) => p.Top + (float)((double)(high - price) / scale * p.Height);
        using var gridPen = new Pen(grid, 1);
        using var dotted = new Pen(Color.FromArgb(105, 165, 187, 215), 1) { DashPattern = new float[] { 4, 5 } };
        for (int i = 0; i <= 5; i++)
        {
            float y = p.Top + i * p.Height / 5f;
            g.DrawLine(gridPen, p.Left, y, p.Right, y);
            var price = high - (high - low) * (decimal)(i / 5.0);
            DrawText(g, PriceText(price), faded, new PointF(p.Right + 9, y - 10), 9);
        }
        int labelStep = Math.Max(1, (int)Math.Ceiling(span / 7));
        for (int i = first; i < after; i++)
        {
            if (i % labelStep != 0) continue;
            float x = X(i);
            if (x < p.Left || x > p.Right) continue;
            g.DrawLine(gridPen, x, p.Top, x, p.Bottom);
            DrawText(g, candles[i].OpenTime.ToLocalTime().ToString("dd.MM HH:mm"),
                faded, new PointF(x - 30, p.Bottom + 8), 8);
        }

        float w = Math.Max(1.5f, Math.Min(28f, p.Width / (float)span * .66f));
        for (int i = first; i < after; i++)
        {
            var c = candles[i];
            float x = X(i);
            if (x + w < p.Left || x - w > p.Right) continue;
            float topBody = Y(Math.Max(c.Open, c.Close));
            float bottomBody = Y(Math.Min(c.Open, c.Close));
            Color color = c.Close >= c.Open ? green : red;
            using var pen = new Pen(color, Math.Max(1, w * 0.11f));
            using var fill = new SolidBrush(color);
            g.DrawLine(pen, x, Y(c.High), x, Y(c.Low));
            g.FillRectangle(fill, x - w / 2, topBody, w, Math.Max(1.4f, bottomBody - topBody));
        }

        var last = candles[^1];
        float lastY = Y(last.Close);
        if (lastY >= p.Top && lastY <= p.Bottom)
        {
            using var lastPen = new Pen(last.Close >= last.Open ? green : red, 1) { DashPattern = new float[] { 4, 4 } };
            g.DrawLine(lastPen, p.Left, lastY, p.Right, lastY);
            using var lastBrush = new SolidBrush(last.Close >= last.Open ? green : red);
            g.FillRectangle(lastBrush, p.Right + 1, lastY - 11, 91, 22);
            DrawText(g, PriceText(last.Close), bg, new PointF(p.Right + 7, lastY - 9), 9, bold: true);
        }

        if (pointer is Point pt && p.Contains(pt))
        {
            g.DrawLine(dotted, pt.X, p.Top, pt.X, p.Bottom);
            g.DrawLine(dotted, p.Left, pt.Y, p.Right, pt.Y);
            decimal hoverPrice = high - (decimal)((pt.Y - p.Top) / p.Height) * (high - low);
            DrawText(g, PriceText(hoverPrice), Color.White, new PointF(p.Right + 6, pt.Y - 18), 9);
        }

        if (rulerStart is Point r1 && rulerEnd is Point r2 && p.Contains(r1))
        {
            g.DrawLine(new Pen(Color.Gold, 2), r1, r2);
            decimal startPrice = high - (decimal)((r1.Y - p.Top) / p.Height) * (high - low);
            decimal endPrice = high - (decimal)((r2.Y - p.Top) / p.Height) * (high - low);
            if (startPrice != 0)
            {
                var change = (endPrice - startPrice) / startPrice * 100m;
                DrawText(g, $"{change:+0.00;-0.00;0.00}%  •  Shift + мышь",
                    Color.Gold, new PointF(Math.Min(r1.X, r2.X), Math.Min(r1.Y, r2.Y) - 23), 10, bold: true);
            }
        }
        DrawText(g, $"Свечей: {(int)Math.Round(span)}  •  колесо — масштаб  •  мышь — перемещение  •  Shift — линейка",
            faded, new PointF(16, Height - 19), 8);
    }

    private static string PriceText(decimal price) => price switch
    {
        >= 1000m => price.ToString("#,0.00", CultureInfo.InvariantCulture),
        >= 1m => price.ToString("0.000", CultureInfo.InvariantCulture),
        >= 0.01m => price.ToString("0.00000", CultureInfo.InvariantCulture),
        _ => price.ToString("0.########", CultureInfo.InvariantCulture)
    };

    private void DrawText(Graphics g, string value, Color color, PointF position, float size = 10, bool bold = false)
    {
        using var font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular);
        using var brush = new SolidBrush(color);
        g.DrawString(value, font, brush, position);
    }
}
