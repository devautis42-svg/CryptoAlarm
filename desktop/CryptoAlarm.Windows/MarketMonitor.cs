using System.Collections.Concurrent;
using System.Globalization;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace CryptoAlarm.Windows;

/// <summary>
/// Watches only the enabled rules. Uses exchange trade streams for low-latency
/// monitoring and recent 1-minute REST klines when a stream is unavailable.
/// A WebSocket handshake is never considered live until price messages arrive.
/// </summary>
public sealed class MarketMonitor : IDisposable
{
    private sealed record Sample(DateTimeOffset At, decimal Price);
    private readonly BinanceApi api;
    private readonly Func<AppSettings> getSettings;
    private readonly object gate = new();
    private readonly Dictionary<string, List<Sample>> history = new();
    private readonly HashSet<string> latched = new();
    private readonly ConcurrentDictionary<string, DateTimeOffset> lastLive = new();
    private readonly ConcurrentDictionary<string, Task> socketTasks = new();
    private CancellationTokenSource? cts;

    public event Action<AlertEvent>? Alarm;
    public event Action<MarketKind, string, decimal>? PriceChanged;
    public event Action<string>? StatusChanged;
    public bool IsRunning => cts is { IsCancellationRequested: false };

    public MarketMonitor(BinanceApi api, Func<AppSettings> getSettings)
    {
        this.api = api;
        this.getSettings = getSettings;
    }

    private static string Key(MarketKind market, string symbol) => market + ":" + symbol;
    private bool IsTracked(MarketKind market, string symbol) =>
        getSettings().Rules.Any(r => r.Enabled && r.Market == market && r.Symbol == symbol);

    public void Start()
    {
        if (IsRunning) return;
        cts = new CancellationTokenSource();
        var ct = cts.Token;
        StatusChanged?.Invoke("Мониторинг запущен");
        _ = Task.Run(() => RunAsync(ct), ct);
    }

    public void Stop()
    {
        var source = cts;
        cts = null;
        source?.Cancel();
        lock (gate) { history.Clear(); latched.Clear(); }
        lastLive.Clear();
        StatusChanged?.Invoke("Мониторинг остановлен");
    }

