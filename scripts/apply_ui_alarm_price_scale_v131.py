from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# Clean up the temporary Futures diagnostics UI. The socket can keep its
# internal diagnostic callback, but the technical card and redundant top badge
# should not occupy chart space now that routed Futures LIVE is working.
# ---------------------------------------------------------------------------
if 'import androidx.compose.foundation.gestures.detectDragGestures' not in app:
    app = app.replace(
        'import androidx.compose.foundation.gestures.detectTapGestures\n',
        'import androidx.compose.foundation.gestures.detectDragGestures\nimport androidx.compose.foundation.gestures.detectTapGestures\n',
        1,
    )
if 'import android.media.MediaPlayer' not in app:
    app = app.replace(
        'import android.media.Ringtone\n',
        'import android.media.MediaPlayer\nimport android.media.Ringtone\n',
        1,
    )
if 'import kotlin.math.exp' not in app:
    app = app.replace(
        'import kotlin.math.ceil\n',
        'import kotlin.math.ceil\nimport kotlin.math.exp\n',
        1,
    )

badge_block = '''                val anyLive = liveConnected || restFallbackActive
                Surface(
                    color = if (anyLive) Color(0xFF10362E) else Color(0xFF33262B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (anyLive) VGreen.copy(alpha = .5f) else VRed.copy(alpha = .4f))
                ) {
                    Text(
                        when {
                            liveConnected -> "⚡ LIVE"
                            restFallbackActive -> "↻ REST LIVE"
                            else -> "● CONNECTING"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = if (anyLive) VGreen else VMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
'''
if badge_block not in app:
    raise RuntimeError("Could not locate redundant chart LIVE badge")
app = app.replace(badge_block, '', 1)

diag_block = '''                if (market == MarketType.FUTURES) {
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
if diag_block not in app:
    raise RuntimeError("Could not locate Futures diagnostics card")
app = app.replace(diag_block, '', 1)

app = app.replace(
    '    var wsDiagnostic by remember(market, symbol, timeframe.api) { mutableStateOf("ожидание подключения") }\n',
    '',
    1,
)
app = app.replace(
    '''            onTrade = { trade -> liveTrades.offer(trade) },
            onKline = { kline -> liveKlines.offer(kline) },
            onDiagnosticChanged = { text -> wsDiagnostic = text }
''',
    '''            onTrade = { trade -> liveTrades.offer(trade) },
            onKline = { kline -> liveKlines.offer(kline) }
''',
    1,
)

# ---------------------------------------------------------------------------
# TradingView-like vertical price scaling. Horizontal pan/pinch remains exactly
# as before; dragging the right price scale up/down changes only the Y range.
# Double-tap the price scale to return to automatic scaling.
# ---------------------------------------------------------------------------
state_anchor = '    var rulerEnd by remember { mutableStateOf<VRulerPoint?>(null) }\n'
if state_anchor not in app:
    raise RuntimeError("Could not locate chart ruler state")
if 'var priceScale by remember' not in app:
    app = app.replace(
        state_anchor,
        state_anchor + '    var priceScale by remember { mutableFloatStateOf(1f) }\n',
        1,
    )

old_price_block = '''    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val axisMax = maxPrice + pricePadding
    val axisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val priceRange = (axisMax - axisMin).coerceAtLeast(axisMax * .0001)
'''
new_price_block = '''    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val autoAxisMax = maxPrice + pricePadding
    val autoAxisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val autoRange = (autoAxisMax - autoAxisMin).coerceAtLeast(autoAxisMax * .0001)
    val safePriceScale = priceScale.coerceIn(.25f, 8f)
    val axisCenter = (autoAxisMax + autoAxisMin) / 2.0
    val scaledRange = autoRange / safePriceScale.toDouble()
    val rawAxisMin = axisCenter - scaledRange / 2.0
    val axisMin = rawAxisMin.coerceAtLeast(0.0)
    val axisMax = if (rawAxisMin >= 0.0) axisCenter + scaledRange / 2.0 else scaledRange
    val priceRange = (axisMax - axisMin).coerceAtLeast(axisMax * .0001)
'''
if old_price_block not in app:
    raise RuntimeError("Could not locate chart price range block")
app = app.replace(old_price_block, new_price_block, 1)

app = app.replace(
    '"≈${span.roundToInt()} свечей • плавный pinch"',
    '"≈${span.roundToInt()} свечей • цена ×${String.format(Locale.US, "%.2f", safePriceScale)}"',
    1,
)

app = app.replace(
    '.pointerInput(total, rulerMode) {',
    '.pointerInput(total, rulerMode, safePriceScale) {',
    1,
)

old_ruler_price = '''                                        val localMax = currentVisible.maxOfOrNull { it.high } ?: 1.0
                                        val localMin = currentVisible.minOfOrNull { it.low } ?: 0.0
                                        val localPad = ((localMax - localMin) * .06).coerceAtLeast(localMax * .0004)
                                        val localAxisMax = localMax + localPad
                                        val localAxisMin = (localMin - localPad).coerceAtLeast(0.0)
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001)
                                        val plotHeight = size.height * .82f
                                        val price = localAxisMax - (pos.y.coerceIn(0f, plotHeight) / plotHeight) * localRange
