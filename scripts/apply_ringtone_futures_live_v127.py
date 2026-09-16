from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
rule_path = root / "app/src/main/java/com/cryptoalarm/app/AlarmRule.kt"
service_path = root / "app/src/main/java/com/cryptoalarm/app/MarketMonitorService.kt"
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
build_path = root / "app/build.gradle.kts"

# ---------------------------------------------------------------------------
# Persist a user-selected alarm tone.
# ---------------------------------------------------------------------------
rule = rule_path.read_text(encoding="utf-8")
if 'ALARM_TONE_URI' not in rule:
    rule = rule.replace(
        '    private const val SCAN_INTERVAL_SECONDS = "scan_interval_seconds"\n',
        '    private const val SCAN_INTERVAL_SECONDS = "scan_interval_seconds"\n'
        '    private const val ALARM_TONE_URI = "alarm_tone_uri"\n',
        1,
    )

    tail = '''    fun getScanIntervalSeconds(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, 0).getInt(SCAN_INTERVAL_SECONDS, 15).coerceAtLeast(1)
}'''
    replacement = '''    fun getScanIntervalSeconds(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, 0).getInt(SCAN_INTERVAL_SECONDS, 15).coerceAtLeast(1)

    fun setAlarmToneUri(context: android.content.Context, uri: String?) {
        val editor = context.getSharedPreferences(PREFS, 0).edit()
        if (uri.isNullOrBlank()) editor.remove(ALARM_TONE_URI) else editor.putString(ALARM_TONE_URI, uri)
        editor.apply()
    }

    fun getAlarmToneUri(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, 0).getString(ALARM_TONE_URI, null)
}'''
    if tail not in rule:
        raise RuntimeError("Could not locate RuleStore tail")
    rule = rule.replace(tail, replacement, 1)
rule_path.write_text(rule, encoding="utf-8")

# ---------------------------------------------------------------------------
# Make the foreground alarm player use the selected tone and reload it without
# restarting monitoring.
# ---------------------------------------------------------------------------
service = service_path.read_text(encoding="utf-8")
if 'import android.net.Uri' not in service:
    service = service.replace('import android.media.RingtoneManager\n', 'import android.media.RingtoneManager\nimport android.net.Uri\n', 1)

if 'ACTION_RELOAD_TONE' not in service:
    service = service.replace(
        '        const val ACTION_SILENCE = "cryptoalarm.SILENCE"\n',
        '        const val ACTION_SILENCE = "cryptoalarm.SILENCE"\n'
        '        const val ACTION_RELOAD_TONE = "cryptoalarm.RELOAD_TONE"\n',
        1,
    )

service = service.replace(
    '''            ACTION_STOP -> stopMonitoring()
            ACTION_SILENCE -> silenceAlarm()
            else -> startMonitoring()
''',
    '''            ACTION_STOP -> stopMonitoring()
            ACTION_SILENCE -> silenceAlarm()
            ACTION_RELOAD_TONE -> reloadAlarmPlayer()
            else -> startMonitoring()
''',
    1,
)

old_prepare = '''    private fun prepareAlarmPlayer() {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
'''
new_prepare = '''    private fun prepareAlarmPlayer() {
        if (player != null) return
        val selectedUri = RuleStore.getAlarmToneUri(this)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uri = selectedUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
'''
if old_prepare not in service:
    raise RuntimeError("Could not locate alarm player preparation")
service = service.replace(old_prepare, new_prepare, 1)

if 'private fun reloadAlarmPlayer()' not in service:
    reload_anchor = '    private fun startAlarmSound() {'
    reload_fn = '''    private fun reloadAlarmPlayer() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        prepareAlarmPlayer()
    }

'''
    if reload_anchor not in service:
        raise RuntimeError("Could not locate startAlarmSound")
    service = service.replace(reload_anchor, reload_fn + reload_anchor, 1)
service_path.write_text(service, encoding="utf-8")

