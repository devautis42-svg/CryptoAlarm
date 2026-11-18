using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text.Json;

namespace CryptoAlarm.Windows;

public sealed record ChartPacket(Candle? Bar, decimal Price, DateTimeOffset At);

public sealed class ChartFeed : IDisposable
{
    private readonly CancellationTokenSource stop = new();
    private readonly Task worker;
    private long lastPacket;
    public ConcurrentQueue<ChartPacket> Packets { get; } = new();
    public bool IsFresh => Environment.TickCount64 - Interlocked.Read(ref lastPacket) < 5000
        && Interlocked.Read(ref lastPacket) != 0;

    public ChartFeed(MarketKind market, string symbol, string interval)
    {
        var ct = stop.Token;
        worker = Task.Run(() => Run(market, symbol, interval, ct));
    }

    private async Task Run(MarketKind market, string symbol, string interval, CancellationToken ct)
    {
        string pair = symbol.ToLowerInvariant();
        string streams = $"{pair}@kline_{interval}/{pair}@aggTrade";
        int attempt = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                string uri = market == MarketKind.Futures
                    ? "wss://fstream.binance.com/market/stream?streams=" + streams
                    : (attempt % 2 == 0 ? "wss://stream.binance.com:443/stream?streams=" :
                        "wss://data-stream.binance.vision/stream?streams=") + streams;
                using var socket = new ClientWebSocket();
                socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
                using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
                {
                    timeout.CancelAfter(TimeSpan.FromSeconds(10));
                    await socket.ConnectAsync(new Uri(uri), timeout.Token);
                }
                byte[] buffer = new byte[8192];
                DateTimeOffset latestBarEvent = DateTimeOffset.MinValue;
                while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
                {
                    using var packet = new MemoryStream();
                    WebSocketReceiveResult result;
                    do
                    {
                        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        timeout.CancelAfter(TimeSpan.FromSeconds(12));
                        result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), timeout.Token);
                        if (result.MessageType == WebSocketMessageType.Close) throw new IOException("Socket closed");
                        packet.Write(buffer, 0, result.Count);
                        if (packet.Length > 128000) throw new IOException("Oversized packet");
                    } while (!result.EndOfMessage);
                    if (result.MessageType != WebSocketMessageType.Text) continue;
                    using var doc = JsonDocument.Parse(packet.ToArray());
                    var data = doc.RootElement;
                    if (data.TryGetProperty("data", out var nested)) data = nested;
                    if (!data.TryGetProperty("e", out var type)) continue;
                    ChartPacket? update = null;
                    if (type.GetString() == "kline" && data.TryGetProperty("k", out var k))
                    {
                        var at = DateTimeOffset.FromUnixTimeMilliseconds(data.GetProperty("E").GetInt64());
                        if (at < latestBarEvent) continue;
                        latestBarEvent = at;
                        var bar = new Candle(DateTimeOffset.FromUnixTimeMilliseconds(k.GetProperty("t").GetInt64()),
                            BinanceApi.Dec(k.GetProperty("o")), BinanceApi.Dec(k.GetProperty("h")),
                            BinanceApi.Dec(k.GetProperty("l")), BinanceApi.Dec(k.GetProperty("c")),
                            BinanceApi.Dec(k.GetProperty("v")));
                        if (bar.Close > 0) update = new ChartPacket(bar, 0, at);
                    }
                    else if (type.GetString() == "aggTrade")
                    {
                        var at = DateTimeOffset.FromUnixTimeMilliseconds(data.GetProperty("T").GetInt64());
                        decimal price = BinanceApi.Dec(data.GetProperty("p"));
                        if (price > 0 && at >= latestBarEvent) update = new ChartPacket(null, price, at);
                    }
                    if (update is null) continue;
                    Interlocked.Exchange(ref lastPacket, Environment.TickCount64);
                    Packets.Enqueue(update);
                    while (Packets.Count > 4000) Packets.TryDequeue(out _);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch { }
            Interlocked.Exchange(ref lastPacket, 0);
            attempt++;
            try { await Task.Delay(1500, ct); } catch (OperationCanceledException) { break; }
        }
    }

    public static DateTimeOffset EndOf(DateTimeOffset start, string interval) => interval switch
    {
        "1m" => start.AddMinutes(1), "5m" => start.AddMinutes(5), "15m" => start.AddMinutes(15),
        "1h" => start.AddHours(1), "4h" => start.AddHours(4), "1d" => start.AddDays(1),
        "1w" => start.AddDays(7), _ => start.AddMinutes(1)
    };

    public void Dispose()
    {
        stop.Cancel();
        _ = worker.ContinueWith(_ => stop.Dispose(), TaskScheduler.Default);
    }
}
