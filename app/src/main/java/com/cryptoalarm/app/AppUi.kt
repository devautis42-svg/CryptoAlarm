package com.cryptoalarm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.util.Locale

private val AccentBlue = Color(0xFF3B6CFF)
private val AccentGreen = Color(0xFF00D39A)
private val AccentRed = Color(0xFFFF5C6C)
private val SurfaceDark = Color(0xFF111925)
private val SurfaceDark2 = Color(0xFF172233)

private enum class AppTab(val label: String, val icon: String) {
    HOME("Главная", "⌂"),
    ALARMS("Будильники", "◉"),
    MARKET("Рынок", "▥"),
    CHART("График", "⌁"),
    SETTINGS("Настройки", "⚙")
}

private data class Timeframe(val label: String, val api: String)

private val chartTimeframes = listOf(
    Timeframe("1м", "1m"),
    Timeframe("5м", "5m"),
    Timeframe("15м", "15m"),
    Timeframe("1ч", "1h"),
    Timeframe("4ч", "4h"),
    Timeframe("1д", "1d"),
    Timeframe("1н", "1w")
)

@Composable
fun ComponentActivity.CryptoAlarmV06App() {
    val colors = darkColorScheme(
        primary = AccentBlue,
        secondary = AccentGreen,
        background = Color(0xFF07101A),
        surface = SurfaceDark,
        surfaceVariant = SurfaceDark2
    )

    MaterialTheme(colorScheme = colors) {
        var tab by remember { mutableStateOf(AppTab.HOME) }
        var rules by remember { mutableStateOf(RuleStore.load(this)) }
        var monitoring by remember { mutableStateOf(RuleStore.isMonitoring(this)) }
        var selectedSymbol by remember { mutableStateOf("BTCUSDT") }
        var coins by remember { mutableStateOf(CoinRepository.fallback()) }

        LaunchedEffect(Unit) { coins = CoinRepository.loadUsdtSymbols() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B1420)) {
                    AppTab.entries.forEach { item ->
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
                    AppTab.HOME -> HomeScreen(
                        rules = rules,
                        monitoring = monitoring,
                        onToggleMonitoring = {
                            if (monitoring) {
                                startService(Intent(this@CryptoAlarmV06App, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
                                monitoring = false
                            } else if (rules.any { it.enabled }) {
                                ContextCompat.startForegroundService(
                                    this@CryptoAlarmV06App,
                                    Intent(this@CryptoAlarmV06App, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START)
                                )
                                monitoring = true
                            }
                        },
                        onOpenAlarms = { tab = AppTab.ALARMS },
                        onOpenMarket = { tab = AppTab.MARKET },
                        onOpenChart = { tab = AppTab.CHART }
                    )
                    AppTab.ALARMS -> AlarmsScreen(
                        rules = rules,
                        coins = coins,
                        onRulesChange = { newRules ->
                            rules = newRules
                            RuleStore.save(this@CryptoAlarmV06App, newRules)
                        }
                    )
                    AppTab.MARKET -> MarketScreen(
                        coins = coins,
                        onOpenChart = { symbol ->
                            selectedSymbol = symbol
                            tab = AppTab.CHART
                        }
                    )
                    AppTab.CHART -> ChartScreen(
                        coins = coins,
                        selectedSymbol = selectedSymbol,
                        onSymbolChange = { selectedSymbol = it }
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        scanSeconds = RuleStore.getScanIntervalSeconds(this).toString(),
                        onSaveScan = { RuleStore.setScanIntervalSeconds(this, it) },
                        onBatterySettings = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }.onFailure {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        onSilence = {
                            startService(Intent(this, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeScreen(
    rules: List<AlarmRule>,
    monitoring: Boolean,
    onToggleMonitoring: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenMarket: () -> Unit,
    onOpenChart: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ScreenTitle("Crypto Alarm", "Ваш рынок всегда под контролем") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2A26)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (monitoring) "●" else "○", color = if (monitoring) AccentGreen else Color.Gray, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (monitoring) "Live мониторинг активен" else "Мониторинг выключен", fontWeight = FontWeight.Bold)
                        Text(if (monitoring) "Binance WebSocket • мгновенные события" else "Включите, чтобы получать тревоги", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("▮▮▮", color = AccentGreen)
                }
            }
        }
        item {
            Button(
                onClick = onToggleMonitoring,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (monitoring) "■ Остановить мониторинг" else "▶ Начать мониторинг", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("${rules.count { it.enabled }}", "Активных\nбудильников", Modifier.weight(1f), AccentBlue)
                MetricCard("${rules.count { it.direction == AlertDirection.DROP }}", "На\nпадение", Modifier.weight(1f), AccentRed)
                MetricCard("Live", "WebSocket\nрежим", Modifier.weight(1f), AccentGreen)
            }
        }
        item { SectionHeader("Быстрые действия") }
        item { ActionCard("＋", "Создать будильник", "Настроить новый сигнал", onOpenAlarms) }
        item { ActionCard("⌕", "Смотреть рынок", "Актуальные цены монет", onOpenMarket) }
        item { ActionCard("⌁", "Открыть график", "Свечи, объём и таймфреймы", onOpenChart) }
    }
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier, accent: Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark2)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ActionCard(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = AccentBlue.copy(alpha = .22f)) {
                Text(icon, modifier = Modifier.padding(12.dp), color = AccentBlue, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun AlarmsScreen(
    rules: List<AlarmRule>,
    coins: List<String>,
    onRulesChange: (List<AlarmRule>) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("Все") }
    val filtered = when (filter) {
        "Рост" -> rules.filter { it.direction == AlertDirection.RISE }
        "Падение" -> rules.filter { it.direction == AlertDirection.DROP }
        else -> rules
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScreenTitle("Будильники", "Ваши крипто-сигналы") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Все", "Рост", "Падение").forEach { item ->
                        FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) })
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text("Будильников пока нет", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(filtered, key = { it.id }) { rule ->
                AlarmCard(
                    rule = rule,
                    onToggle = { enabled -> onRulesChange(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) },
                    onDelete = { onRulesChange(rules.filterNot { it.id == rule.id }) }
                )
            }
        }
        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = AccentBlue
        ) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
    }

    if (showCreate) {
        CreateAlarmDialog(
            coins = coins,
            onDismiss = { showCreate = false },
            onCreate = { rule -> onRulesChange(rules + rule); showCreate = false }
        )
    }
}

@Composable
private fun AlarmCard(rule: AlarmRule, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val up = rule.direction == AlertDirection.RISE
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${rule.symbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (up) "↗ Рост" else "↘ Падение"} ≥ ${fmt(rule.thresholdPercent)}% за ${rule.windowMinutes} мин",
                    color = if (up) AccentGreen else AccentRed
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onDelete) { Text("×") }
        }
    }
}