# ---------------------------------------------------------------------------
# Settings: Android ringtone picker + preview. System alarm/notification/ring
# tones can be chosen without storage permissions.
# ---------------------------------------------------------------------------
app = app_path.read_text(encoding="utf-8")
imports = [
    ('import android.content.Intent\n', 'import android.content.Intent\nimport android.media.Ringtone\nimport android.media.RingtoneManager\n'),
    ('import androidx.activity.ComponentActivity\n', 'import androidx.activity.ComponentActivity\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts\n'),
]
for old, new in imports:
    if new.strip().split('\n')[-1] not in app:
        app = app.replace(old, new, 1)

settings_pattern = re.compile(
    r'''@Composable\nprivate fun VSettings\(activity: ComponentActivity, scan: Int\) \{.*?\n\}\n\nprivate fun vFmt''',
    re.S,
)
settings_replacement = '''@Composable
private fun VSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    var toneUri by remember { mutableStateOf(RuleStore.getAlarmToneUri(activity)) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }

    val effectiveToneUri = remember(toneUri) {
        toneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
    val toneTitle = remember(toneUri) {
        runCatching {
            effectiveToneUri?.let { RingtoneManager.getRingtone(activity, it)?.getTitle(activity) }
        }.getOrNull() ?: "Стандартная тревога"
    }

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (picked != null) {
                preview?.stop()
                preview = null
                toneUri = picked.toString()
                RuleStore.setAlarmToneUri(activity, picked.toString())
                if (RuleStore.isMonitoring(activity)) {
                    activity.startService(
                        Intent(activity, MarketMonitorService::class.java)
                            .setAction(MarketMonitorService.ACTION_RELOAD_TONE)
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { preview?.stop() }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { VHeader("Настройки", "Spot + Futures") }
        item {
            Surface(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = VSurface,
                shape = RoundedCornerShape(17.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Мелодия тревоги", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(toneTitle, color = VMuted, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                preview?.stop()
                                preview = null
                                val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(
                                        RingtoneManager.EXTRA_RINGTONE_TYPE,
                                        RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE
                                    )
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, effectiveToneUri)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия Crypto Alarm")
                                }
                                tonePicker.launch(pickerIntent)
                            }
                        ) { Text("Выбрать") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val current = preview
                                if (current?.isPlaying == true) {
                                    current.stop()
                                    preview = null
                                } else {
                                    current?.stop()
                                    preview = effectiveToneUri?.let { uri ->
                                        runCatching { RingtoneManager.getRingtone(activity, uri) }.getOrNull()
                                    }
                                    preview?.play()
                                }
                            }
                        ) { Text(if (preview?.isPlaying == true) "Стоп" else "▶ Тест") }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            preview?.stop()
                            preview = null
                            toneUri = null
                            RuleStore.setAlarmToneUri(activity, null)
                            if (RuleStore.isMonitoring(activity)) {
                                activity.startService(
                                    Intent(activity, MarketMonitorService::class.java)
                                        .setAction(MarketMonitorService.ACTION_RELOAD_TONE)
                                )
                            }
                        }
                    ) { Text("Вернуть стандартную") }
                }
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Резервный REST-скан", fontWeight = FontWeight.Bold)
                    Text("Для сигналов при потере Live WebSocket", fontSize = 12.sp, color = VMuted)
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Секунд") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            RuleStore.setScanIntervalSeconds(activity, (seconds.toIntOrNull() ?: 1).coerceAtLeast(1))
                        }) { Text("Сохранить") }
                    }
                }
            }
        }
        item { VAction("🔋", "Оптимизация батареи", "Разрешить работу мониторинга в фоне") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") }) }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } } }
        item { VAction("⏰", "Надёжный перезапуск", "Разрешить точные проверки watchdog ночью") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${activity.packageName}") }) } } }
        item { VAction("🔇", "Остановить звук", "Заглушить активную тревогу") { activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE)) } }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 1.2.7 • мелодии + Futures Live", color = VMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun vFmt'''
