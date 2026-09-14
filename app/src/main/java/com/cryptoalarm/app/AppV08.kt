package com.cryptoalarm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val EBlue = Color(0xFF3B6CFF)
private val EGreen = Color(0xFF00D39A)
private val ERed = Color(0xFFFF5C6C)
private val ECard = Color(0xFF142030)
private val EBg = Color(0xFF07101A)
private val EChart = Color(0xFF0A131F)

private enum class ETab(val label: String, val icon: String) {
    HOME("Главная", "⌂"),
    ALARMS("Будильники", "◉"),
    MARKET("Рынок", "▥"),
    CHART("График", "⌁"),
    SETTINGS("Настройки", "⚙")
}

private data class ETimeframe(val label: String, val api: String)
private val eTimeframes = listOf(
    ETimeframe("1м", "1m"),
    ETimeframe("5м", "5m"),
    ETimeframe("15м", "15m"),
    ETimeframe("1ч", "1h"),
    ETimeframe("4ч", "4h"),
    ETimeframe("1д", "1d"),
    ETimeframe("1н", "1w")
)

@Composable
fun ComponentActivity.CryptoAlarmV08App() {
    val activity = this
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = EBlue,
            secondary = EGreen,
            background = EBg,
            surface = Color(0xFF0E1723),
            surfaceVariant = ECard
        )
    ) {
        var tab by remember { mutableStateOf(ETab.HOME) }
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
            containerColor = EBg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B1420)) {
                    ETab.entries.forEach { item ->
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
                    ETab.HOME -> EHome(
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
                        onAlarms = { tab = ETab.ALARMS },
                        onMarket = { tab = ETab.MARKET },
                        onChart = { tab = ETab.CHART }
                    )
                    ETab.ALARMS -> EAlarms(
                        rules = rules,
                        spotCoins = spotCoins,
                        futuresCoins = futuresCoins,
                        onRules = {
                            rules = it
                            RuleStore.save(activity, it)
                        }
                    )
                    ETab.MARKET -> EMarket(
                        market = market,
                        coins = if (market == MarketType.SPOT) spotCoins else futuresCoins,
                        onMarket = { market = it },
                        onChart = { picked -> symbol = picked; tab = ETab.CHART }
                    )
                    ETab.CHART -> EChartScreen(
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
                    ETab.SETTINGS -> ESettings(
                        activity = activity,
                        scan = RuleStore.getScanIntervalSeconds(activity)
                    )
                }
            }
        }
    }
}

@Composable
private fun ETitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EMarketSwitch(market: MarketType, onMarket: (MarketType) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = market == MarketType.SPOT,
            onClick = { onMarket(MarketType.SPOT) },
            label = { Text("Spot") }
        )
        FilterChip(
            selected = market == MarketType.FUTURES,
            onClick = { onMarket(MarketType.FUTURES) },
            label = { Text("Futures USDT-M") }
        )
    }
}

@Composable
private fun EHome(
    rules: List<AlarmRule>,
    monitoring: Boolean,
    onToggle: () -> Unit,
    onAlarms: () -> Unit,
    onMarket: () -> Unit,
    onChart: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ETitle("Crypto Alarm", "Spot + Futures под контролем") }
        item {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2A26))
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (monitoring) "●" else "○", color = if (monitoring) EGreen else Color.Gray, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (monitoring) "Live мониторинг активен" else "Мониторинг выключен", fontWeight = FontWeight.Bold)
                        Text("Отдельные потоки Spot и Futures", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("⚡", color = EGreen, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item {
            Button(
                onClick = onToggle,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (monitoring) "■ Остановить мониторинг" else "▶ Начать мониторинг", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EMetric("${rules.count { it.enabled }}", "Активных", Modifier.weight(1f), EBlue)
                EMetric("${rules.count { it.marketType == MarketType.SPOT }}", "Spot", Modifier.weight(1f), EGreen)
                EMetric("${rules.count { it.marketType == MarketType.FUTURES }}", "Futures", Modifier.weight(1f), Color(0xFFFFB34D))
            }
        }
        item { EAction("＋", "Создать будильник", "Spot или Futures", onAlarms) }
        item { EAction("▥", "Смотреть рынок", "Переключение Spot / Futures", onMarket) }
        item { EAction("⌁", "Открыть график", "Панорамирование, масштаб и свечи", onChart) }
    }
}

