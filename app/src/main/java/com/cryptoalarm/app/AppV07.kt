package com.cryptoalarm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.util.Locale

private val VBlue = Color(0xFF3B6CFF)
private val VGreen = Color(0xFF00D39A)
private val VRed = Color(0xFFFF5C6C)
private val VCard = Color(0xFF142030)
private val VBg = Color(0xFF07101A)

private enum class VTab(val label: String, val icon: String) {
    HOME("Главная", "⌂"), ALARMS("Будильники", "◉"), MARKET("Рынок", "▥"), CHART("График", "⌁"), SETTINGS("Настройки", "⚙")
}

private data class VTimeframe(val label: String, val api: String)
private val vTimeframes = listOf(
    VTimeframe("1м", "1m"), VTimeframe("5м", "5m"), VTimeframe("15м", "15m"),
    VTimeframe("1ч", "1h"), VTimeframe("4ч", "4h"), VTimeframe("1д", "1d"), VTimeframe("1н", "1w")
)

@Composable
fun ComponentActivity.CryptoAlarmV07App() {
    val activity = this
    val colors = darkColorScheme(
        primary = VBlue,
        secondary = VGreen,
        background = VBg,
        surface = Color(0xFF0E1723),
        surfaceVariant = VCard
    )

    MaterialTheme(colorScheme = colors) {
        var tab by remember { mutableStateOf(VTab.HOME) }
        var rules by remember { mutableStateOf(RuleStore.load(activity)) }
        var monitoring by remember { mutableStateOf(RuleStore.isMonitoring(activity)) }
        var market by remember { mutableStateOf(MarketType.SPOT) }
        var symbol by remember { mutableStateOf("BTCUSDT") }
        var spotCoins by remember { mutableStateOf(CoinRepository.fallback()) }
        var futuresCoins by remember { mutableStateOf(CoinRepository.fallback()) }

        LaunchedEffect(Unit) {
            spotCoins = CoinRepository.loadUsdtSymbols(MarketType.SPOT)
            futuresCoins = CoinRepository.loadUsdtSymbols(MarketType.FUTURES)
        }

        Scaffold(
            containerColor = VBg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B1420)) {
                    VTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.icon, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(item.label, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    VTab.HOME -> VHome(
                        rules = rules,
                        monitoring = monitoring,
                        onToggle = {
                            if (monitoring) {
                                activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
                                monitoring = false
                            } else if (rules.any { it.enabled }) {
                                ContextCompat.startForegroundService(
                                    activity,
                                    Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
                                )
                                monitoring = true
                            }
                        },
                        onAlarms = { tab = VTab.ALARMS },
                        onMarket = { tab = VTab.MARKET },
                        onChart = { tab = VTab.CHART }
                    )
                    VTab.ALARMS -> VAlarms(
                        rules = rules,
                        spotCoins = spotCoins,
                        futuresCoins = futuresCoins,
                        onRules = {
                            rules = it
                            RuleStore.save(activity, it)
                        }
                    )
                    VTab.MARKET -> VMarket(
                        market = market,
                        coins = if (market == MarketType.SPOT) spotCoins else futuresCoins,
                        onMarket = { market = it },
                        onChart = { picked -> symbol = picked; tab = VTab.CHART }
                    )
                    VTab.CHART -> VChart(
                        market = market,
                        symbol = symbol,
                        coins = if (market == MarketType.SPOT) spotCoins else futuresCoins,
                        onMarket = { newMarket ->
                            market = newMarket
                            val list = if (newMarket == MarketType.SPOT) spotCoins else futuresCoins
                            if (symbol.removeSuffix("USDT") !in list) symbol = "BTCUSDT"
                        },
                        onSymbol = { symbol = it }
                    )
                    VTab.SETTINGS -> VSettings(
                        activity = activity,
                        scan = RuleStore.getScanIntervalSeconds(activity)
                    )
                }
            }
        }
    }
}

@Composable
private fun VTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MarketSwitch(market: MarketType, onMarket: (MarketType) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = market == MarketType.SPOT, onClick = { onMarket(MarketType.SPOT) }, label = { Text("Spot") })
        FilterChip(selected = market == MarketType.FUTURES, onClick = { onMarket(MarketType.FUTURES) }, label = { Text("Futures USDT-M") })
    }
}

