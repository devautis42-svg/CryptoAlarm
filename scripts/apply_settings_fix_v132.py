from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

settings_pattern = re.compile(
    r'''@Composable\nprivate fun VSettings\(activity: ComponentActivity, scan: Int\) \{.*?\n\}\n\nprivate fun vFmt''',
    re.S,
)

settings_replacement = '''@Composable
private fun VSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    var toneUri by remember { mutableStateOf(RuleStore.getAlarmToneUri(activity)) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }

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
            ?: runCatching {
                effectiveToneUri?.let { RingtoneManager.getRingtone(activity, it)?.getTitle(activity) }
            }.getOrNull()
            ?: if (toneUri == null) "Стандартная тревога" else "Своя мелодия"
    }

    fun selectTone(uri: String?) {
        preview?.stop()
        preview = null
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
            if (picked != null) selectTone(picked.toString())
        }
    }

    DisposableEffect(Unit) {
        onDispose { preview?.stop() }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            VHeader("Настройки", "Spot + Futures")
        }

        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = VSurface,
                shape = RoundedCornerShape(17.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Мелодия тревоги", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        toneTitle,
                        color = VGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Встроенные мелодии", color = VMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = toneUri == null,
                            onClick = { selectTone(null) },
                            label = { Text("Системная") }
                        )
                        builtInTones.forEach { (title, uri) ->
                            FilterChip(
                                selected = toneUri == uri,
                                onClick = { selectTone(uri) },
                                label = { Text(title) }
                            )
                        }
                    }

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
                                    putExtra(
                                        RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                    )
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, effectiveToneUri)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия Crypto Alarm")
                                }
                                tonePicker.launch(pickerIntent)
                            }
                        ) {
                            Text("Из телефона")
                        }

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
                        ) {
                            Text(if (preview?.isPlaying == true) "Стоп" else "▶ Тест")
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { selectTone(null) }) {
                        Text("Вернуть стандартную")
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = VSurface,
                shape = RoundedCornerShape(17.dp)
            ) {
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
                        Button(
                            onClick = {
                                RuleStore.setScanIntervalSeconds(
                                    activity,
                                    (seconds.toIntOrNull() ?: 1).coerceAtLeast(1)
                                )
                            }
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }

        item {
            VAction(
                "🔋",
                "Оптимизация батареи",
                "Разрешить работу мониторинга в фоне"
            ) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                }.onFailure {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        item {
            VAction(
                "⏰",
                "Надёжный перезапуск",
                "Разрешить точные проверки watchdog ночью"
            ) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                    )
                }
            }
        }

        item {
            VAction(
                "🔇",
                "Остановить звук",
                "Заглушить активную тревогу"
            ) {
                activity.startService(
                    Intent(activity, MarketMonitorService::class.java)
                        .setAction(MarketMonitorService.ACTION_SILENCE)
                )
            }
        }

        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = VSurface,
                shape = RoundedCornerShape(17.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 1.3.2 • мелодии + масштаб цены", color = VMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun vFmt'''

app, count = settings_pattern.subn(settings_replacement, app, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace VSettings after v1.3.1: {count}")

app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 26', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.3.2"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.3.2 settings syntax fix")