@Composable
private fun EMetric(value: String, label: String, modifier: Modifier, color: Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = ECard), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ECard)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = EBlue, style = MaterialTheme.typography.headlineSmall)
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
private fun EAlarms(
    rules: List<AlarmRule>,
    spotCoins: List<String>,
    futuresCoins: List<String>,
    onRules: (List<AlarmRule>) -> Unit
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
            item { ETitle("Будильники", "Сигналы для Spot и Futures") }
            item {
                Row(
                    Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf("Все", "Spot", "Futures", "Рост", "Падение").forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
            }
            if (shown.isEmpty()) item { Text("Нет будильников", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shown, key = { it.id }) { rule ->
                Card(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ECard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                AssistChip(onClick = {}, label = { Text(rule.marketType.label) })
                            }
                            Text(
                                "${if (rule.direction == AlertDirection.RISE) "↗ Рост" else "↘ Падение"} ${eFmt(rule.thresholdPercent)}% за ${rule.windowMinutes} мин",
                                color = if (rule.direction == AlertDirection.RISE) EGreen else ERed
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on -> onRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) }
                        )
                        TextButton(onClick = { onRules(rules.filterNot { it.id == rule.id }) }) { Text("×") }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = EBlue
        ) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
    }

    if (showCreate) {
        ECreateAlarm(
            spotCoins = spotCoins,
            futuresCoins = futuresCoins,
            onDismiss = { showCreate = false },
            onCreate = { onRules(rules + it); showCreate = false }
        )
    }
}

