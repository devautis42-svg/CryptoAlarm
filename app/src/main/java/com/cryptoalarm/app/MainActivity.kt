package com.cryptoalarm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    val dark = darkColorScheme()
    MaterialTheme(colorScheme = dark) {
        var rules by remember { mutableStateOf(RuleStore.load(this)) }
        var monitoring by remember { mutableStateOf(RuleStore.isMonitoring(this)) }
        var symbol by remember { mutableStateOf("BTCUSDT") }
        var drop by remember { mutableStateOf("3") }
        var minutes by remember { mutableIntStateOf(15) }
        var menuOpen by remember { mutableStateOf(false) }

        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@CryptoAlarmApp, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Crypto Alarm", fontWeight = FontWeight.Bold) }) }
        ) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (monitoring) "🟢 Мониторинг включён" else "⚫ Мониторинг выключен", style = MaterialTheme.typography.titleMedium)
                            Text("Проверка рынка каждые 15 секунд. API-ключ Binance не нужен.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = rules.any { it.enabled } && !monitoring,
                                    onClick = {
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
                                OutlinedButton(
                                    onClick = { startService(Intent(this@CryptoAlarmApp, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE)) }
                                ) { Text("Тишина") }
                            }
                        }
                    }
                }

                item { Text("Создать тревогу", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("BTCUSDT", "ETHUSDT", "SOLUSDT").forEach { s ->
                            FilterChip(
                                selected = symbol == s,
                                onClick = { symbol = s },
                                label = { Text(s.removeSuffix("USDT")) }
                            )
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = drop,
                        onValueChange = { drop = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.') },
                        label = { Text("Падение, %") },
                        supportingText = { Text("Например: 3 = тревога при падении на 3% или больше") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = !menuOpen }) {
                        OutlinedTextField(
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            value = "$minutes минут",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("За какой период") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) }
                        )
                        ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            listOf(5, 15, 30, 60).forEach { m ->
                                DropdownMenuItem(text = { Text("$m минут") }, onClick = { minutes = m; menuOpen = false })
                            }
                        }
                    }
                }

                item {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            val pct = drop.toDoubleOrNull()
                            if (pct != null && pct > 0) {
                                rules = rules + AlarmRule(symbol = symbol, dropPercent = pct, windowMinutes = minutes)
                                RuleStore.save(this@CryptoAlarmApp, rules)
                            }
                        }
                    ) { Text("+ Добавить будильник") }
                }

                item { HorizontalDivider(); Text("Мои будильники", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }

                if (rules.isEmpty()) {
                    item { Text("Пока нет правил. Добавь первое выше.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                items(rules, key = { it.id }) { rule ->
                    ElevatedCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Падение ≥ ${rule.dropPercent}% за ${rule.windowMinutes} мин")
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
                    Text(
                        "Важно: приложение не торгует и не подключается к биржевому аккаунту. Оно только читает публичные цены и подаёт сигнал.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
