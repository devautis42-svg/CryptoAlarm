from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
service_path = root / "app/src/main/java/com/cryptoalarm/app/MarketMonitorService.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

service = service_path.read_text(encoding="utf-8")

# Track live freshness per symbol, not just per market. One healthy stream must
# not hide a stale symbol from REST fallback.
anchor = '    private val lastDataAt = ConcurrentHashMap<MarketType, Long>()\n'
if anchor not in service:
    raise RuntimeError("Could not locate lastDataAt")
if 'lastSymbolDataAt' not in service:
    service = service.replace(
        anchor,
        anchor + '    private val lastSymbolDataAt = ConcurrentHashMap<String, Long>()\n',
        1,
    )

old_live_stamp = '''                        val firstRealData = connectedMarkets.add(marketType)
                        lastDataAt[marketType] = System.currentTimeMillis()
                        if (firstRealData) updateLiveStatus()
                        processLivePrice(marketType, symbol, price, eventTime)
'''
new_live_stamp = '''                        val receivedAt = System.currentTimeMillis()
                        val firstRealData = connectedMarkets.add(marketType)
                        lastDataAt[marketType] = receivedAt
                        lastSymbolDataAt[key(marketType, symbol)] = receivedAt
                        if (firstRealData) updateLiveStatus()
                        processLivePrice(marketType, symbol, price, eventTime)
'''
if old_live_stamp not in service:
    raise RuntimeError("Could not locate live data freshness block")
service = service.replace(old_live_stamp, new_live_stamp, 1)

# For 1-minute alerts, REST fallback must be responsive even if the user's
# generic fallback interval is much larger. Poll immediately, then at most once
# per second while at least one 1-minute rule is active.
old_fallback_job = '''        fallbackJob = scope.launch {
            while (isActive) {
                val fallbackSeconds = RuleStore.getScanIntervalSeconds(this@MarketMonitorService).coerceAtLeast(1)
                delay(fallbackSeconds * 1000L)
                pollRestFallback()
            }
        }
'''
new_fallback_job = '''        fallbackJob = scope.launch {
            while (isActive) {
                pollRestFallback()
                val configuredSeconds = RuleStore.getScanIntervalSeconds(this@MarketMonitorService).coerceAtLeast(1)
                val hasOneMinuteRule = activeRules.any { it.windowMinutes <= 1 }
                val effectiveSeconds = if (hasOneMinuteRule) minOf(configuredSeconds, 1) else configuredSeconds
                delay(effectiveSeconds * 1000L)
            }
        }
'''
if old_fallback_job not in service:
    raise RuntimeError("Could not locate fallback job")
service = service.replace(old_fallback_job, new_fallback_job, 1)

# Preserve OHLC movement for BOTH open and already-closed 1m candles. The old
# fallback discarded high/low as soon as a candle closed, which could make a
# sharp end-of-minute drop disappear before the next REST poll.
history_pattern = re.compile(
    r'''                    for \(i in 0 until arr\.length\(\)\) \{\n                        val candle = arr\.getJSONArray\(i\)\n                        val openTime = candle\.getLong\(0\)\n                        val closeTime = candle\.getLong\(6\)\n                        if \(openTime > now\) continue\n\n                        val close = candle\.getString\(4\)\.toDouble\(\)\n                        if \(closeTime > now\) \{\n                            val high = candle\.getString\(2\)\.toDouble\(\)\n                            val low = candle\.getString\(3\)\.toDouble\(\)\n                            add\(PricePoint\(now - 2L, high\)\)\n                            add\(PricePoint\(now - 1L, low\)\)\n                            add\(PricePoint\(now, close\)\)\n                        \} else \{\n                            add\(PricePoint\(closeTime, close\)\)\n                        \}\n                    \}\n'''
)
history_replacement = '''                    for (i in 0 until arr.length()) {
                        val candle = arr.getJSONArray(i)
                        val openTime = candle.getLong(0)
                        val closeTime = candle.getLong(6)
                        if (openTime > now) continue

                        val open = candle.getString(1).toDouble()
                        val high = candle.getString(2).toDouble()
                        val low = candle.getString(3).toDouble()
                        val close = candle.getString(4).toDouble()
                        val effectiveEnd = minOf(closeTime, now)
                        val span = (effectiveEnd - openTime).coerceAtLeast(4L)
                        val oneThird = (span / 3L).coerceAtLeast(1L)
                        val t1 = (openTime + oneThird).coerceAtMost(effectiveEnd - 2L)
                        val t2 = (openTime + oneThird * 2L).coerceAtMost(effectiveEnd - 1L)

                        add(PricePoint(openTime, open))
                        if (close >= open) {
                            // Plausible green-candle path: open -> low -> high -> close.
                            add(PricePoint(t1, low))
                            add(PricePoint(t2, high))
                        } else {
                            // Plausible red-candle path: open -> high -> low -> close.
                            add(PricePoint(t1, high))
                            add(PricePoint(t2, low))
                        }
                        add(PricePoint(effectiveEnd, close))
                    }
'''
service, count = history_pattern.subn(history_replacement, service, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace REST OHLC history loop: {count}")

# Freshness decision is per symbol. If ETH is stale, ETH falls back to REST even
# if another Futures symbol is still receiving websocket data.
old_freshness = '''            val lastLiveData = lastDataAt[first.marketType] ?: 0L
            val liveIsFresh = connectedMarkets.contains(first.marketType) &&
                System.currentTimeMillis() - lastLiveData <= 3_000L
'''
new_freshness = '''            val lastLiveData = lastSymbolDataAt[key(first.marketType, first.symbol)] ?: 0L
            val liveIsFresh = connectedMarkets.contains(first.marketType) &&
                System.currentTimeMillis() - lastLiveData <= 3_000L
'''
if old_freshness not in service:
    raise RuntimeError("Could not locate REST freshness check")
service = service.replace(old_freshness, new_freshness, 1)

service_path.write_text(service, encoding="utf-8")

app = app_path.read_text(encoding="utf-8")
app = app.replace(
    'Версия 1.2.8 • диагностика Futures WS',
    'Версия 1.2.9 • надёжные 1-мин будильники'
)
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 23', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.9"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.9 alarm REST reliability")