@Composable
private fun ECreateAlarm(
    spotCoins: List<String>,
    futuresCoins: List<String>,
    onDismiss: () -> Unit,
    onCreate: (AlarmRule) -> Unit
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
                EMarketSwitch(market) { newMarket -> market = newMarket; query = "BTC"; symbol = "BTCUSDT" }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) },
                    label = { Text("Монета") },
                    singleLine = true
                )
                matches.forEach { coin ->
                    Text(
                        "$coin / USDT${if (symbol == "${coin}USDT") "  ✓" else ""}",
                        Modifier.fillMaxWidth().clickable { symbol = "${coin}USDT"; query = coin }.padding(vertical = 4.dp)
                    )
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
                val p = percent.toDoubleOrNull()
                val m = minutes.toIntOrNull()
                if (p != null && p in 0.1..99.9 && m != null && m in 1..999 && symbol.removeSuffix("USDT") in coins) {
                    onCreate(AlarmRule(symbol = symbol, thresholdPercent = p, windowMinutes = m, direction = direction, marketType = market))
                }
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EMarket(
    market: MarketType,
    coins: List<String>,
    onMarket: (MarketType) -> Unit,
    onChart: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    val preferred = remember(coins) {
        listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "PEPE", "SUI", "LINK", "AVAX")
            .filter { it in coins }
            .ifEmpty { coins.take(12) }
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
        item { ETitle("Рынок", if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M") }
        item { EMarketSwitch(market, onMarket); Spacer(Modifier.height(8.dp)) }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it.uppercase() },
                label = { Text("Поиск монеты") },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        items(shown, key = { "${it.marketType}:${it.symbol}" }) { t ->
            val up = t.changePercent >= 0
            Row(
                Modifier.fillMaxWidth().clickable { onChart(t.symbol) }.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold)
                    Text("${t.marketType.label} • ${t.symbol.removeSuffix("USDT")}/USDT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(ePrice(t.price), Modifier.width(120.dp))
                Text(String.format(Locale.US, "%+.2f%%", t.changePercent), color = if (up) EGreen else ERed, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .06f))
        }
    }
}

@Composable
private fun EChartScreen(
    market: MarketType,
    symbol: String,
    coins: List<String>,
    onMarket: (MarketType) -> Unit,
    onSymbol: (String) -> Unit
) {
    var timeframe by remember { mutableStateOf(eTimeframes[3]) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var ticker by remember { mutableStateOf<MarketTicker?>(null) }
    var choose by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(symbol.removeSuffix("USDT")) }

    LaunchedEffect(market, symbol, timeframe.api) {
        candles = emptyList()
        while (true) {
            candles = MarketDataRepository.loadCandles(symbol, timeframe.api, 500, market)
            ticker = MarketDataRepository.loadTicker(symbol, market)
            delay(5_000)
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { ETitle("График", if (market == MarketType.SPOT) "Интерактивный Spot график" else "Интерактивный Futures USDT-M график") }
        item { EMarketSwitch(market, onMarket) }
        item {
            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { choose = true },
                colors = CardDefaults.cardColors(containerColor = ECard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${symbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Сменить ›", color = EBlue)
                }
            }
        }
        item {
            val t = ticker
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text(t?.let { ePrice(it.price) } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                val ch = t?.changePercent ?: 0.0
                Text(
                    if (t == null) "Загрузка…" else String.format(Locale.US, "%+.2f%% за 24ч", ch),
                    color = if (ch >= 0) EGreen else ERed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                eTimeframes.forEach { tf ->
                    FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.label) })
                }
            }
        }
        item {
            if (candles.isEmpty()) {
                Card(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = EChart),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                EInteractiveChartPanel(candles = candles)
            }
        }
        item {
            Text(
                "Жесты: тяните график влево/вправо • двумя пальцами меняйте масштаб • нажмите на свечу для деталей • двойное нажатие возвращает к последней цене",
                modifier = Modifier.padding(horizontal = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EStat("24ч максимум", t?.let { ePrice(it.high24h) } ?: "—", Modifier.weight(1f))
                EStat("24ч минимум", t?.let { ePrice(it.low24h) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            val t = ticker
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EStat("Объём 24ч", t?.let { eCompact(it.quoteVolume24h) } ?: "—", Modifier.weight(1f))
                EStat("Изменение", t?.let { String.format(Locale.US, "%+.2f%%", it.changePercent) } ?: "—", Modifier.weight(1f))
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
                        Text(
                            "$coin / USDT",
                            Modifier.fillMaxWidth().clickable { onSymbol("${coin}USDT"); query = coin; choose = false }.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choose = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun EInteractiveChartPanel(candles: List<Candle>) {
    val total = candles.size
    val minVisible = minOf(15, total).coerceAtLeast(1)
    val maxVisible = minOf(220, total).coerceAtLeast(minVisible)
    var visibleCount by remember(total) { mutableStateOf(minOf(70, total).coerceAtLeast(1)) }
    var endIndex by remember(total) { mutableStateOf(total) }
    var selectedIndex by remember(total) { mutableStateOf<Int?>(null) }

    fun clampState() {
        visibleCount = visibleCount.coerceIn(minVisible, maxVisible)
        endIndex = endIndex.coerceIn(visibleCount, total)
        if (selectedIndex != null && selectedIndex !in 0 until total) selectedIndex = null
    }

    clampState()
    val startIndex = (endIndex - visibleCount).coerceAtLeast(0)
    val visible = candles.subList(startIndex, endIndex)
    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val selectedVisibleIndex = selectedIndex?.minus(startIndex)?.takeIf { it in visible.indices }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0

    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = EChart),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    visibleCount = (visibleCount + 10).coerceAtMost(maxVisible)
                    endIndex = endIndex.coerceAtLeast(visibleCount)
                    selectedIndex = null
                }) { Text("−") }
                Text("$visibleCount свечей", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    endIndex = total
                    selectedIndex = null
                }) { Text("К последней") }
                TextButton(onClick = {
                    visibleCount = (visibleCount - 10).coerceAtLeast(minVisible)
                    endIndex = endIndex.coerceAtLeast(visibleCount)
                    selectedIndex = null
                }) { Text("+") }
            }

            Box(Modifier.fillMaxWidth().height(390.dp)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(total) {
                            var panAccumulator = 0f
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newVisible = (visibleCount / zoom).roundToInt().coerceIn(minVisible, maxVisible)
                                if (newVisible != visibleCount) {
                                    visibleCount = newVisible
                                    endIndex = endIndex.coerceIn(visibleCount, total)
                                    selectedIndex = null
                                }
                                val pxPerCandle = size.width.toFloat() / visibleCount.coerceAtLeast(1)
                                panAccumulator += pan.x
                                val steps = (panAccumulator / pxPerCandle.coerceAtLeast(1f)).toInt()
                                if (steps != 0) {
                                    endIndex = (endIndex - steps).coerceIn(visibleCount, total)
                                    panAccumulator -= steps * pxPerCandle
                                    selectedIndex = null
                                }
                            }
                        }
                        .pointerInput(total) {
                            detectTapGestures(
                                onDoubleTap = {
                                    endIndex = total
                                    selectedIndex = null
                                },
                                onTap = { pos ->
                                    val currentStart = (endIndex - visibleCount).coerceAtLeast(0)
                                    val localIndex = ((pos.x / size.width.toFloat()) * visibleCount)
                                        .toInt()
                                        .coerceIn(0, visibleCount - 1)
                                    selectedIndex = (currentStart + localIndex).coerceIn(0, total - 1)
                                }
                            )
                        }
                ) {
                    val chartHeight = size.height * .78f
                    val volumeTop = size.height * .82f
                    val maxVol = visible.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
                    val range = (maxPrice - minPrice).coerceAtLeast(maxPrice * .0001)
                    val candleWidth = size.width / visible.size.coerceAtLeast(1)

                    repeat(5) { i ->
                        val y = chartHeight * i / 4f
                        drawLine(Color.White.copy(alpha = .07f), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    repeat(5) { i ->
                        val x = size.width * i / 4f
                        drawLine(Color.White.copy(alpha = .04f), Offset(x, 0f), Offset(x, chartHeight), 1f)
                    }

                    fun py(value: Double): Float = chartHeight - (((value - minPrice) / range).toFloat() * chartHeight)

                    visible.forEachIndexed { i, c ->
                        val x = candleWidth * i + candleWidth / 2f
                        val color = if (c.close >= c.open) EGreen else ERed
                        val openY = py(c.open)
                        val closeY = py(c.close)
                        val highY = py(c.high)
                        val lowY = py(c.low)
                        drawLine(color, Offset(x, highY), Offset(x, lowY), 2f)
                        val half = (candleWidth * .28f).coerceIn(1.4f, 8f)
                        val top = minOf(openY, closeY)
                        val bottom = maxOf(openY, closeY)
                        drawRect(
                            color,
                            Offset(x - half, top),
                            androidx.compose.ui.geometry.Size(half * 2f, (bottom - top).coerceAtLeast(2.5f))
                        )
                        val volumeHeight = (c.volume / maxVol).toFloat() * (size.height - volumeTop)
                        drawRect(
                            color.copy(alpha = .5f),
                            Offset(x - half, size.height - volumeHeight),
                            androidx.compose.ui.geometry.Size(half * 2f, volumeHeight)
                        )
                    }

                    visible.lastOrNull()?.let { last ->
                        val y = py(last.close)
                        drawLine(
                            color = if (last.close >= last.open) EGreen.copy(alpha = .7f) else ERed.copy(alpha = .7f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                    }

                    selectedVisibleIndex?.let { selectedLocal ->
                        val c = visible[selectedLocal]
                        val x = candleWidth * selectedLocal + candleWidth / 2f
                        val y = py(c.close)
                        drawLine(Color.White.copy(alpha = .7f), Offset(x, 0f), Offset(x, chartHeight), 1.5f)
                        drawLine(
                            Color.White.copy(alpha = .45f),
                            Offset(0f, y),
                            Offset(size.width, y),
                            1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        )
                    }
                }

                Text(
                    ePrice(maxPrice),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = .65f)
                )
                Text(
                    ePrice(minPrice),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 74.dp),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = .65f)
                )
            }

            if (selected != null) {
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(selected.openTime)),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ECandleValue("O", ePrice(selected.open), Modifier.weight(1f))
                        ECandleValue("H", ePrice(selected.high), Modifier.weight(1f))
                        ECandleValue("L", ePrice(selected.low), Modifier.weight(1f))
                        ECandleValue("C", ePrice(selected.close), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Объём: ${eCompact(selected.volume)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ECandleValue(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun EStat(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = ECard), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ESettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ETitle("Настройки", "Spot + Futures") }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ECard)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Резервный REST-скан", fontWeight = FontWeight.Bold)
                    Text("Для Spot и Futures при потере Live-потока", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Секунд") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { RuleStore.setScanIntervalSeconds(activity, (seconds.toIntOrNull() ?: 1).coerceAtLeast(1)) }) { Text("Сохранить") }
                    }
                }
            }
        }
        item {
            EAction("🔋", "Оптимизация батареи", "Разрешить работу в фоне") {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") })
                }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        item {
            EAction("🔇", "Остановить звук", "Заглушить активную тревогу") {
                activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE))
            }
        }
        item {
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ECard)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 0.8.0 • интерактивный график", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun eFmt(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

private fun ePrice(v: Double): String = when {
    v >= 1000 -> "$" + DecimalFormat("#,##0.00").format(v)
    v >= 1 -> "$" + DecimalFormat("0.00##").format(v)
    else -> "$" + DecimalFormat("0.000000##").format(v)
}

private fun eCompact(v: Double): String = when {
    v >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", v / 1_000_000_000)
    v >= 1_000_000 -> String.format(Locale.US, "$%.2fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "$%.2fK", v / 1_000)
    else -> String.format(Locale.US, "%.4f", v)
}
