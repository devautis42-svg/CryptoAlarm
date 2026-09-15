from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
service_path = root / "app/src/main/java/com/cryptoalarm/app/MarketMonitorService.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

service = service_path.read_text(encoding="utf-8")

# Track the time of the last actual market-data message. A successful socket
# handshake alone must not disable REST fallback.
anchor = '    private val connectedMarkets = ConcurrentHashMap.newKeySet<MarketType>()\n'
if anchor not in service:
    raise RuntimeError("connectedMarkets anchor not found")
if 'lastDataAt' not in service:
    service = service.replace(
        anchor,
        anchor + '    private val lastDataAt = ConcurrentHashMap<MarketType, Long>()\n',
        1,
    )

# Port 443 is more reliable on mobile networks than Spot port 9443.
service = service.replace(
    'MarketType.SPOT -> "wss://stream.binance.com:9443/stream?streams="',
    'MarketType.SPOT -> "wss://stream.binance.com:443/stream?streams="',
)

# Reset last-data status whenever a market socket is replaced.
service = service.replace(
    '''        reconnectPending.remove(marketType)
        connectedMarkets.remove(marketType)
        sockets.remove(marketType)?.cancel()
''',
    '''        reconnectPending.remove(marketType)
        connectedMarkets.remove(marketType)
        lastDataAt.remove(marketType)
        sockets.remove(marketType)?.cancel()
''',
    1,
)

# Do not declare LIVE on handshake. LIVE means actual price events are flowing.
old_open = '''            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != generations[marketType]) return
                connectedMarkets.add(marketType)
                reconnectPending.remove(marketType)
                updateLiveStatus()
            }
'''
new_open = '''            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != generations[marketType]) return
                reconnectPending.remove(marketType)
                updateMonitor("${marketType.label} WebSocket открыт • жду рыночные данные…")
            }
'''
if old_open not in service:
    raise RuntimeError("onOpen block not found")
service = service.replace(old_open, new_open, 1)

# Mark the market as LIVE only after a valid trade message arrives.
old_message_tail = '''                    val symbol = data.optString("s")
                    val price = data.optString("p").toDoubleOrNull() ?: return
                    val eventTime = data.optLong("E", System.currentTimeMillis())
                    if (symbol.isNotBlank()) processLivePrice(marketType, symbol, price, eventTime)
'''
new_message_tail = '''                    val symbol = data.optString("s")
                    val price = data.optString("p").toDoubleOrNull() ?: return
                    val eventTime = data.optLong("E", System.currentTimeMillis())
                    if (symbol.isNotBlank()) {
                        val firstRealData = connectedMarkets.add(marketType)
                        lastDataAt[marketType] = System.currentTimeMillis()
                        if (firstRealData) updateLiveStatus()
                        processLivePrice(marketType, symbol, price, eventTime)
                    }
'''
if old_message_tail not in service:
    raise RuntimeError("onMessage price block not found")
service = service.replace(old_message_tail, new_message_tail, 1)

# Clear real-data freshness when a socket goes away.
service = service.replace(
    '                connectedMarkets.remove(marketType)\n                webSocket.close(code, reason)',
    '                connectedMarkets.remove(marketType)\n                lastDataAt.remove(marketType)\n                webSocket.close(code, reason)',
    1,
)
service = service.replace(
    '                connectedMarkets.remove(marketType)\n                scheduleReconnect(marketType, generation)',
    '                connectedMarkets.remove(marketType)\n                lastDataAt.remove(marketType)\n                scheduleReconnect(marketType, generation)',
    1,
)
service = service.replace(
    '                connectedMarkets.remove(marketType)\n                updateMonitor("${marketType.label} Live временно недоступен • REST-резерв")',
    '                connectedMarkets.remove(marketType)\n                lastDataAt.remove(marketType)\n                updateMonitor("${marketType.label} Live временно недоступен • REST-резерв")',
    1,
)

# Sample live prices 4x/sec into the rolling window. This is fast enough to
# catch sharp one-minute moves without retaining every individual trade.
service = service.replace(
    '            if (last == null || eventTime - last.timestamp >= 1_000L) {',
    '            if (last == null || eventTime - last.timestamp >= 250L) {',
    1,
)