@Composable
private fun VHome(
    rules: List<AlarmRule>, monitoring: Boolean, onToggle: () -> Unit,
    onAlarms: () -> Unit, onMarket: () -> Unit, onChart: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { VTitle("Crypto Alarm", "Spot + Futures под контролем") }
        item {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2A26))
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (monitoring) "●" else "○", color = if (monitoring) VGreen else Color.Gray, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (monitoring) "Live мониторинг активен" else "Мониторинг выключен", fontWeight = FontWeight.Bold)
                        Text("Отдельные потоки Spot и Futures", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("⚡", color = VGreen, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item {
            Button(onClick = onToggle, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (monitoring) "■ Остановить мониторинг" else "▶ Начать мониторинг", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallMetric("${rules.count { it.enabled }}", "Активных", Modifier.weight(1f), VBlue)
                SmallMetric("${rules.count { it.marketType == MarketType.SPOT }}", "Spot", Modifier.weight(1f), VGreen)
                SmallMetric("${rules.count { it.marketType == MarketType.FUTURES }}", "Futures", Modifier.weight(1f), Color(0xFFFFB34D))
            }
        }
        item { VAction("＋", "Создать будильник", "Spot или Futures", onAlarms) }
        item { VAction("▥", "Смотреть рынок", "Переключение Spot / Futures", onMarket) }
        item { VAction("⌁", "Открыть график", "Свечи Spot и Futures", onChart) }
    }
}

@Composable
private fun SmallMetric(value: String, label: String, modifier: Modifier, color: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = VCard), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = VCard)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = VBlue, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›")
        }
    }
}

@Composable
private fun VAlarms(
    rules: List<AlarmRule>, spotCoins: List<String>, futuresCoins: List<String>, onRules: (List<AlarmRule>) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("Все") }
    val shown = when (filter) {
        "Spot" -> rules.filter { it.marketType == MarketType.SPOT }
        "Futures" -> rules.filter { it.marketType == MarketType.FUTURES }
        "Рост" -> rules.filter { it.direction == AlertDirection.RISE }
        "Падение" -> rules.filter { it.direction == AlertDirection.DROP }
        else -> rules
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { VTitle("Будильники", "Сигналы для Spot и Futures") }
            item {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Все", "Spot", "Futures", "Рост", "Падение").forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
            }
            if (shown.isEmpty()) item { Text("Нет будильников", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { rule ->
                Card(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = VCard), shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                AssistChip(onClick = {}, label = { Text(rule.marketType.label) })
                            }
                            Text(
                                "${if (rule.direction == AlertDirection.RISE) "↗ Рост" else "↘ Падение"} ${vFmt(rule.thresholdPercent)}% за ${rule.windowMinutes} мин",
                                color = if (rule.direction == AlertDirection.RISE) VGreen else VRed
                            )
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { on ->
                            onRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it })
                        })
                        TextButton(onClick = { onRules(rules.filterNot { it.id == rule.id }) }) { Text("×") }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = VBlue) {
            Text("＋", style = MaterialTheme.typography.headlineMedium)
        }
    }

    if (showCreate) {
        VCreateAlarm(
            spotCoins = spotCoins,
            futuresCoins = futuresCoins,
            onDismiss = { showCreate = false },
            onCreate = { onRules(rules + it); showCreate = false }
        )
    }
}

