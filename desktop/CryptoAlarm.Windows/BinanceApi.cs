using System.Globalization;
using System.Net.Http;
using System.Text.Json;

namespace CryptoAlarm.Windows;

public sealed class BinanceApi : IDisposable
{
    private readonly HttpClient client = new() { Timeout = TimeSpan.FromSeconds(10) };

    private static string Base(MarketKind market) => market == MarketKind.Futures
        ? "https://fapi.binance.com/fapi/v1"
        : "https://data-api.binance.vision/api/v3";

    public BinanceApi() => client.DefaultRequestHeaders.UserAgent.ParseAdd("CryptoAlarm-Windows/0.1");

    public static decimal Dec(JsonElement value) =>
        decimal.TryParse(value.ValueKind == JsonValueKind.String ? value.GetString() : value.ToString(),
            NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed) ? parsed : 0m;

    public async Task<List<Ticker>> GetTickersAsync(MarketKind market, CancellationToken ct = default)
    {
        using var response = await client.GetAsync(Base(market) + "/ticker/24hr", ct);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        var result = new List<Ticker>();
        foreach (var item in doc.RootElement.EnumerateArray())
        {
            string symbol = item.GetProperty("symbol").GetString() ?? "";
            if (!symbol.EndsWith("USDT", StringComparison.OrdinalIgnoreCase)) continue;
            decimal price = Dec(item.GetProperty("lastPrice"));
            if (price <= 0) continue;
            result.Add(new Ticker(
                symbol, price,
                Dec(item.GetProperty("priceChangePercent")),
                Dec(item.GetProperty("quoteVolume"))));
        }
        return result;
    }

    public async Task<decimal> GetPriceAsync(MarketKind market, string symbol, CancellationToken ct = default)
    {
        using var response = await client.GetAsync(
            Base(market) + "/ticker/price?symbol=" + Uri.EscapeDataString(symbol), ct);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        return Dec(doc.RootElement.GetProperty("price"));
    }

    public async Task<List<Candle>> GetCandlesAsync(
        MarketKind market, string symbol, string interval, int limit = 200, CancellationToken ct = default)
    {
        string url = Base(market) + "/klines?symbol=" + Uri.EscapeDataString(symbol)
            + "&interval=" + Uri.EscapeDataString(interval)
            + "&limit=" + Math.Clamp(limit, 2, 500);
        using var response = await client.GetAsync(url, ct);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        var result = new List<Candle>();
        foreach (var item in doc.RootElement.EnumerateArray())
        {
            result.Add(new Candle(
                DateTimeOffset.FromUnixTimeMilliseconds(item[0].GetInt64()),
                Dec(item[1]), Dec(item[2]), Dec(item[3]), Dec(item[4]), Dec(item[5])));
        }
        return result;
    }

    private readonly System.Collections.Concurrent.ConcurrentDictionary<MarketKind, (DateTimeOffset At, string[] Symbols)> symbolCache = new();

    public async Task<string[]> GetSymbolsAsync(MarketKind market, CancellationToken ct = default)
    {
        if (symbolCache.TryGetValue(market, out var cached) &&
            DateTimeOffset.UtcNow - cached.At < TimeSpan.FromMinutes(10)) return cached.Symbols;
        using var response = await client.GetAsync(Base(market) + "/exchangeInfo", ct);
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
        var result = doc.RootElement.GetProperty("symbols").EnumerateArray()
            .Where(x => x.GetProperty("status").GetString() == "TRADING" &&
                x.GetProperty("quoteAsset").GetString() == "USDT" &&
                (market == MarketKind.Futures
                    ? x.TryGetProperty("contractType", out var type) && type.GetString() == "PERPETUAL"
                    : !x.TryGetProperty("isSpotTradingAllowed", out var allowed) || allowed.GetBoolean()))
            .Select(x => x.GetProperty("symbol").GetString()!)
            .OrderBy(x => x, StringComparer.Ordinal).ToArray();
        symbolCache[market] = (DateTimeOffset.UtcNow, result);
        return result;
    }

    public void Dispose() => client.Dispose();
}
