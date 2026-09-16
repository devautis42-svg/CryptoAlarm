from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

socket = socket_path.read_text(encoding="utf-8")

# Add a diagnostic callback to the chart socket so the UI can show the exact
# Futures connection phase / error instead of a generic CONNECTING label.
ctor_old = '''    private val interval: String,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onTrade: (LiveTrade) -> Unit,
    private val onKline: (LiveKlineUpdate) -> Unit = {}
) {'''
ctor_new = '''    private val interval: String,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onTrade: (LiveTrade) -> Unit,
    private val onKline: (LiveKlineUpdate) -> Unit = {},
    private val onDiagnosticChanged: (String) -> Unit = {}
) {'''
if ctor_old not in socket:
    raise RuntimeError("Could not locate LiveChartSocket constructor")
socket = socket.replace(ctor_old, ctor_new, 1)

# Helper keeps diagnostic updates on the main thread and trims noisy exception
# strings so the message remains readable on a phone screen.
insert_before = '    private fun connect() {'
helper = '''    private fun diagnostic(message: String) {
        val compact = message.replace("\\n", " ").replace("\\r", " ").take(140)
        mainHandler.post { if (!stopped) onDiagnosticChanged(compact) }
    }

'''
if 'private fun diagnostic(message: String)' not in socket:
    if insert_before not in socket:
        raise RuntimeError("Could not locate connect()")
    socket = socket.replace(insert_before, helper + insert_before, 1)

# Report which Futures connection mode is being attempted.
url_anchor = '''        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CryptoAlarm/1.2.7")
            .build()
'''
if url_anchor not in socket:
    # Be tolerant if the user-agent stayed at the previous version.
    url_anchor = re.search(r'''        val request = Request\.Builder\(\)\n            \.url\(url\)\n            \.header\("User-Agent", "CryptoAlarm/[^"]+"\)\n            \.build\(\)\n''', socket)
    if not url_anchor:
        raise RuntimeError("Could not locate chart request block")
    old_request = url_anchor.group(0)
else:
    old_request = url_anchor

new_request = '''        if (marketType == MarketType.FUTURES) {
            val mode = if (reconnectAttempt % 2 == 0) "SUBSCRIBE /ws" else "RAW kline"
            diagnostic("CONNECTING • Futures • $mode")
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CryptoAlarm/1.2.8")
            .build()
'''
socket = socket.replace(old_request, new_request, 1)

# Enrich onOpen with connection phase information while preserving the v1.2.7
# SUBSCRIBE behavior.
open_pattern = re.compile(
    r'''            override fun onOpen\(webSocket: WebSocket, response: Response\) \{.*?\n                mainHandler\.postDelayed\(noDataWatchdog, 6_000L\)\n            \}\n''',
    re.S,
)
match = open_pattern.search(socket)
if not match:
    raise RuntimeError("Could not locate onOpen block")
old_open = match.group(0)
new_open = old_open.replace(
    '            override fun onOpen(webSocket: WebSocket, response: Response) {\n',
    '            override fun onOpen(webSocket: WebSocket, response: Response) {\n'
    '                if (marketType == MarketType.FUTURES) {\n'
    '                    val mode = if (reconnectAttempt % 2 == 0) "SUBSCRIBE /ws" else "RAW kline"\n'
    '                    diagnostic("SOCKET OPEN • $mode")\n'
    '                }\n',
    1,
)
# Report successful send of the SUBSCRIBE request.
new_open = new_open.replace(
    '                    webSocket.send(subscription)\n',
    '                    val sent = webSocket.send(subscription)\n'
    '                    diagnostic(if (sent) "SUBSCRIBE SENT • жду подтверждение" else "SUBSCRIBE SEND FAILED")\n',
    1,
)
socket = socket.replace(old_open, new_open, 1)

# Show Binance subscription acknowledgement messages that otherwise have no
# event type and were silently ignored.
msg_anchor = '''                    val eventType = data.optString("e")

                    when (eventType) {'''
msg_replacement = '''                    val eventType = data.optString("e")
                    if (marketType == MarketType.FUTURES && outer.has("id") && outer.has("result")) {
                        val resultText = if (outer.isNull("result")) "OK" else outer.opt("result")?.toString() ?: "OK"
                        diagnostic("SUBSCRIBE $resultText • жду kline data")
                    }

                    when (eventType) {'''
if msg_anchor not in socket:
    raise RuntimeError("Could not locate WebSocket message parser")
socket = socket.replace(msg_anchor, msg_replacement, 1)

# The first actual kline packet is the strongest signal that Futures WS is
# genuinely live.
kline_anchor = '''                            markDataSeen()
                            if (!stopped) onKline(update)
'''
kline_replacement = '''                            val firstPacket = !dataSeen
                            markDataSeen()
                            if (firstPacket && marketType == MarketType.FUTURES) {
                                diagnostic("LIVE • Futures kline data идёт")
                            }
                            if (!stopped) onKline(update)
'''
if kline_anchor not in socket:
    raise RuntimeError("Could not locate kline dispatch")