@Composable
private fun CreateAlarmDialog(coins: List<String>, onDismiss: () -> Unit, onCreate: (AlarmRule) -> Unit) {
    var query by remember { mutableStateOf("BTC") }
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var direction by remember { mutableStateOf(AlertDirection.DROP) }
    var percent by remember { mutableStateOf("0.5") }
    var minutes by remember { mutableStateOf("5") }
    val matches = remember(query, coins) { coins.filter { it.contains(query, ignoreCase = true) }.take(6) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый будильник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase().filter(Char::isLetterOrDigit).take(12) },
                    label = { Text("Монета") },
                    singleLine = true
                )
                if (query.isNotBlank()) {
                    matches.forEach { coin ->
                        Text(
                            "$coin / USDT${if (symbol == "${coin}USDT") "  ✓" else ""}",
                            modifier = Modifier.fillMaxWidth().clickable { symbol = "${coin}USDT"; query = coin }.padding(vertical = 5.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == AlertDirection.DROP, onClick = { direction = AlertDirection.DROP }, label = { Text("📉 Падение") })
                    FilterChip(selected = direction == AlertDirection.RISE, onClick = { direction = AlertDirection.RISE }, label = { Text("📈 Рост") })
                }
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Изменение, %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Период, минут") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pct = percent.toDoubleOrNull()
                val mins = minutes.toIntOrNull()
                if (pct != null && pct in 0.1..99.9 && mins != null && mins in 1..999) {
                    onCreate(AlarmRule(symbol = symbol, thresholdPercent = pct, windowMinutes = mins, direction = direction))
                }
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MarketScreen(coins: List<String>, onOpenChart: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    val popular = remember(coins) {
        val preferred = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "PEPE", "SUI", "LINK", "AVAX")
        preferred.filter { it in coins }.ifEmpty { coins.take(12) }
    }

    LaunchedEffect(popular) {
        while (true) {
            tickers = MarketDataRepository.loadPopularTickers(popular)
            delay(10_000)
        }
    }

    val shown = tickers.filter { it.symbol.removeSuffix("USDT").contains(search, ignoreCase = true) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Рынок", "Актуальные цены в реальном времени") }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.uppercase() },
                label = { Text("Поиск монеты") },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
        }
        if (shown.isEmpty()) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        items(shown, key = { it.symbol }) { ticker ->
            MarketRow(ticker = ticker, onClick = { onOpenChart(ticker.symbol) })
        }
    }
}

