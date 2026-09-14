package com.cryptoalarm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CryptoAlarmApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentActivity.CryptoAlarmApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var rules by remember { mutableStateOf(RuleStore.load(this)) }
        var monitoring by remember { mutableStateOf(RuleStore.isMonitoring(this)) }
        var scanSeconds by remember { mutableStateOf(RuleStore.getScanIntervalSeconds(this).toString()) }
        var symbol by remember { mutableStateOf("BTCUSDT") }
        var direction by remember { mutableStateOf(AlertDirection.DROP) }
        var percent by remember { mutableStateOf("0.5") }
        var minutes by remember { mutableStateOf("5") }

        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@CryptoAlarmApp, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("Crypto Alarm", fontWeight = FontWeight.Bold) }) }) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (monitoring) "🟢 Мониторинг включён" else "⚫ Мониторинг выключен", style = MaterialTheme.typography.titleMedium)
                            Text("Частоту сканирования можно настроить от 1 секунды. После перезагрузки мониторинг восстановится автоматически, если был включён.", style = MaterialTheme.typography.bodySmall)

                            OutlinedTextField(
                                value = scanSeconds,
                                onValueChange = { scanSeconds = it.filter(Char::isDigit).take(5) },
                                label = { Text("Сканировать каждые, секунд") },
                                supportingText = { Text("Минимум 1 секунда") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val value = ((scanSeconds.toIntOrNull() ?: 1) - 1).coerceAtLeast(1)
                                    scanSeconds = value.toString()
                                    RuleStore.setScanIntervalSeconds(this@CryptoAlarmApp, value)
                                }) { Text("−1 сек") }
                                OutlinedButton(onClick = {
                                    val value = ((scanSeconds.toIntOrNull() ?: 1) + 1).coerceAtMost(99999)
                                    scanSeconds = value.toString()
                                    RuleStore.setScanIntervalSeconds(this@CryptoAlarmApp, value)
                                }) { Text("+1 сек") }
                                Button(onClick = {
                                    val value = (scanSeconds.toIntOrNull() ?: 1).coerceAtLeast(1)
                                    scanSeconds = value.toString()
                                    RuleStore.setScanIntervalSeconds(this@CryptoAlarmApp, value)
                                }) { Text("Сохранить") }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = rules.any { it.enabled } && !monitoring,
                                    onClick = {
                                        val value = (scanSeconds.toIntOrNull() ?: 1).coerceAtLeast(1)
                                        RuleStore.setScanIntervalSeconds(this@CryptoAlarmApp, value)
                                        val intent = Intent(this@CryptoAlarmApp, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
                                        ContextCompat.startForegroundService(this@CryptoAlarmApp, intent)
                                        monitoring = true
                                    }
                                ) { Text("Включить") }
                                OutlinedButton(
                                    enabled = monitoring,
                                    onClick = {
                                        startService(Intent(this@CryptoAlarmApp, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
                                        monitoring = false
                                    }
                                ) { Text("Выключить") }
                                OutlinedButton(onClick = {
                                    startService(Intent(this@CryptoAlarmApp, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE))
                                }) { Text("Тишина") }
                            }
                            OutlinedButton(onClick = {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:$packageName")
                                    })
                                }.onFailure {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            }) { Text("Не ограничивать батареей") }
                        }
                    }
                }

                item { Text("Создать тревогу", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("BTCUSDT", "ETHUSDT", "SOLUSDT").forEach { s ->
                            FilterChip(selected = symbol == s, onClick = { symbol = s }, label = { Text(s.removeSuffix("USDT")) })
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = direction == AlertDirection.DROP,
                            onClick = { direction = AlertDirection.DROP },
                            label = { Text("📉 Падение") }
                        )
                        FilterChip(
                            selected = direction == AlertDirection.RISE,
                            onClick = { direction = AlertDirection.RISE },
                            label = { Text("📈 Рост") }
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = percent,
                        onValueChange = { value -> percent = value.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.') },
                        label = { Text("Изменение, %") },
                        supportingText = { Text("От 0.1% до 99.9%. Например: 0.1, 0.5, 1.7") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val value = ((percent.toDoubleOrNull() ?: 0.1) - 0.1).coerceAtLeast(0.1)
                            percent = String.format(java.util.Locale.US, "%.1f", value)
                        }) { Text("−0.1") }
                        OutlinedButton(onClick = {
                            val value = ((percent.toDoubleOrNull() ?: 0.1) + 0.1).coerceAtMost(99.9)
                            percent = String.format(java.util.Locale.US, "%.1f", value)
                        }) { Text("+0.1") }
                    }
                }

                item {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                        label = { Text("Период, минут") },
                        supportingText = { Text("Любое значение от 1 до 999 минут") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val value = ((minutes.toIntOrNull() ?: 1) - 1).coerceAtLeast(1)
                            minutes = value.toString()
                        }) { Text("−1 мин") }
                        OutlinedButton(onClick = {
                            val value = ((minutes.toIntOrNull() ?: 1) + 1).coerceAtMost(999)
                            minutes = value.toString()
                        }) { Text("+1 мин") }
                    }
                }

                item {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            val pct = percent.toDoubleOrNull()
                            val mins = minutes.toIntOrNull()
                            if (pct != null && pct in 0.1..99.9 && mins != null && mins in 1..999) {
                                rules = rules + AlarmRule(symbol = symbol, thresholdPercent = pct, windowMinutes = mins, direction = direction)
                                RuleStore.save(this@CryptoAlarmApp, rules)
                            }
                        }
                    ) { Text("+ Добавить будильник") }
                }

                item { HorizontalDivider(); Text("Мои будильники", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

                if (rules.isEmpty()) item { Text("Пока нет правил. Добавь первое выше.", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                items(rules, key = { it.id }) { rule ->
                    ElevatedCard {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                val label = if (rule.direction == AlertDirection.DROP) "Падение" else "Рост"
                                val icon = if (rule.direction == AlertDirection.DROP) "📉" else "📈"
                                Text("$icon $label ≥ ${rule.thresholdPercent}% за ${rule.windowMinutes} мин")
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { on ->
                                    rules = rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }
                                    RuleStore.save(this@CryptoAlarmApp, rules)
                                }
                            )
                            TextButton(onClick = {
                                rules = rules.filterNot { it.id == rule.id }
                                RuleStore.save(this@CryptoAlarmApp, rules)
                            }) { Text("Удалить") }
                        }
                    }
                }

                item {
                    Text("Важно: сканирование каждую 1 секунду сильнее расходует батарею и чаще обращается к публичному API Binance. Для обычного использования разумнее 3–10 секунд.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
