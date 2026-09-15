from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

anchor = '    var liveConnected by remember { mutableStateOf(false) }\n'
if anchor not in app:
    raise RuntimeError("Could not locate liveConnected state")
if 'restFallbackActive' not in app:
    app = app.replace(anchor, anchor + '    var restFallbackActive by remember { mutableStateOf(false) }\n', 1)

app = app.replace(
    '        liveConnected = false\n        liveTrades.clear()',
    '        liveConnected = false\n        restFallbackActive = false\n        liveTrades.clear()',
    1,
)

app = app.replace(
    'ticker = if (liveConnected && livePrice != null) fresh.copy(price = livePrice) else fresh',
    'ticker = if ((liveConnected || restFallbackActive) && livePrice != null) fresh.copy(price = livePrice) else fresh',
    1,
)

app = app.replace(
    'onConnectedChanged = { liveConnected = it },',
    'onConnectedChanged = { connected -> liveConnected = connected; if (connected) restFallbackActive = false },',
    1,
)

fallback_anchor = '    LazyColumn(contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {'
if fallback_anchor not in app:
    raise RuntimeError("Could not locate chart LazyColumn")
if 'FUTURES_REST_FALLBACK_V123' not in app:
    fallback_block = '''    // FUTURES_REST_FALLBACK_V123: if the phone/network cannot receive
    // fstream.binance.com WebSocket market data, keep the chart alive through
    // the Futures REST endpoint that already powers historical candles.
    LaunchedEffect(market, symbol, timeframe.api, liveConnected) {
        if (market != MarketType.FUTURES || liveConnected) {
            restFallbackActive = false
            return@LaunchedEffect
        }

        delay(3_000L)
        while (market == MarketType.FUTURES && !liveConnected) {
            val fresh = MarketDataRepository.loadCandles(
                symbol = symbol,
                interval = timeframe.api,
                limit = 20,
                marketType = MarketType.FUTURES
            )

            if (fresh.isNotEmpty()) {
                val merged = candles.toMutableList()
                for (candle in fresh) {
                    val index = merged.indexOfLast { it.openTime == candle.openTime }
                    if (index >= 0) {
                        merged[index] = candle
                    } else if (merged.isEmpty() || candle.openTime > merged.last().openTime) {
                        merged.add(candle)
                    }
                }

                candles = if (merged.size > 500) merged.takeLast(500) else merged
                val last = fresh.last()
                ticker = ticker?.copy(price = last.close)
                    ?: MarketTicker(symbol, last.close, 0.0, last.high, last.low, 0.0, market)
                restFallbackActive = true
            } else {
                restFallbackActive = false
            }

            delay(750L)
        }
    }

'''
    app = app.replace(fallback_anchor, fallback_block + fallback_anchor, 1)

old_badge = '''                Surface(color = if (liveConnected) Color(0xFF10362E) else Color(0xFF33262B), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (liveConnected) VGreen.copy(alpha = .5f) else VRed.copy(alpha = .4f))) {
                    Text(if (liveConnected) "⚡ LIVE" else "● CONNECTING", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = if (liveConnected) VGreen else VMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
'''
new_badge = '''                val anyLive = liveConnected || restFallbackActive
                Surface(
                    color = if (anyLive) Color(0xFF10362E) else Color(0xFF33262B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (anyLive) VGreen.copy(alpha = .5f) else VRed.copy(alpha = .4f))
                ) {
                    Text(
                        when {
                            liveConnected -> "⚡ LIVE"
                            restFallbackActive -> "↻ LIVE REST"
                            else -> "● CONNECTING"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = if (anyLive) VGreen else VMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
'''
if old_badge not in app:
    raise RuntimeError("Could not locate chart LIVE badge")
app = app.replace(old_badge, new_badge, 1)

app = app.replace('else VInteractiveChartPanel(candles, liveConnected)', 'else VInteractiveChartPanel(candles, liveConnected || restFallbackActive)', 1)
app = app.replace('Версия 1.2.2 • Futures kline LIVE', 'Версия 1.2.3 • Futures WS + REST fallback')
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 17', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.3"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.3 Futures REST live fallback")
