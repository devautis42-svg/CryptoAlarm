from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

socket = socket_path.read_text(encoding="utf-8")

old = '''        val s = symbol.lowercase()
        val streams = "$s@aggTrade/$s@miniTicker"
        val url = when (marketType) {
            MarketType.SPOT -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://stream.binance.com:443/stream?streams=$streams"
                } else {
                    "wss://data-stream.binance.vision/stream?streams=$streams"
                }
            }
            MarketType.FUTURES -> "wss://fstream.binance.com/stream?streams=$streams"
        }
'''

new = '''        val s = symbol.lowercase()
        val spotStreams = "$s@aggTrade/$s@miniTicker"
        val url = when (marketType) {
            MarketType.SPOT -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://stream.binance.com:443/stream?streams=$spotStreams"
                } else {
                    "wss://data-stream.binance.vision/stream?streams=$spotStreams"
                }
            }
            MarketType.FUTURES -> {
                // v1.2.1: prefer the simplest raw Futures aggTrade stream.
                // Some mobile networks were leaving the combined Futures
                // aggTrade + miniTicker connection stuck in CONNECTING.
                if (reconnectAttempt % 2 == 0) {
                    "wss://fstream.binance.com/ws/$s@aggTrade"
                } else {
                    "wss://fstream.binance.com/stream?streams=$s@aggTrade"
                }
            }
        }
'''

if old not in socket:
    raise RuntimeError("Could not locate Binance chart endpoint block")
socket = socket.replace(old, new, 1)
socket = socket.replace('User-Agent", "CryptoAlarm/1.1.1"', 'User-Agent", "CryptoAlarm/1.2.1"')
socket_path.write_text(socket, encoding="utf-8")

app = app_path.read_text(encoding="utf-8")
app = app.replace("Версия 1.2.0 • плавный Live Chart", "Версия 1.2.1 • плавный Live Chart + Futures fix")
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 15', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.1"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.1 Futures LIVE fix")
