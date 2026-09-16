from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
service_path = root / "app/src/main/java/com/cryptoalarm/app/MarketMonitorService.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

# ---------------------------------------------------------------------------
# v1.3.0: Binance USD-M Futures moved regular market streams to /market.
# Use a combined aggTrade + kline stream so Futures updates as responsively as
# Spot while kline snapshots keep OHLCV authoritative.
# ---------------------------------------------------------------------------
socket = socket_path.read_text(encoding="utf-8")

endpoint_pattern = re.compile(
    r'''        val s = symbol\.lowercase\(\)\n        val spotStreams = "\$s@aggTrade/\$s@miniTicker"\n        val url = when \(marketType\) \{.*?\n        \}\n''',
    re.S,
)
endpoint_replacement = '''        val s = symbol.lowercase()
        val spotStreams = "$s@aggTrade/$s@miniTicker"
        val futuresStreams = "$s@aggTrade/$s@kline_$interval"
        val url = when (marketType) {
            MarketType.SPOT -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://stream.binance.com:443/stream?streams=$spotStreams"
                } else {
                    "wss://data-stream.binance.vision/stream?streams=$spotStreams"
                }
            }
            MarketType.FUTURES ->
                "wss://fstream.binance.com/market/stream?streams=$futuresStreams"
        }
'''
socket, count = endpoint_pattern.subn(endpoint_replacement, socket, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace LiveChartSocket endpoint block: {count}")

# Replace outdated diagnostics that referred to legacy /ws subscription modes.
diag_pattern = re.compile(
    r'''        if \(marketType == MarketType\.FUTURES\) \{\n            val mode = if \(reconnectAttempt % 2 == 0\) "SUBSCRIBE /ws" else "RAW kline"\n            diagnostic\("CONNECTING • Futures • \$mode"\)\n        \}\n\n'''
)
socket, _ = diag_pattern.subn(
    '''        if (marketType == MarketType.FUTURES) {
            diagnostic("CONNECTING • Futures • /market • aggTrade + kline")
        }

''',
    socket,
    count=1,
)

# The routed combined URL is already subscribed. Do not send the old JSON
# SUBSCRIBE command after opening the socket.
open_pattern = re.compile(
    r'''            override fun onOpen\(webSocket: WebSocket, response: Response\) \{.*?\n            \}\n\n            override fun onMessage''',
    re.S,
)
open_replacement = '''            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (marketType == MarketType.FUTURES) {
                    diagnostic("SOCKET OPEN • /market • жду aggTrade + kline")
                }
                mainHandler.postDelayed(noDataWatchdog, 5_000L)
            }

            override fun onMessage'''
socket, count = open_pattern.subn(open_replacement, socket, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace LiveChartSocket onOpen block: {count}")

# Report Futures LIVE as soon as the first aggregate trade packet arrives, not
# only when a kline packet arrives.
agg_anchor = '''                            markDataSeen()
                            if (!stopped) onTrade(LiveTrade(price, quantity, eventTime))
'''
agg_replacement = '''                            val firstPacket = !dataSeen
                            markDataSeen()
                            if (firstPacket && marketType == MarketType.FUTURES) {
                                diagnostic("LIVE • Futures aggTrade + kline")
                            }
                            if (!stopped) onTrade(LiveTrade(price, quantity, eventTime))
'''
if agg_anchor not in socket:
    raise RuntimeError("Could not locate aggTrade dispatch")
socket = socket.replace(agg_anchor, agg_replacement, 1)

# Keep Futures trade updates fast without double-counting volume; authoritative
# volume comes from the kline stream.
if "fun applyLiveTradePriceOnlyToCandles" not in socket:
    socket += '''

fun applyLiveTradePriceOnlyToCandles(
    current: List<Candle>,
    trade: LiveTrade,
    intervalMillis: Long,
    maxCandles: Int = 500
): List<Candle> = applyLiveTradeToCandles(
    current = current,
    trade = trade.copy(quantity = 0.0),
    intervalMillis = intervalMillis,
    maxCandles = maxCandles
)
'''

socket = re.sub(
    r'User-Agent", "CryptoAlarm/[^"]+"',
    'User-Agent", "CryptoAlarm/1.3.0"',
    socket,
    count=1,
)
socket_path.write_text(socket, encoding="utf-8")

# ---------------------------------------------------------------------------
# Alarm-monitor service: it also used the decommissioned unrouted Futures URL.
# Move aggTrade monitoring to the routed /market endpoint.
# ---------------------------------------------------------------------------
service = service_path.read_text(encoding="utf-8")
service = service.replace(
    'MarketType.FUTURES -> "wss://fstream.binance.com/stream?streams="',
    'MarketType.FUTURES -> "wss://fstream.binance.com/market/stream?streams="',
)
service = service.replace(
    'MarketType.FUTURES -> "wss://fstream.binance.com/ws/"',
    'MarketType.FUTURES -> "wss://fstream.binance.com/market/ws/"',
)
service_path.write_text(service, encoding="utf-8")

# ---------------------------------------------------------------------------
# UI batching: Futures now consumes both sources. aggTrade drives low-latency
# motion; kline snapshots periodically correct exact OHLCV.
# ---------------------------------------------------------------------------
app = app_path.read_text(encoding="utf-8")

batch_pattern = re.compile(
    r'''    // v1\.2\.2: keep UI rendering smooth while using exact Futures kline snapshots\.\n    LaunchedEffect\(market, symbol, timeframe\.api\) \{.*?\n    \}\n\n    DisposableEffect''',
    re.S,
)
batch_replacement = '''    // v1.3.0: Futures uses aggTrade for immediate motion and kline for exact OHLCV.
    // Both are batched at display cadence so Compose remains smooth under busy symbols.
    LaunchedEffect(market, symbol, timeframe.api) {
        val intervalMillis = chartIntervalMillis(timeframe.api)
        while (true) {
            delay(33L)
            if (candles.isEmpty()) continue

            if (market == MarketType.FUTURES) {
                var nextCandles = candles
                var changed = false

                // Apply the newest authoritative kline snapshot first.
                var latestKline: LiveKlineUpdate? = null
                var klineDrained = 0
                while (klineDrained < 512) {
                    val item = liveKlines.poll() ?: break
                    latestKline = item
                    klineDrained++
                }
                latestKline?.let { kline ->
                    nextCandles = applyLiveKlineToCandles(nextCandles, kline, 500)
                    changed = true
                }

                // Then apply every recent trade price without adding volume twice.
                var lastTradePrice: Double? = null
                var tradeDrained = 0
                while (tradeDrained < 2_000) {
                    val trade = liveTrades.poll() ?: break
                    nextCandles = applyLiveTradePriceOnlyToCandles(
                        nextCandles,
                        trade,
                        intervalMillis,
                        500
                    )
                    lastTradePrice = trade.price
                    tradeDrained++
                    changed = true
                }

                if (changed) candles = nextCandles
                val fastPrice = lastTradePrice ?: latestKline?.close
                fastPrice?.let { price ->
                    ticker = ticker?.copy(price = price)
                        ?: MarketTicker(symbol, price, 0.0, price, price, 0.0, market)
                }
            } else {
                var nextCandles = candles
                var lastPrice: Double? = null
                var drained = 0
                while (drained < 2_000) {
                    val trade = liveTrades.poll() ?: break
                    nextCandles = applyLiveTradeToCandles(nextCandles, trade, intervalMillis, 500)
                    lastPrice = trade.price
                    drained++
                }
                if (drained > 0) {
                    candles = nextCandles
                    lastPrice?.let { price ->
                        ticker = ticker?.copy(price = price)
                            ?: MarketTicker(symbol, price, 0.0, price, price, 0.0, market)
                    }
                }
            }
        }
    }

    DisposableEffect'''
app, count = batch_pattern.subn(batch_replacement, app, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace chart batching loop: {count}")

# Update diagnostics/version text after previous migrations.
app = app.replace(
    'Версия 1.2.9 • надёжные 1-мин сигналы',
    'Версия 1.3.0 • быстрый Futures WebSocket'
)
app = app.replace(
    'Версия 1.2.8 • диагностика Futures WS',
    'Версия 1.3.0 • быстрый Futures WebSocket'
)
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 24', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.3.0"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.3.0 routed Futures WebSocket")
