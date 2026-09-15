from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

socket = socket_path.read_text(encoding="utf-8")

if "data class LiveKlineUpdate" not in socket:
    socket = socket.replace(
        '''data class LiveTrade(
    val price: Double,
    val quantity: Double,
    val tradeTime: Long
)
''',
        '''data class LiveTrade(
    val price: Double,
    val quantity: Double,
    val tradeTime: Long
)

data class LiveKlineUpdate(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)
''',
        1
    )

socket = socket.replace(
    '''    private val symbol: String,
    private val marketType: MarketType,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onTrade: (LiveTrade) -> Unit
) {''',
    '''    private val symbol: String,
    private val marketType: MarketType,
    private val interval: String,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onTrade: (LiveTrade) -> Unit,
    private val onKline: (LiveKlineUpdate) -> Unit = {}
) {''',
    1
)

endpoint_pattern = re.compile(
    r'''        val s = symbol\.lowercase\(\)\n        val spotStreams = "\$s@aggTrade/\$s@miniTicker"\n        val url = when \(marketType\) \{.*?\n        \}\n''',
    re.S,
)
endpoint_replacement = '''        val s = symbol.lowercase()
        val spotStreams = "$s@aggTrade/$s@miniTicker"
        val url = when (marketType) {
            MarketType.SPOT -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://stream.binance.com:443/stream?streams=$spotStreams"
                } else {
                    "wss://data-stream.binance.vision/stream?streams=$spotStreams"
                }
            }
            MarketType.FUTURES -> "wss://fstream.binance.com/ws/$s@kline_$interval"
        }
'''
socket, count = endpoint_pattern.subn(endpoint_replacement, socket, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace endpoint block: {count}")

message_pattern = re.compile(
    r'''            override fun onMessage\(webSocket: WebSocket, text: String\) \{.*?\n            \}\n\n            override fun onClosing''',
    re.S,
)
message_replacement = '''            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val outer = JSONObject(text)
                    val data = if (outer.has("data")) outer.optJSONObject("data") ?: outer else outer
                    val eventType = data.optString("e")

                    when (eventType) {
                        "kline" -> {
                            val k = data.optJSONObject("k") ?: return
                            val update = LiveKlineUpdate(
                                openTime = k.optLong("t"),
                                open = k.optString("o").toDoubleOrNull() ?: return,
                                high = k.optString("h").toDoubleOrNull() ?: return,
                                low = k.optString("l").toDoubleOrNull() ?: return,
                                close = k.optString("c").toDoubleOrNull() ?: return,
                                volume = k.optString("v").toDoubleOrNull() ?: 0.0
                            )
                            markDataSeen()
                            if (!stopped) onKline(update)
                        }
                        "aggTrade" -> {
                            val price = data.optString("p").toDoubleOrNull() ?: return
                            val quantity = data.optString("q").toDoubleOrNull() ?: 0.0
                            val eventTime = when {
                                data.has("T") -> data.optLong("T")
                                data.has("E") -> data.optLong("E")
                                else -> System.currentTimeMillis()
                            }
                            markDataSeen()
                            if (!stopped) onTrade(LiveTrade(price, quantity, eventTime))
                        }
                        "24hrMiniTicker" -> {
                            val price = data.optString("c").toDoubleOrNull() ?: return
                            val eventTime = data.optLong("E", System.currentTimeMillis())
                            markDataSeen()
                            if (!stopped) onTrade(LiveTrade(price, 0.0, eventTime))
                        }
                    }
                }
            }

            override fun onClosing'''
socket, count = message_pattern.subn(message_replacement, socket, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace onMessage: {count}")

if "private fun markDataSeen()" not in socket:
    insert_before = '''    private fun scheduleReconnect(delayMs: Long = 1_000L) {'''
    helper = '''    private fun markDataSeen() {
        if (!dataSeen) {
            dataSeen = true
            reconnectAttempt = 0
            mainHandler.post { if (!stopped) onConnectedChanged(true) }
        }
    }

'''
    socket = socket.replace(insert_before, helper + insert_before, 1)

if "fun applyLiveKlineToCandles" not in socket:
    socket += '''

fun applyLiveKlineToCandles(
    current: List<Candle>,
    update: LiveKlineUpdate,
    maxCandles: Int = 500
): List<Candle> {
    if (current.isEmpty()) return current
    val result = current.toMutableList()
    val candle = Candle(
        openTime = update.openTime,
        open = update.open,
        high = update.high,
        low = update.low,
        close = update.close,
        volume = update.volume
    )

    val lastIndex = result.lastIndex
    when {
        update.openTime == result[lastIndex].openTime -> result[lastIndex] = candle
        update.openTime > result[lastIndex].openTime -> result.add(candle)
        else -> {
            val index = result.indexOfLast { it.openTime == update.openTime }
            if (index >= 0) result[index] = candle else return current
        }
    }

    return if (result.size > maxCandles) result.takeLast(maxCandles) else result
}
'''

socket = socket.replace('User-Agent", "CryptoAlarm/1.2.1"', 'User-Agent", "CryptoAlarm/1.2.2"')
socket_path.write_text(socket, encoding="utf-8")

app = app_path.read_text(encoding="utf-8")
trade_queue = '    val liveTrades = remember(market, symbol, timeframe.api) { ConcurrentLinkedQueue<LiveTrade>() }\n'
if trade_queue not in app:
    raise RuntimeError("Could not locate liveTrades queue")
if "liveKlines" not in app:
    app = app.replace(trade_queue, trade_queue + '    val liveKlines = remember(market, symbol, timeframe.api) { ConcurrentLinkedQueue<LiveKlineUpdate>() }\n', 1)

app = app.replace(
    '        liveTrades.clear()\n        candles = MarketDataRepository.loadCandles',
    '        liveTrades.clear()\n        liveKlines.clear()\n        candles = MarketDataRepository.loadCandles',
    1
)

batch_pattern = re.compile(
    r'''    // SMOOTH_VIEWPORT_V12: WebSocket events are queued and applied at a\n    // display-friendly cadence instead of forcing a Compose recomposition for\n    // every single BTC trade\.\n    LaunchedEffect\(market, symbol, timeframe\.api\) \{.*?\n    \}\n\n    DisposableEffect''',
    re.S,
)
batch_replacement = '''    // v1.2.2: keep UI rendering smooth while using exact Futures kline snapshots.
    LaunchedEffect(market, symbol, timeframe.api) {
        val intervalMillis = chartIntervalMillis(timeframe.api)
        while (true) {
            delay(33L)
            if (candles.isEmpty()) continue

            if (market == MarketType.FUTURES) {
                var latestKline: LiveKlineUpdate? = null
                var drained = 0
                while (drained < 512) {
                    val item = liveKlines.poll() ?: break
                    latestKline = item
                    drained++
                }
                latestKline?.let { kline ->
                    candles = applyLiveKlineToCandles(candles, kline, 500)
                    ticker = ticker?.copy(price = kline.close)
                        ?: MarketTicker(symbol, kline.close, 0.0, kline.high, kline.low, 0.0, market)
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
    raise RuntimeError(f"Could not replace batching loop: {count}")

app = app.replace(
    '''            marketType = market,
            onConnectedChanged = { liveConnected = it },
            onTrade = { trade -> liveTrades.offer(trade) }
''',
    '''            marketType = market,
            interval = timeframe.api,
            onConnectedChanged = { liveConnected = it },
            onTrade = { trade -> liveTrades.offer(trade) },
            onKline = { kline -> liveKlines.offer(kline) }
''',
    1
)
app = app.replace(
    '''            socket.stop()
            liveTrades.clear()
''',
    '''            socket.stop()
            liveTrades.clear()
            liveKlines.clear()
''',
    1
)
app = app.replace("Версия 1.2.1 • плавный Live Chart + Futures fix", "Версия 1.2.2 • Futures kline LIVE")
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 16', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.2"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.2 Futures kline LIVE")