@Composable
private fun VCreateAlarm(
    spotCoins: List<String>, futuresCoins: List<String>, onDismiss: () -> Unit, onCreate: (AlarmRule) -> Unit
) {
    var market by remember { mutableStateOf(MarketType.SPOT) }
    var query by remember { mutableStateOf("BTC") }
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var direction by remember { mutableStateOf(AlertDirection.DROP) }
    var percent by remember { mutableStateOf("0.5") }
    var minutes by remember { mutableStateOf("5") }
    val coins = if (market == MarketType.SPOT) spotCoins else futuresCoins
    val matches = remember(query, coins) { coins.filter { it.contains(query, true) }.take(6) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый будильник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                MarketSwitch(market) { newMarket -> market = newMarket; query = "BTC"; symbol = "BTCUSDT" }
                OutlinedTextField(value = query, onValueChange = { query = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, label = { Text("Монета") }, singleLine = true)
                matches.forEach { coin ->
                    Text("$coin / USDT${if (symbol == "${coin}USDT") "  ✓" else ""}", Modifier.fillMaxWidth().clickable {
                        symbol = "${coin}USDT"; query = coin
                    }.padding(vertical = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == AlertDirection.DROP, onClick = { direction = AlertDirection.DROP }, label = { Text("📉 Падение") })
                    FilterChip(selected = direction == AlertDirection.RISE, onClick = { direction = AlertDirection.RISE }, label = { Text("📈 Рост") })
                }
                OutlinedTextField(value = percent, onValueChange = { percent = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } }, label = { Text("Изменение, %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("Период, минут") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = percent.toDoubleOrNull(); val m = minutes.toIntOrNull()
                if (p != null && p in 0.1..99.9 && m != null && m in 1..999 && symbol.removeSuffix("USDT") in coins) {
                    onCreate(AlarmRule(symbol = symbol, thresholdPercent = p, windowMinutes = m, direction = direction, marketType = market))
                }
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun VMarket(market: MarketType, coins: List<String>, onMarket: (MarketType) -> Unit, onChart: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    val preferred = remember(coins) {
        listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "PEPE", "SUI", "LINK", "AVAX").filter { it in coins }.ifEmpty { coins.take(12) }
    }

    LaunchedEffect(market, preferred) {
        tickers = emptyList()
        while (true) {
            tickers = MarketDataRepository.loadPopularTickers(preferred, market)
            delay(8_000)
        }
    }

    val shown = tickers.filter { it.symbol.removeSuffix("USDT").contains(search, true) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { VTitle("Рынок", if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M") }
        item { MarketSwitch(market, onMarket); Spacer(Modifier.height(8.dp)) }
        item {
            OutlinedTextField(value = search, onValueChange = { search = it.uppercase() }, label = { Text("Поиск монеты") }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        items(shown, key = { "${it.marketType}:${it.symbol}" }) { t ->
            val up = t.changePercent >= 0
            Row(Modifier.fillMaxWidth().clickable { onChart(t.symbol) }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold)
                    Text("${t.marketType.label} • ${t.symbol.removeSuffix("USDT")}/USDT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(vPrice(t.price), Modifier.width(120.dp))
                Text(String.format(Locale.US, "%+.2f%%", t.changePercent), color = if (up) VGreen else VRed, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .06f))
        }
    }
}

@Composable
private fun VChart(market: MarketType, symbol: String, coins: List<String>, onMarket: (MarketType) -> Unit, onSymbol: (String) -> Unit) {
    var timeframe by remember { mutableStateOf(vTimeframes[3]) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var ticker by remember { mutableStateOf<MarketTicker?>(null) }
    var choose by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(symbol.removeSuffix("USDT")) }

    LaunchedEffect(market, symbol, timeframe.api) {
        candles = emptyList()
        while (true) {
            candles = MarketDataRepository.loadCandles(symbol, timeframe.api, 100, market)
            ticker = MarketDataRepository.loadTicker(symbol, market)
            delay(7_000)
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { VTitle("График", if (market == MarketType.SPOT) "Spot график" else "Futures USDT-M график") }
        item { MarketSwitch(market, onMarket) }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { choose = true }, colors = CardDefaults.cardColors(containerColor = VCard), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${symbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Сменить ›", color = VBlue)
                }
            }
        }
        item {
            val t = ticker
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(t?.let { vPrice(it.price) } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                val ch = t?.changePercent ?: 0.0
                Text(if (t == null) "Загрузка…" else String.format(Locale.US, "%+.2f%% за 24ч", ch), color = if (ch >= 0) VGreen else VRed, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                vTimeframes.forEach { tf ->
                    FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.label) }, modifier = Modifier.weight(1f))
                }
            }
        }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A131F)), shape = RoundedCornerShape(18.dp)) {
                if (candles.isEmpty()) Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else VCandles(candles.takeLast(70), Modifier.fillMaxWidth().height(330.dp).padding(12.dp))
            }
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VStat("24ч максимум", t?.let { vPrice(it.high24h) } ?: "—", Modifier.weight(1f))
                VStat("24ч минимум", t?.let { vPrice(it.low24h) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VStat("Объём 24ч", t?.let { vCompact(it.quoteVolume24h) } ?: "—", Modifier.weight(1f))
                VStat("Изменение", t?.let { String.format(Locale.US, "%+.2f%%", it.changePercent) } ?: "—", Modifier.weight(1f))
            }
        }
    }

    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text("Выбрать монету • ${market.label}") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it.uppercase() }, label = { Text("Поиск") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    coins.filter { it.contains(query, true) }.take(8).forEach { coin ->
                        Text("$coin / USDT", Modifier.fillMaxWidth().clickable { onSymbol("${coin}USDT"); query = coin; choose = false }.padding(vertical = 8.dp))
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { choose = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun VCandles(candles: List<Candle>, modifier: Modifier) {
    val maxPrice = candles.maxOfOrNull { it.high } ?: 1.0
    val minPrice = candles.minOfOrNull { it.low } ?: 0.0
    val maxVol = candles.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
    val range = (maxPrice - minPrice).coerceAtLeast(maxPrice * .0001)
    Canvas(modifier) {
        val chartH = size.height * .78f
        val volumeTop = size.height * .82f
        val w = size.width / candles.size.coerceAtLeast(1)
        repeat(5) { i ->
            val y = chartH * i / 4f
            drawLine(Color.White.copy(alpha = .07f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        candles.forEachIndexed { i, c ->
            val x = w * i + w / 2f
            fun py(v: Double) = chartH - (((v - minPrice) / range).toFloat() * chartH)
            val color = if (c.close >= c.open) VGreen else VRed
            val oy = py(c.open); val cy = py(c.close); val hy = py(c.high); val ly = py(c.low)
            drawLine(color, Offset(x, hy), Offset(x, ly), 2f)
            val half = (w * .28f).coerceAtLeast(1.5f)
            val top = minOf(oy, cy); val bottom = maxOf(oy, cy)
            drawRect(color, Offset(x - half, top), androidx.compose.ui.geometry.Size(half * 2, (bottom - top).coerceAtLeast(2.5f)))
            val vh = (c.volume / maxVol).toFloat() * (size.height - volumeTop)
            drawRect(color.copy(alpha = .5f), Offset(x - half, size.height - vh), androidx.compose.ui.geometry.Size(half * 2, vh))
        }
    }
}

@Composable
private fun VStat(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = VCard), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { VTitle("Настройки", "Spot + Futures") }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = VCard)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Резервный REST-скан", fontWeight = FontWeight.Bold)
                    Text("Для Spot и Futures при потере Live-потока", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = seconds, onValueChange = { seconds = it.filter(Char::isDigit).take(5) }, modifier = Modifier.weight(1f), label = { Text("Секунд") }, singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { RuleStore.setScanIntervalSeconds(activity, (seconds.toIntOrNull() ?: 1).coerceAtLeast(1)) }) { Text("Сохранить") }
                    }
                }
            }
        }
        item {
            VAction("🔋", "Оптимизация батареи", "Разрешить работу в фоне") {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") })
                }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        item {
            VAction("🔇", "Остановить звук", "Заглушить активную тревогу") {
                activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE))
            }
        }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = VCard)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 0.7.0 • Spot + Futures USDT-M", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun vFmt(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
private fun vPrice(v: Double): String = when {
    v >= 1000 -> "$" + DecimalFormat("#,##0.00").format(v)
    v >= 1 -> "$" + DecimalFormat("0.00##").format(v)
    else -> "$" + DecimalFormat("0.000000##").format(v)
}
private fun vCompact(v: Double): String = when {
    v >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", v / 1_000_000_000)
    v >= 1_000_000 -> String.format(Locale.US, "$%.2fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "$%.2fK", v / 1_000)
    else -> vPrice(v)
}