'''
new_ruler_price = '''                                        val localMax = currentVisible.maxOfOrNull { it.high } ?: 1.0
                                        val localMin = currentVisible.minOfOrNull { it.low } ?: 0.0
                                        val localPad = ((localMax - localMin) * .06).coerceAtLeast(localMax * .0004)
                                        val localAutoMax = localMax + localPad
                                        val localAutoMin = (localMin - localPad).coerceAtLeast(0.0)
                                        val localAutoRange = (localAutoMax - localAutoMin).coerceAtLeast(localAutoMax * .0001)
                                        val localCenter = (localAutoMax + localAutoMin) / 2.0
                                        val localScaledRange = localAutoRange / safePriceScale.toDouble()
                                        val localRawMin = localCenter - localScaledRange / 2.0
                                        val localAxisMin = localRawMin.coerceAtLeast(0.0)
                                        val localAxisMax = if (localRawMin >= 0.0) localCenter + localScaledRange / 2.0 else localScaledRange
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001)
                                        val plotHeight = size.height * .82f
                                        val price = localAxisMax - (pos.y.coerceIn(0f, plotHeight) / plotHeight) * localRange
'''
if old_ruler_price not in app:
    raise RuntimeError("Could not locate ruler price mapping")
app = app.replace(old_ruler_price, new_ruler_price, 1)

old_axis_modifier = '''                Column(
                    Modifier.align(Alignment.TopEnd).fillMaxHeight().width(58.dp)
                        .padding(top = 2.dp, bottom = 70.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
'''
new_axis_modifier = '''                Column(
                    Modifier.align(Alignment.TopEnd).fillMaxHeight().width(58.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val factor = exp((-dragAmount.y / 220f).toDouble()).toFloat()
                                priceScale = (priceScale * factor).coerceIn(.25f, 8f)
                                selectedIndex = null
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    priceScale = 1f
                                    selectedIndex = null
                                }
                            )
                        }
                        .padding(top = 2.dp, bottom = 70.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
'''
if old_axis_modifier not in app:
    raise RuntimeError("Could not locate right price scale")
app = app.replace(old_axis_modifier, new_axis_modifier, 1)

# ---------------------------------------------------------------------------
# Alarm sounds. Keep the Android ringtone picker, but add four built-in tones
# that are bundled in res/raw and persist by stable android.resource URI name.
# ---------------------------------------------------------------------------
settings_pattern = re.compile(
    r'''@Composable\nprivate fun VSettings\(activity: ComponentActivity, scan: Int\) \{.*?\n\}\n\nprivate fun vFmt''',
    re.S,
)
settings_replacement = '''@Composable
private fun VSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    var toneUri by remember { mutableStateOf(RuleStore.getAlarmToneUri(activity)) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }

    val builtInTones = remember(activity.packageName) {
        listOf(
            "Пульс" to "android.resource://${activity.packageName}/raw/alarm_pulse",
            "Сирена" to "android.resource://${activity.packageName}/raw/alarm_siren",
            "Радар" to "android.resource://${activity.packageName}/raw/alarm_radar",
            "Срочно" to "android.resource://${activity.packageName}/raw/alarm_urgent"
        )
    }

    val effectiveToneUri = remember(toneUri) {
        toneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    val toneTitle = remember(toneUri) {
        builtInTones.firstOrNull { it.second == toneUri }?.first
            ?: if (toneUri == null) {
                "Системная тревога"
            } else {
                runCatching {
                    effectiveToneUri?.let { RingtoneManager.getRingtone(activity, it)?.getTitle(activity) }
                }.getOrNull() ?: "Своя мелодия"
            }
    }

    fun stopPreview() {
        preview?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        preview = null
    }

    fun applyTone(uri: String?) {
        stopPreview()
        toneUri = uri
        RuleStore.setAlarmToneUri(activity, uri)
        if (RuleStore.isMonitoring(activity)) {
            activity.startService(
                Intent(activity, MarketMonitorService::class.java)
                    .setAction(MarketMonitorService.ACTION_RELOAD_TONE)
            )
        }
    }

    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (picked != null) applyTone(picked.toString())
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
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
                    Text(toneTitle, color = VGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    Text("Встроенные мелодии", color = VMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = toneUri == null,
                            onClick = { applyTone(null) },
                            label = { Text("Системная") }
                        )
                        builtInTones.forEach { (title, uri) ->
                            FilterChip(
                                selected = toneUri == uri,
                                onClick = { applyTone(uri) },
                                label = { Text(title) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                stopPreview()
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
                        ) { Text("Из телефона") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (preview?.isPlaying == true) {
                                    stopPreview()
                                } else {
                                    stopPreview()
                                    val p = effectiveToneUri?.let { uri ->
                                        runCatching { MediaPlayer.create(activity, uri) }.getOrNull()
                                    }
                                    preview = p
                                    p?.setOnCompletionListener { finished ->
                                        runCatching { finished.release() }
                                        if (preview === finished) preview = null
                                    }
                                    p?.start()
                                }
                            }
                        ) { Text(if (preview?.isPlaying == true) "Стоп" else "▶ Тест") }
                    }
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
        item { VAction("⏰", "Надёжный перезапуск", "Разрешить точные проверки watchdog ночью") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${activity.packageName}") }) } }
        item { VAction("🔇", "Остановить звук", "Заглушить активную тревогу") { activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE)) } }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 1.3.1 • мелодии + масштаб цены", color = VMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun vFmt'''
app, count = settings_pattern.subn(settings_replacement, app, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace settings screen: {count}")

app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 25', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.3.1"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.3.1 clean UI + alarm presets + price scale")