socket = socket.replace(kline_anchor, kline_replacement, 1)

# Report stale-data watchdog events before cancelling the connection.
watch_anchor = '''            if (!dataSeen || stale) {
                mainHandler.post { if (!stopped) onConnectedChanged(false) }
                socket?.cancel()
            } else {'''
watch_replacement = '''            if (!dataSeen || stale) {
                if (marketType == MarketType.FUTURES) {
                    val reason = if (!dataSeen) "NO DATA • 6–8с после открытия" else "STALE • данные не идут >8с"
                    diagnostic(reason)
                }
                mainHandler.post { if (!stopped) onConnectedChanged(false) }
                socket?.cancel()
            } else {'''
if watch_anchor not in socket:
    raise RuntimeError("Could not locate stale watchdog branch")
socket = socket.replace(watch_anchor, watch_replacement, 1)

# Capture close codes and the actual Throwable class/message on failures.
closing_anchor = '''            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped) return
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }
'''
closing_replacement = '''            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped) return
                if (marketType == MarketType.FUTURES) {
                    diagnostic("CLOSED • code=$code • ${reason.ifBlank { "без причины" }}")
                }
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }
'''
if closing_anchor not in socket:
    raise RuntimeError("Could not locate onClosed")
socket = socket.replace(closing_anchor, closing_replacement, 1)

failure_anchor = '''            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }
'''
failure_replacement = '''            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                if (marketType == MarketType.FUTURES) {
                    val http = response?.code?.let { "HTTP $it • " } ?: ""
                    val kind = t::class.java.simpleName.ifBlank { "WebSocketError" }
                    val detail = t.message?.take(90)?.ifBlank { null } ?: "без текста"
                    diagnostic("ERROR • ${http}$kind • $detail")
                }
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }
'''
if failure_anchor not in socket:
    raise RuntimeError("Could not locate onFailure")
socket = socket.replace(failure_anchor, failure_replacement, 1)

socket_path.write_text(socket, encoding="utf-8")

# ---------------------------------------------------------------------------
# Chart UI: surface the latest WS diagnostic directly below the LIVE badge and
# explicitly label REST fallback so it cannot be mistaken for "reset".
# ---------------------------------------------------------------------------
app = app_path.read_text(encoding="utf-8")

state_anchor = '    var restFallbackActive by remember { mutableStateOf(false) }\n'
if state_anchor not in app:
    raise RuntimeError("Could not locate restFallbackActive state")
if 'wsDiagnostic' not in app:
    app = app.replace(
        state_anchor,
        state_anchor + '    var wsDiagnostic by remember(market, symbol, timeframe.api) { mutableStateOf("ожидание подключения") }\n',
        1,
    )

# Wire the diagnostic callback into LiveChartSocket.
socket_call_anchor = '''            onTrade = { trade -> liveTrades.offer(trade) },
            onKline = { kline -> liveKlines.offer(kline) }
'''
socket_call_replacement = '''            onTrade = { trade -> liveTrades.offer(trade) },
            onKline = { kline -> liveKlines.offer(kline) },
            onDiagnosticChanged = { text -> wsDiagnostic = text }
'''
if socket_call_anchor not in app:
    raise RuntimeError("Could not locate LiveChartSocket call")
app = app.replace(socket_call_anchor, socket_call_replacement, 1)

# Rename the fallback badge for clarity.
app = app.replace('restFallbackActive -> "↻ LIVE REST"', 'restFallbackActive -> "↻ REST LIVE"')

# Add diagnostic text immediately after the badge Surface in the chart header.
# We anchor on the exact text block emitted by v1.2.3 and retained by v1.2.7.
badge_tail = '''                        fontSize = 11.sp
                    )
                }
'''
# Find the first occurrence after the REST LIVE badge only.
badge_pos = app.find('restFallbackActive -> "↻ REST LIVE"')
if badge_pos < 0:
    raise RuntimeError("Could not locate chart LIVE badge")
tail_pos = app.find(badge_tail, badge_pos)
if tail_pos < 0:
    raise RuntimeError("Could not locate end of chart LIVE badge")
insert_pos = tail_pos + len(badge_tail)
diag_ui = '''                if (market == MarketType.FUTURES) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = Color(0xFF0C1725),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, VBorder.copy(alpha = .65f))
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("Диагностика Futures WS", color = VMuted, fontSize = 10.sp)
                            Text(
                                if (restFallbackActive && !liveConnected) "$wsDiagnostic • REST fallback активен" else wsDiagnostic,
                                color = if (liveConnected) VGreen else if (restFallbackActive) Color(0xFFFFC857) else VMuted,
                                fontSize = 11.sp,
                                maxLines = 3
                            )
                        }
                    }
                }
'''
app = app[:insert_pos] + diag_ui + app[insert_pos:]

app = app.replace(
    'Версия 1.2.7 • мелодии + Futures Live',
    'Версия 1.2.8 • диагностика Futures WS'
)
app_path.write_text(app, encoding="utf-8")

# Final version bump.
build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 22', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.8"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.8 Futures WebSocket diagnostics")