@Composable
private fun MarketRow(ticker: MarketTicker, onClick: () -> Unit) {
    val up = ticker.changePercent >= 0
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(ticker.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold)
            Text("${ticker.symbol.removeSuffix("USDT")}/USDT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(priceFmt(ticker.price), modifier = Modifier.width(120.dp))
        Text(String.format(Locale.US, "%+.2f%%", ticker.changePercent), color = if (up) AccentGreen else AccentRed, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = Color.White.copy(alpha = .06f))
}

@Composable
private fun ChartScreen(coins: List<String>, selectedSymbol: String, onSymbolChange: (String) -> Unit) {
    var timeframe by remember { mutableStateOf(chartTimeframes[3]) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var ticker by remember { mutableStateOf<MarketTicker?>(null) }
    var chooseCoin by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(selectedSymbol.removeSuffix("USDT")) }

    LaunchedEffect(selectedSymbol, timeframe.api) {
        candles = emptyList()
        candles = MarketDataRepository.loadCandles(selectedSymbol, timeframe.api, 100)
        ticker = MarketDataRepository.loadTicker(selectedSymbol)
    }

    LaunchedEffect(selectedSymbol, timeframe.api) {
        while (true) {
            delay(8_000)
            candles = MarketDataRepository.loadCandles(selectedSymbol, timeframe.api, 100)
            ticker = MarketDataRepository.loadTicker(selectedSymbol)
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("График", "Анализ движения цены") }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { chooseCoin = true },
                colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◉", color = AccentBlue, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${selectedSymbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold)
                        Text("Binance Spot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Сменить  ›", color = AccentBlue)
                }
            }
        }
        item {
            val t = ticker
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(t?.let { priceFmt(it.price) } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                val change = t?.changePercent ?: 0.0
                Text(
                    if (t == null) "Загрузка…" else String.format(Locale.US, "%+.2f%% за 24ч", change),
                    color = if (change >= 0) AccentGreen else AccentRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                chartTimeframes.forEach { tf ->
                    FilterChip(
                        selected = timeframe == tf,
                        onClick = { timeframe = tf },
                        label = { Text(tf.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A131F))
            ) {
                if (candles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    CandlestickChart(candles = candles, modifier = Modifier.fillMaxWidth().height(330.dp).padding(12.dp))
                }
            }
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("24ч максимум", t?.let { priceFmt(it.high24h) } ?: "—", Modifier.weight(1f))
                StatCard("24ч минимум", t?.let { priceFmt(it.low24h) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Объём (24ч)", t?.let { compactNumber(it.quoteVolume24h) } ?: "—", Modifier.weight(1f))
                StatCard("Изменение (24ч)", t?.let { String.format(Locale.US, "%+.2f%%", it.changePercent) } ?: "—", Modifier.weight(1f))
            }
        }
    }

    if (chooseCoin) {
        AlertDialog(
            onDismissRequest = { chooseCoin = false },
            title = { Text("Выбрать монету") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it.uppercase() }, label = { Text("Поиск") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    coins.filter { it.contains(query, ignoreCase = true) }.take(8).forEach { coin ->
                        Text("$coin / USDT", modifier = Modifier.fillMaxWidth().clickable {
                            onSymbolChange("${coin}USDT")
                            query = coin
                            chooseCoin = false
                        }.padding(vertical = 8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseCoin = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun CandlestickChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    val visible = candles.takeLast(70)
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val maxVol = visible.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
    val priceRange = (maxPrice - minPrice).coerceAtLeast(maxPrice * 0.0001)

    Canvas(modifier = modifier) {
        val chartHeight = size.height * 0.78f
        val volumeTop = size.height * 0.82f
        val candleWidth = size.width / visible.size.coerceAtLeast(1)

        for (i in 0..4) {
            val y = chartHeight * i / 4f
            drawLine(Color.White.copy(alpha = .07f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        for (i in 0..4) {
            val x = size.width * i / 4f
            drawLine(Color.White.copy(alpha = .04f), Offset(x, 0f), Offset(x, chartHeight), 1f)
        }

        visible.forEachIndexed { index, c ->
            val centerX = candleWidth * index + candleWidth / 2f
            fun py(value: Double): Float = chartHeight - (((value - minPrice) / priceRange).toFloat() * chartHeight)
            val color = if (c.close >= c.open) AccentGreen else AccentRed
            val openY = py(c.open)
            val closeY = py(c.close)
            val highY = py(c.high)
            val lowY = py(c.low)
            drawLine(color, Offset(centerX, highY), Offset(centerX, lowY), 2f)
            val bodyTop = minOf(openY, closeY)
            val bodyBottom = maxOf(openY, closeY)
            val half = (candleWidth * .28f).coerceAtLeast(1.5f)
            drawRect(
                color = color,
                topLeft = Offset(centerX - half, bodyTop),
                size = androidx.compose.ui.geometry.Size(half * 2, (bodyBottom - bodyTop).coerceAtLeast(2.5f))
            )
            val volHeight = ((c.volume / maxVol).toFloat() * (size.height - volumeTop))
            drawRect(
                color = color.copy(alpha = .55f),
                topLeft = Offset(centerX - half, size.height - volHeight),
                size = androidx.compose.ui.geometry.Size(half * 2, volHeight)
            )
        }

        val last = visible.lastOrNull()
        if (last != null) {
            val y = chartHeight - (((last.close - minPrice) / priceRange).toFloat() * chartHeight)
            drawLine(AccentGreen.copy(alpha = .65f), Offset(0f, y), Offset(size.width, y), 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceDark2), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SettingsScreen(
    scanSeconds: String,
    onSaveScan: (Int) -> Unit,
    onBatterySettings: () -> Unit,
    onSilence: () -> Unit
) {
    var scan by remember { mutableStateOf(scanSeconds) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Настройки", "Управляйте приложением под себя") }
        item { SectionHeader("Мониторинг") }
        item {
            SettingsCard {
                Text("Резервный REST-скан", fontWeight = FontWeight.SemiBold)
                Text("Используется только если Live WebSocket недоступен", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = scan,
                        onValueChange = { scan = it.filter(Char::isDigit).take(5) },
                        label = { Text("Секунд") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { onSaveScan((scan.toIntOrNull() ?: 1).coerceAtLeast(1)) }) { Text("Сохранить") }
                }
            }
        }
        item { SectionHeader("Система") }
        item { SettingsAction("🔋", "Оптимизация батареи", "Разрешить приложению работать без ограничений", onBatterySettings) }
        item { SettingsAction("🔇", "Остановить звук тревоги", "Сразу заглушить активный сигнал", onSilence) }
        item { SectionHeader("О приложении") }
        item {
            SettingsCard {
                Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                Text("Версия 0.6.0 • Live WebSocket + графики", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        shape = RoundedCornerShape(16.dp)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun SettingsAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›")
        }
    }
}

private fun fmt(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

private fun priceFmt(v: Double): String = when {
    v >= 1000 -> "$" + DecimalFormat("#,##0.00").format(v)
    v >= 1 -> "$" + DecimalFormat("0.00##").format(v)
    else -> "$" + DecimalFormat("0.000000##").format(v)
}

private fun compactNumber(v: Double): String = when {
    v >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", v / 1_000_000_000)
    v >= 1_000_000 -> String.format(Locale.US, "$%.2fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "$%.2fK", v / 1_000)
    else -> priceFmt(v)
}