app, count = settings_pattern.subn(settings_replacement, app, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace settings screen: {count}")

# Faster visible Futures fallback if the network blocks the Futures websocket.
app = app.replace('        delay(3_000L)\n        while (market == MarketType.FUTURES && !liveConnected) {',
                  '        delay(2_000L)\n        while (market == MarketType.FUTURES && !liveConnected) {', 1)
app = app.replace('            delay(750L)\n        }\n    }', '            delay(500L)\n        }\n    }', 1)
app_path.write_text(app, encoding="utf-8")

# ---------------------------------------------------------------------------
# Futures chart WebSocket: use Binance's documented live SUBSCRIBE mechanism
# first, then alternate to the direct raw kline stream. Detect streams that go
# stale after they were initially LIVE and reconnect automatically.
# ---------------------------------------------------------------------------
socket = socket_path.read_text(encoding="utf-8")
if 'import android.os.SystemClock' not in socket:
    socket = socket.replace('import android.os.Looper\n', 'import android.os.Looper\nimport android.os.SystemClock\n', 1)

if 'private var lastDataAt' not in socket:
    socket = socket.replace(
        '    private var dataSeen = false\n    private var reconnectAttempt = 0\n',
        '    private var dataSeen = false\n    @Volatile private var lastDataAt = 0L\n    private var reconnectAttempt = 0\n',
        1,
    )

old_watchdog = '''    private val noDataWatchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!dataSeen) {
                socket?.cancel()
                scheduleReconnect(400L)
            } else {
                mainHandler.postDelayed(this, 10_000L)
            }
        }
    }
'''
new_watchdog = '''    private val noDataWatchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val stale = lastDataAt == 0L || now - lastDataAt > 8_000L
            if (!dataSeen || stale) {
                mainHandler.post { if (!stopped) onConnectedChanged(false) }
                socket?.cancel()
            } else {
                mainHandler.postDelayed(this, 3_000L)
            }
        }
    }
'''
if old_watchdog not in socket:
    raise RuntimeError("Could not locate socket watchdog")
socket = socket.replace(old_watchdog, new_watchdog, 1)

socket = socket.replace(
    '        dataSeen = false\n        mainHandler.post { onConnectedChanged(false) }',
    '        dataSeen = false\n        lastDataAt = 0L\n        mainHandler.post { onConnectedChanged(false) }',
    1,
)

socket = socket.replace(
    '            MarketType.FUTURES -> "wss://fstream.binance.com/ws/$s@kline_$interval"',
    '''            MarketType.FUTURES -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://fstream.binance.com/ws"
                } else {
                    "wss://fstream.binance.com/ws/$s@kline_$interval"
                }
            }''',
    1,
)

old_open = '''            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Do not show LIVE yet. We only mark the stream live after
                // receiving an actual market-data event.
                mainHandler.postDelayed(noDataWatchdog, 6_000L)
            }
'''
new_open = '''            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Binance Futures supports live subscription messages on the
                // base websocket. This is the primary connection mode in v1.2.7.
                if (marketType == MarketType.FUTURES && reconnectAttempt % 2 == 0) {
                    val subscription = JSONObject()
                        .put("method", "SUBSCRIBE")
                        .put("params", org.json.JSONArray().put("$s@kline_$interval"))
                        .put("id", 1)
                        .toString()
                    webSocket.send(subscription)
                }
                mainHandler.postDelayed(noDataWatchdog, 5_000L)
            }
'''
if old_open not in socket:
    raise RuntimeError("Could not locate LiveChartSocket onOpen")
socket = socket.replace(old_open, new_open, 1)

old_mark = '''    private fun markDataSeen() {
        if (!dataSeen) {
            dataSeen = true
            reconnectAttempt = 0
            mainHandler.post { if (!stopped) onConnectedChanged(true) }
        }
    }
'''
new_mark = '''    private fun markDataSeen() {
        lastDataAt = SystemClock.elapsedRealtime()
        if (!dataSeen) {
            dataSeen = true
            reconnectAttempt = 0
            mainHandler.post { if (!stopped) onConnectedChanged(true) }
        }
    }
'''
if old_mark not in socket:
    raise RuntimeError("Could not locate markDataSeen")
socket = socket.replace(old_mark, new_mark, 1)
socket = socket.replace('User-Agent", "CryptoAlarm/1.2.2"', 'User-Agent", "CryptoAlarm/1.2.7"')
socket_path.write_text(socket, encoding="utf-8")

# Final version bump after all previous build-time migrations.
build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 21', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.7"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.7 ringtone picker + robust Futures Live")