# Evaluate against the highest price in the rolling window for DROP and the
# lowest price for RISE. This matches screener-style "moved X% within N min".
old_live_eval = '''        symbolRules.forEach { rule ->
            val baseline = findBaseline(marketType, symbol, eventTime - rule.windowMinutes * 60_000L) ?: return@forEach
            evaluateRule(rule, price, baseline, eventTime)
        }
'''
new_live_eval = '''        symbolRules.forEach { rule ->
            val reference = findExtremeReference(rule, eventTime) ?: return@forEach
            evaluateRule(rule, price, reference, eventTime)
        }
'''
if old_live_eval not in service:
    raise RuntimeError("live evaluation block not found")
service = service.replace(old_live_eval, new_live_eval, 1)

baseline_pattern = re.compile(
    r'''    private fun findBaseline\(marketType: MarketType, symbol: String, targetTime: Long\): Double\? \{.*?\n    \}\n\n    private fun evaluateRule''',
    re.S,
)
extreme_function = '''    private fun findExtremeReference(rule: AlarmRule, eventTime: Long): Double? {
        val deque = history[key(rule.marketType, rule.symbol)] ?: return null
        val cutoff = eventTime - rule.windowMinutes * 60_000L
        synchronized(deque) {
            var reference: Double? = null
            val iterator = deque.iterator()
            while (iterator.hasNext()) {
                val point = iterator.next()
                if (point.timestamp < cutoff || point.timestamp > eventTime) continue
                reference = when (rule.direction) {
                    AlertDirection.DROP -> if (reference == null) point.price else maxOf(reference, point.price)
                    AlertDirection.RISE -> if (reference == null) point.price else minOf(reference, point.price)
                }
            }
            return reference
        }
    }

    private fun evaluateRule'''
service, count = baseline_pattern.subn(extreme_function, service, count=1)
if count != 1:
    raise RuntimeError(f"findBaseline replacement failed: {count}")

# REST history should include the live high/low of the currently open 1m candle
# so fallback can still detect a sharp candle move. Closed candles retain their
# close point to avoid fabricating the exact time of historical highs/lows.
old_history_loop = '''                    for (i in 0 until arr.length()) {
                        val candle = arr.getJSONArray(i)
                        val closeTime = candle.getLong(6)
                        val close = candle.getString(4).toDouble()
                        if (closeTime <= now) add(PricePoint(closeTime, close))
                    }
'''
new_history_loop = '''                    for (i in 0 until arr.length()) {
                        val candle = arr.getJSONArray(i)
                        val openTime = candle.getLong(0)
                        val closeTime = candle.getLong(6)
                        if (openTime > now) continue

                        val close = candle.getString(4).toDouble()
                        if (closeTime > now) {
                            val high = candle.getString(2).toDouble()
                            val low = candle.getString(3).toDouble()
                            add(PricePoint(now - 2L, high))
                            add(PricePoint(now - 1L, low))
                            add(PricePoint(now, close))
                        } else {
                            add(PricePoint(closeTime, close))
                        }
                    }
'''
if old_history_loop not in service:
    raise RuntimeError("history loop not found")
service = service.replace(old_history_loop, new_history_loop, 1)

# REST fallback must be based on recent DATA, not on the socket's open state.
service = service.replace(
    '''            val first = symbolRules.first()
            if (connectedMarkets.contains(first.marketType)) return@forEach
            val maxWindow = symbolRules.maxOf { it.windowMinutes }
''',
    '''            val first = symbolRules.first()
            val lastLiveData = lastDataAt[first.marketType] ?: 0L
            val liveIsFresh = connectedMarkets.contains(first.marketType) &&
                System.currentTimeMillis() - lastLiveData <= 3_000L
            if (liveIsFresh) return@forEach
            connectedMarkets.remove(first.marketType)

            val maxWindow = symbolRules.maxOf { it.windowMinutes }
''',
    1,
)

old_rest_eval = '''            symbolRules.forEach { rule ->
                val baseline = findBaseline(rule.marketType, rule.symbol, now - rule.windowMinutes * 60_000L) ?: return@forEach
                evaluateRule(rule, current, baseline, now)
            }
'''
new_rest_eval = '''            symbolRules.forEach { rule ->
                val reference = findExtremeReference(rule, now) ?: return@forEach
                evaluateRule(rule, current, reference, now)
            }
'''
if old_rest_eval not in service:
    raise RuntimeError("REST evaluation block not found")
service = service.replace(old_rest_eval, new_rest_eval, 1)

service_path.write_text(service, encoding="utf-8")

# Version bump after all previous build-time migrations.
app = app_path.read_text(encoding="utf-8")
app = app.replace(
    'Версия 1.2.4 • фильтры рынка',
    'Версия 1.2.5 • исправлен движок будильников'
)
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 19', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.5"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.5 alarm engine fix")