    private async Task RunAsync(CancellationToken ct)
    {
        var nextStatus = DateTimeOffset.MinValue;
        using var throttle = new SemaphoreSlim(5);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var settings = getSettings();
                var monitored = settings.Rules.Where(r => r.Enabled)
                    .GroupBy(r => Key(r.Market, r.Symbol))
                    .Select(g => g.First()).ToArray();

                foreach (var rule in monitored)
                {
                    string key = Key(rule.Market, rule.Symbol);
                    if (socketTasks.TryGetValue(key, out var old) && !old.IsCompleted) continue;
                    var task = Task.Run(() => StreamTradesAsync(rule.Market, rule.Symbol, ct), ct);
                    socketTasks[key] = task;
                }

                var jobs = monitored.Select(async rule =>
                {
                    string key = Key(rule.Market, rule.Symbol);
                    if (lastLive.TryGetValue(key, out var seen) &&
                        DateTimeOffset.UtcNow - seen < TimeSpan.FromSeconds(5)) return;

                    await throttle.WaitAsync(ct);
                    try { await PollFallbackAsync(rule.Market, rule.Symbol, ct); }
                    finally { throttle.Release(); }
                });
                await Task.WhenAll(jobs);

                if (DateTimeOffset.UtcNow >= nextStatus)
                {
                    int realtime = monitored.Count(r => lastLive.TryGetValue(
                        Key(r.Market, r.Symbol), out var at) &&
                        DateTimeOffset.UtcNow - at < TimeSpan.FromSeconds(5));
                    StatusChanged?.Invoke(monitored.Length == 0
                        ? "Нет активных правил"
                        : $"Мониторинг: {monitored.Length} • WS: {realtime} • REST: {monitored.Length - realtime}");
                    nextStatus = DateTimeOffset.UtcNow.AddSeconds(4);
                }

                await Task.Delay(TimeSpan.FromSeconds(Math.Clamp(settings.ScanSeconds, 1, 30)), ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                StatusChanged?.Invoke("Связь с Binance: " + ex.Message);
                try { await Task.Delay(2000, ct); } catch (OperationCanceledException) { break; }
            }
        }
    }

    private async Task PollFallbackAsync(MarketKind market, string symbol, CancellationToken ct)
    {
        try
        {
            // Recent candles include the in-progress candle's high/low; unlike
            // a single last-price poll, this catches many fast one-minute wicks.
            var candlesTask = api.GetCandlesAsync(market, symbol, "1m", 3, ct);
            var priceTask = api.GetPriceAsync(market, symbol, ct);
            await Task.WhenAll(candlesTask, priceTask);
            var candles = await candlesTask;
            decimal current = await priceTask;
            if (current <= 0 || candles.Count == 0) return;

            var now = DateTimeOffset.UtcNow;
            Candle last = candles[^1];
            lock (gate)
            {
                string key = Key(market, symbol);
                if (!history.TryGetValue(key, out var points))
                    history[key] = points = new List<Sample>(500);

                // Preserve the observed current-candle range. Candle high and
                // low do not tell their chronological order, so use extremes
                // as references but fire only if the present price crosses.
                decimal hi = Math.Max(last.High, current);
                decimal lo = Math.Min(last.Low, current);
                Evaluate(market, symbol, current, now, points, hi, lo);

                // Keep the previous candle's high and low for minute-boundary
                // transitions. Do not fabricate their intraminute timestamps.
                if (candles.Count >= 2)
                {
                    var prev = candles[^2];
                    if (prev.OpenTime < last.OpenTime && now - prev.OpenTime < TimeSpan.FromMinutes(2))
                    {
                        var prevAt = prev.OpenTime.AddSeconds(59);
                        if (prevAt <= now && !points.Any(p => p.At == prevAt))
                        {
                            points.Add(new Sample(prevAt, prev.High));
                            points.Add(new Sample(prevAt.AddMilliseconds(1), prev.Low));
                        }
                    }
                }
            }
            PriceChanged?.Invoke(market, symbol, current);
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            StatusChanged?.Invoke($"REST {market} {symbol}: {ex.Message}");
        }
    }

    private async Task StreamTradesAsync(MarketKind market, string symbol, CancellationToken ct)
    {
        string uri = market == MarketKind.Futures
            ? $"wss://fstream.binance.com/ws/{symbol.ToLowerInvariant()}@aggTrade"
            : $"wss://stream.binance.com:443/ws/{symbol.ToLowerInvariant()}@aggTrade";
        while (!ct.IsCancellationRequested && IsTracked(market, symbol))
        {
            try
            {
                using var socket = new ClientWebSocket();
                socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(15);
                using (var connectTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    connectTimeout.CancelAfter(TimeSpan.FromSeconds(8));
                    await socket.ConnectAsync(new Uri(uri), connectTimeout.Token);
                }

                var buffer = new byte[8192];
                while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested &&
                       IsTracked(market, symbol))
                {
                    using var packet = new MemoryStream();
                    WebSocketReceiveResult result;
                    do
                    {
                        using var readTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        readTimeout.CancelAfter(TimeSpan.FromSeconds(15));
                        result = await socket.ReceiveAsync(
                            new ArraySegment<byte>(buffer), readTimeout.Token);
                        if (result.MessageType == WebSocketMessageType.Close)
                            throw new IOException("WebSocket closed");
                        packet.Write(buffer, 0, result.Count);
                    } while (!result.EndOfMessage && packet.Length < 32768);

                    if (result.MessageType != WebSocketMessageType.Text) continue;
                    using var doc = JsonDocument.Parse(packet.ToArray());
                    var data = doc.RootElement;
                    if (data.TryGetProperty("data", out var nested)) data = nested;
                    if (!data.TryGetProperty("p", out var priceEl)) continue;
                    decimal price = BinanceApi.Dec(priceEl);
                    if (price <= 0) continue;

                    lastLive[Key(market, symbol)] = DateTimeOffset.UtcNow;
                    Record(market, symbol, price, DateTimeOffset.UtcNow);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch { /* Fallback polling continues; reconnect below. */ }

            lastLive.TryRemove(Key(market, symbol), out _);
            try { await Task.Delay(TimeSpan.FromSeconds(3), ct); }
            catch (OperationCanceledException) { break; }
        }
    }

    private void Record(MarketKind market, string symbol, decimal price, DateTimeOffset at)
    {
        lock (gate)
        {
            string key = Key(market, symbol);
            if (!history.TryGetValue(key, out var points))
                history[key] = points = new List<Sample>(500);

            var last = points.Count > 0 ? points[^1] : null;
            if (last is null || at - last.At >= TimeSpan.FromMilliseconds(200) ||
                Math.Abs(price - last.Price) / Math.Max(last.Price, 0.0000001m) > 0.001m)
                points.Add(new Sample(at, price));

            Evaluate(market, symbol, price, at, points, null, null);
        }
        PriceChanged?.Invoke(market, symbol, price);
    }

    private void Evaluate(MarketKind market, string symbol, decimal price,
        DateTimeOffset now, List<Sample> points, decimal? extraHigh, decimal? extraLow)
    {
        var applicable = getSettings().Rules.Where(r => r.Enabled &&
            r.Market == market && r.Symbol == symbol).ToArray();
        if (applicable.Length == 0 || price <= 0) return;

        int maxWindow = applicable.Max(r => Math.Clamp(r.WindowMinutes, 1, 999));
        var keepSince = now.AddMinutes(-maxWindow - 2);
        points.RemoveAll(x => x.At < keepSince);
        if (points.Count == 0 || points[^1].At < now)
            points.Add(new Sample(now, price));

        foreach (var rule in applicable)
        {
            var since = now.AddMinutes(-Math.Clamp(rule.WindowMinutes, 1, 999));
            decimal top = price, bottom = price;
            foreach (var sample in points)
            {
                if (sample.At < since || sample.At > now) continue;
                if (sample.Price > top) top = sample.Price;
                if (sample.Price < bottom) bottom = sample.Price;
            }
            if (extraHigh.HasValue && extraHigh.Value > top) top = extraHigh.Value;
            if (extraLow.HasValue && extraLow.Value < bottom) bottom = extraLow.Value;
            decimal percent = rule.Direction == AlarmDirection.Drop
                ? (price - top) / top * 100m
                : (price - bottom) / bottom * 100m;
            bool triggered = rule.Direction == AlarmDirection.Drop
                ? percent <= -rule.ThresholdPercent
                : percent >= rule.ThresholdPercent;
            bool reset = Math.Abs(percent) < rule.ThresholdPercent * 0.7m;

            if (triggered && latched.Add(rule.Id))
                Alarm?.Invoke(new AlertEvent(rule, price, percent, now));
            if (!triggered && reset) latched.Remove(rule.Id);
        }
    }

    public void Dispose()
    {
        Stop();
        cts?.Dispose();
    }
}
