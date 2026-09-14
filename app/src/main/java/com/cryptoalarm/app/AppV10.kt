package com.cryptoalarm.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val XBlue = Color(0xFF4E6DFF)
private val XBlueSoft = Color(0xFF293B7D)
private val XGreen = Color(0xFF19D7A0)
private val XRed = Color(0xFFFF5B6B)
private val XBg = Color(0xFF07111D)
private val XSurface = Color(0xFF101C2C)
private val XSurface2 = Color(0xFF0B1624)
private val XBorder = Color(0xFF314058)
private val XMuted = Color(0xFF9AA6BC)
private val XGrid = Color(0xFF26364B)

private enum class XTab(val label: String, val icon: String) {
    HOME("Главная", "⌂"),
    ALARMS("Будильники", "◉"),
    MARKET("Рынок", "▥"),
    CHART("График", "⌁"),
    SETTINGS("Настройки", "⚙")
}

private data class XTimeframe(val label: String, val api: String)
private val xTimeframes = listOf(
    XTimeframe("1м", "1m"), XTimeframe("5м", "5m"), XTimeframe("15м", "15m"),
    XTimeframe("1ч", "1h"), XTimeframe("4ч", "4h"), XTimeframe("1д", "1d"), XTimeframe("1н", "1w")
)

private data class XRulerPoint(val candleIndex: Int, val price: Double)

@Composable
fun ComponentActivity.CryptoAlarmV10App() {
    val activity = this
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = XBlue,
            secondary = XGreen,
            background = XBg,
            surface = XSurface,
            surfaceVariant = XSurface2,
            onBackground = Color(0xFFF1F3FA),
            onSurface = Color(0xFFF1F3FA),
            onSurfaceVariant = XMuted
        )
    ) {
        var tab by remember { mutableStateOf(XTab.HOME) }
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
            containerColor = XBg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0A1421), tonalElevation = 0.dp) {
                    XTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                indicatorColor = Color(0xFF434C73),
                                unselectedIconColor = XMuted,
                                unselectedTextColor = XMuted
                            ),
                            icon = { Text(item.icon, fontSize = 24.sp) },
                            label = { Text(item.label, maxLines = 1, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    XTab.HOME -> XHome(
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
                        onAlarms = { tab = XTab.ALARMS },
                        onMarket = { tab = XTab.MARKET },
                        onChart = { tab = XTab.CHART }
                    )
                    XTab.ALARMS -> XAlarms(
                        rules = rules,
                        spotCoins = spotCoins,
                        futuresCoins = futuresCoins,
                        onRules = {
                            rules = it
                            RuleStore.save(activity, it)
                        }
                    )
                    XTab.MARKET -> XMarket(
                        market = market,
                        coins = if (market == MarketType.SPOT) spotCoins else futuresCoins,
                        onMarket = { market = it },
                        onChart = { picked -> symbol = picked; tab = XTab.CHART }
                    )
                    XTab.CHART -> XChartScreen(
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
                    XTab.SETTINGS -> XSettings(activity, RuleStore.getScanIntervalSeconds(activity))
                }
            }
        }
    }
}

@Composable
private fun XHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 17.sp, color = XMuted)
    }
}

@Composable
private fun XMarketSegment(market: MarketType, onMarket: (MarketType) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, XBorder)
    ) {
        Row(Modifier.padding(2.dp)) {
            XSegmentButton("Spot", market == MarketType.SPOT, Modifier.weight(1f)) { onMarket(MarketType.SPOT) }
            Spacer(Modifier.width(2.dp))
            XSegmentButton("Futures USDT-M", market == MarketType.FUTURES, Modifier.weight(1.65f)) { onMarket(MarketType.FUTURES) }
        }
    }
}

@Composable
private fun XSegmentButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(50.dp).clickable(onClick = onClick),
        color = if (selected) Color(0xFF4B4E70) else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Color(0xFFC5CBDB), fontSize = 15.sp)
        }
    }
}

@Composable
private fun XHome(
    rules: List<AlarmRule>, monitoring: Boolean, onToggle: () -> Unit,
    onAlarms: () -> Unit, onMarket: () -> Unit, onChart: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { XHeader("Crypto Alarm", "Spot + Futures под контролем") }
        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                color = Color(0xFF0D2926), shape = RoundedCornerShape(20.dp)
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (monitoring) "●" else "○", color = if (monitoring) XGreen else XMuted, fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (monitoring) "Live мониторинг активен" else "Мониторинг выключен", fontWeight = FontWeight.Bold)
                        Text("Отдельные потоки Spot и Futures", color = XMuted, fontSize = 12.sp)
                    }
                    Text("⚡", color = XGreen, fontSize = 25.sp)
                }
            }
        }
        item {
            Button(
                onClick = onToggle,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(17.dp)
            ) { Text(if (monitoring) "■ Остановить мониторинг" else "▶ Начать мониторинг", fontWeight = FontWeight.Bold) }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                XMetric("${rules.count { it.enabled }}", "Активных", Modifier.weight(1f), XBlue)
                XMetric("${rules.count { it.marketType == MarketType.SPOT }}", "Spot", Modifier.weight(1f), XGreen)
                XMetric("${rules.count { it.marketType == MarketType.FUTURES }}", "Futures", Modifier.weight(1f), Color(0xFFFFB454))
            }
        }
        item { XAction("＋", "Создать будильник", "Spot или Futures", onAlarms) }
        item { XAction("▥", "Смотреть рынок", "Цены и изменение за 24ч", onMarket) }
        item { XAction("⌁", "Открыть график", "Свечи, жесты и линейка", onChart) }
    }
}

@Composable
private fun XMetric(value: String, label: String, modifier: Modifier, color: Color) {
    Surface(modifier, color = XSurface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, color = XMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun XAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick),
        color = XSurface, shape = RoundedCornerShape(17.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = XBlue, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = XMuted, fontSize = 12.sp)
            }
            Text("›", color = XMuted, fontSize = 22.sp)
        }
    }
}

@Composable
private fun XAlarms(
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
        LazyColumn(contentPadding = PaddingValues(bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { XHeader("Будильники", "Сигналы Spot и Futures") }
            item {
                Row(Modifier.padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Все", "Spot", "Futures", "Рост", "Падение").forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                    }
                }
            }
            if (shown.isEmpty()) item { Text("Нет будильников", Modifier.padding(18.dp), color = XMuted) }
            items(shown, key = { it.id }) { rule ->
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = XSurface, shape = RoundedCornerShape(17.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                AssistChip(onClick = {}, label = { Text(rule.marketType.label) })
                            }
                            Text(
                                "${if (rule.direction == AlertDirection.RISE) "↗ Рост" else "↘ Падение"} ${xFmt(rule.thresholdPercent)}% за ${rule.windowMinutes} мин",
                                color = if (rule.direction == AlertDirection.RISE) XGreen else XRed
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
        FloatingActionButton(
            onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = XBlue
        ) { Text("＋", fontSize = 28.sp) }
    }

    if (showCreate) {
        XCreateAlarm(spotCoins, futuresCoins, { showCreate = false }) {
            onRules(rules + it)
            showCreate = false
        }
    }
}

@Composable
private fun XCreateAlarm(
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
                XMarketSegment(market, { newMarket -> market = newMarket; query = "BTC"; symbol = "BTCUSDT" }, Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) },
                    label = { Text("Монета") }, singleLine = true
                )
                matches.forEach { coin ->
                    Text("$coin / USDT${if (symbol == "${coin}USDT") "  ✓" else ""}", Modifier.fillMaxWidth().clickable {
                        symbol = "${coin}USDT"; query = coin
                    }.padding(vertical = 4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == AlertDirection.DROP, onClick = { direction = AlertDirection.DROP }, label = { Text("📉 Падение") })
                    FilterChip(selected = direction == AlertDirection.RISE, onClick = { direction = AlertDirection.RISE }, label = { Text("📈 Рост") })
                }
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Изменение, %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Период, минут") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
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
private fun XMarket(market: MarketType, coins: List<String>, onMarket: (MarketType) -> Unit, onChart: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    val preferred = remember(coins) {
        listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "PEPE", "SUI", "LINK", "AVAX")
            .filter { it in coins }.ifEmpty { coins.take(12) }
    }

    LaunchedEffect(market, preferred) {
        tickers = emptyList()
        while (true) {
            tickers = MarketDataRepository.loadPopularTickers(preferred, market)
            delay(8_000)
        }
    }

    val shown = tickers.filter { it.symbol.removeSuffix("USDT").contains(search, true) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item { XHeader("Рынок", if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M") }
        item { XMarketSegment(market, onMarket, Modifier.padding(horizontal = 16.dp).fillMaxWidth()); Spacer(Modifier.height(10.dp)) }
        item {
            OutlinedTextField(
                value = search, onValueChange = { search = it.uppercase() }, label = { Text("Поиск монеты") },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        items(shown, key = { "${it.marketType}:${it.symbol}" }) { t ->
            val up = t.changePercent >= 0
            Row(
                Modifier.fillMaxWidth().clickable { onChart(t.symbol) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold)
                    Text("${t.marketType.label} • ${t.symbol.removeSuffix("USDT")}/USDT", fontSize = 12.sp, color = XMuted)
                }
                Text(xPrice(t.price), Modifier.width(118.dp), textAlign = TextAlign.End)
                Spacer(Modifier.width(12.dp))
                Text(String.format(Locale.US, "%+.2f%%", t.changePercent), color = if (up) XGreen else XRed, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
        }
    }
}

@Composable
private fun XChartScreen(
    market: MarketType, symbol: String, coins: List<String>, onMarket: (MarketType) -> Unit, onSymbol: (String) -> Unit
) {
    var timeframe by remember { mutableStateOf(xTimeframes[3]) }
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

    LazyColumn(contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            XHeader(
                "График",
                if (market == MarketType.SPOT) "Интерактивный Spot график" else "Интерактивный Futures график"
            )
        }
        item { XMarketSegment(market, onMarket, Modifier.padding(horizontal = 20.dp).fillMaxWidth()) }
        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { choose = true },
                color = XSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .025f))
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    XCoinBadge(symbol.removeSuffix("USDT"))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${symbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M", color = XMuted, fontSize = 13.sp)
                    }
                    Text("Сменить", color = XBlue, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text("›", color = XBlue, fontSize = 24.sp)
                }
            }
        }
        item {
            val t = ticker
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(t?.let { xPrice(it.price) } ?: "—", fontSize = 39.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                val ch = t?.changePercent ?: 0.0
                Text(
                    if (t == null) "Загрузка…" else String.format(Locale.US, "%+.2f%% за 24ч", ch),
                    color = if (ch >= 0) XGreen else XRed, fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                xTimeframes.forEach { tf ->
                    XTimeframeButton(tf.label, timeframe == tf, Modifier.weight(1f)) { timeframe = tf }
                }
            }
        }
        item {
            if (candles.isEmpty()) {
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(520.dp),
                    color = XSurface2, shape = RoundedCornerShape(22.dp)
                ) { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                XInteractiveChartPanel(candles)
            }
        }
        item {
            val t = ticker
            if (t != null) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    XStat("24ч максимум", xPrice(t.high24h), Modifier.weight(1f))
                    XStat("24ч минимум", xPrice(t.low24h), Modifier.weight(1f))
                }
            }
        }
        item {
            val t = ticker
            if (t != null) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    XStat("Объём 24ч", xCompact(t.quoteVolume24h), Modifier.weight(1f))
                    XStat("Изменение", String.format(Locale.US, "%+.2f%%", t.changePercent), Modifier.weight(1f))
                }
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
                        Text("$coin / USDT", Modifier.fillMaxWidth().clickable {
                            onSymbol("${coin}USDT"); query = coin; choose = false
                        }.padding(vertical = 9.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choose = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun XCoinBadge(symbol: String) {
    val label = when (symbol) {
        "BTC" -> "₿"
        "ETH" -> "Ξ"
        else -> symbol.take(1)
    }
    val bg = when (symbol) {
        "BTC" -> Color(0xFFF7931A)
        "ETH" -> Color(0xFF627EEA)
        else -> XBlueSoft
    }
    Surface(modifier = Modifier.size(48.dp), shape = CircleShape, color = bg) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun XTimeframeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        color = if (selected) Color(0xFF345CD7) else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) XBlue else Color(0xFF566178))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) Color.White else Color(0xFFD4D9E5))
        }
    }
}

@Composable
private fun XInteractiveChartPanel(candles: List<Candle>) {
    val total = candles.size
    val minVisible = minOf(15, total).coerceAtLeast(1)
    val maxVisible = minOf(220, total).coerceAtLeast(minVisible)
    var visibleCount by remember(total) { mutableStateOf(minOf(70, total).coerceAtLeast(1)) }
    var endIndex by remember(total) { mutableStateOf(total) }
    var selectedIndex by remember(total) { mutableStateOf<Int?>(null) }
    var rulerMode by remember { mutableStateOf(false) }
    var rulerStart by remember(total) { mutableStateOf<XRulerPoint?>(null) }
    var rulerEnd by remember(total) { mutableStateOf<XRulerPoint?>(null) }

    visibleCount = visibleCount.coerceIn(minVisible, maxVisible)
    endIndex = endIndex.coerceIn(visibleCount, total)
    val startIndex = (endIndex - visibleCount).coerceAtLeast(0)
    val visible = candles.subList(startIndex, endIndex)
    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val selectedVisibleIndex = selectedIndex?.minus(startIndex)?.takeIf { it in visible.indices }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val axisMax = maxPrice + pricePadding
    val axisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val priceRange = (axisMax - axisMin).coerceAtLeast(axisMax * .0001)

    fun resetToLatest() {
        endIndex = total
        selectedIndex = null
    }

    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        color = XSurface2, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .035f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XToolButton("−", Modifier.width(52.dp)) {
                    visibleCount = (visibleCount + 10).coerceAtMost(maxVisible)
                    endIndex = endIndex.coerceAtLeast(visibleCount)
                    selectedIndex = null
                }
                XToolButton("📏  Линейка", Modifier.weight(1.05f), selected = rulerMode) {
                    rulerMode = !rulerMode
                    selectedIndex = null
                    if (!rulerMode) { rulerStart = null; rulerEnd = null }
                }
                XToolButton("К последней", Modifier.weight(1.2f), accent = true) { resetToLatest() }
                XToolButton("+", Modifier.width(52.dp)) {
                    visibleCount = (visibleCount - 10).coerceAtLeast(minVisible)
                    endIndex = endIndex.coerceAtLeast(visibleCount)
                    selectedIndex = null
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$visibleCount свечей", color = XMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (rulerMode) {
                    Text(
                        when {
                            rulerStart == null -> "Линейка: выберите первую точку"
                            rulerEnd == null -> "Линейка: выберите вторую точку"
                            else -> "Измерение готово"
                        },
                        color = if (rulerEnd == null) XBlue else XGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (rulerStart != null || rulerEnd != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("Очистить", color = XMuted, fontSize = 11.sp, modifier = Modifier.clickable { rulerStart = null; rulerEnd = null })
                    }
                }
            }

            val axisLabels = remember(axisMax, axisMin) {
                List(6) { i -> axisMax - (axisMax - axisMin) * i / 5.0 }
            }
            val timeIndices = remember(startIndex, endIndex) {
                if (visible.size <= 1) listOf(0) else listOf(0, visible.lastIndex / 4, visible.lastIndex / 2, visible.lastIndex * 3 / 4, visible.lastIndex).distinct()
            }

            Box(Modifier.fillMaxWidth().height(430.dp)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(total, visibleCount, endIndex) {
                            var panAccumulator = 0f
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newVisible = (visibleCount / zoom).roundToInt().coerceIn(minVisible, maxVisible)
                                if (newVisible != visibleCount) {
                                    visibleCount = newVisible
                                    endIndex = endIndex.coerceIn(visibleCount, total)
                                    selectedIndex = null
                                }
                                val plotWidth = size.width * .87f
                                val pxPerCandle = plotWidth / visibleCount.coerceAtLeast(1)
                                panAccumulator += pan.x
                                val steps = (panAccumulator / pxPerCandle.coerceAtLeast(1f)).toInt()
                                if (steps != 0) {
                                    endIndex = (endIndex - steps).coerceIn(visibleCount, total)
                                    panAccumulator -= steps * pxPerCandle
                                    selectedIndex = null
                                }
                            }
                        }
                        .pointerInput(total, visibleCount, endIndex, rulerMode) {
                            detectTapGestures(
                                onDoubleTap = { resetToLatest() },
                                onTap = { pos ->
                                    val currentStart = (endIndex - visibleCount).coerceAtLeast(0)
                                    val currentVisible = candles.subList(currentStart, endIndex)
                                    val plotWidth = size.width * .87f
                                    val effectiveX = pos.x.coerceIn(0f, plotWidth)
                                    val localIndex = ((effectiveX / plotWidth) * visibleCount).toInt().coerceIn(0, visibleCount - 1)
                                    val globalIndex = (currentStart + localIndex).coerceIn(0, total - 1)
                                    if (rulerMode) {
                                        val localMax = currentVisible.maxOfOrNull { it.high } ?: 1.0
                                        val localMin = currentVisible.minOfOrNull { it.low } ?: 0.0
                                        val localPad = ((localMax - localMin) * .06).coerceAtLeast(localMax * .0004)
                                        val localAxisMax = localMax + localPad
                                        val localAxisMin = (localMin - localPad).coerceAtLeast(0.0)
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001)
                                        val plotHeight = size.height * .82f
                                        val y = pos.y.coerceIn(0f, plotHeight)
                                        val price = localAxisMax - (y / plotHeight) * localRange
                                        val point = XRulerPoint(globalIndex, price)
                                        if (rulerStart == null || rulerEnd != null) {
                                            rulerStart = point; rulerEnd = null
                                        } else {
                                            rulerEnd = point
                                        }
                                        selectedIndex = null
                                    } else {
                                        selectedIndex = globalIndex
                                    }
                                }
                            )
                        }
                ) {
                    val plotWidth = size.width * .87f
                    val plotHeight = size.height * .82f
                    val volumeTop = plotHeight * .82f
                    val maxVol = visible.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0
                    val candleWidth = plotWidth / visible.size.coerceAtLeast(1)

                    repeat(6) { i ->
                        val y = plotHeight * i / 5f
                        drawLine(XGrid.copy(alpha = .55f), Offset(0f, y), Offset(plotWidth, y), 1f)
                    }
                    repeat(5) { i ->
                        val x = plotWidth * i / 4f
                        drawLine(XGrid.copy(alpha = .35f), Offset(x, 0f), Offset(x, plotHeight), 1f)
                    }
                    drawLine(XBorder.copy(alpha = .7f), Offset(plotWidth, 0f), Offset(plotWidth, plotHeight), 1f)

                    fun py(value: Double): Float = plotHeight - (((value - axisMin) / priceRange).toFloat() * plotHeight)
                    fun px(globalIndex: Int): Float = candleWidth * (globalIndex - startIndex) + candleWidth / 2f

                    visible.forEachIndexed { i, c ->
                        val x = candleWidth * i + candleWidth / 2f
                        val color = if (c.close >= c.open) XGreen else XRed
                        val oy = py(c.open); val cy = py(c.close); val hy = py(c.high); val ly = py(c.low)
                        drawLine(color, Offset(x, hy), Offset(x, ly), 1.7f)
                        val half = (candleWidth * .28f).coerceIn(1.2f, 7f)
                        val top = minOf(oy, cy); val bottom = maxOf(oy, cy)
                        drawRect(color, Offset(x - half, top), androidx.compose.ui.geometry.Size(half * 2f, (bottom - top).coerceAtLeast(2.2f)))
                        val vh = (c.volume / maxVol).toFloat() * (plotHeight - volumeTop)
                        drawRect(color.copy(alpha = .38f), Offset(x - half, plotHeight - vh), androidx.compose.ui.geometry.Size(half * 2f, vh))
                    }

                    visible.lastOrNull()?.let { last ->
                        val y = py(last.close)
                        drawLine(
                            XRed.copy(alpha = .5f), Offset(0f, y), Offset(plotWidth, y), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                        )
                    }

                    selectedVisibleIndex?.let { local ->
                        val c = visible[local]
                        val x = candleWidth * local + candleWidth / 2f
                        val y = py(c.close)
                        drawLine(Color.White.copy(alpha = .62f), Offset(x, 0f), Offset(x, plotHeight), 1.2f)
                        drawLine(Color.White.copy(alpha = .42f), Offset(0f, y), Offset(plotWidth, y), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
                    }

                    val rs = rulerStart
                    val re = rulerEnd
                    if (rs != null && rs.candleIndex in startIndex until endIndex) {
                        val sx = px(rs.candleIndex); val sy = py(rs.price)
                        drawCircle(XBlue, 6f, Offset(sx, sy)); drawCircle(Color.White, 2.3f, Offset(sx, sy))
                        if (re != null && re.candleIndex in startIndex until endIndex) {
                            val ex = px(re.candleIndex); val ey = py(re.price)
                            val rc = if (re.price >= rs.price) XGreen else XRed
                            drawRect(
                                rc.copy(alpha = .08f), Offset(minOf(sx, ex), minOf(sy, ey)),
                                androidx.compose.ui.geometry.Size(abs(ex - sx).coerceAtLeast(1f), abs(ey - sy).coerceAtLeast(1f))
                            )
                            drawLine(rc, Offset(sx, sy), Offset(ex, ey), 3f)
                            drawCircle(rc, 6f, Offset(ex, ey)); drawCircle(Color.White, 2.3f, Offset(ex, ey))
                        }
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(58.dp).padding(top = 2.dp, bottom = 70.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    axisLabels.forEach { p -> Text(xAxisPrice(p), color = Color(0xFFAEB8CC), fontSize = 10.sp, maxLines = 1) }
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 62.dp, start = 2.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    timeIndices.forEach { idx ->
                        Text(xTimeLabel(visible.getOrNull(idx)?.openTime ?: 0L), color = Color(0xFFAEB8CC), fontSize = 10.sp)
                    }
                }
            }

            if (!rulerMode && selected != null) {
                HorizontalDivider(color = Color.White.copy(alpha = .07f))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(selected.openTime)), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        XCandleValue("O", xPrice(selected.open), Modifier.weight(1f))
                        XCandleValue("H", xPrice(selected.high), Modifier.weight(1f))
                        XCandleValue("L", xPrice(selected.low), Modifier.weight(1f))
                        XCandleValue("C", xPrice(selected.close), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Объём: ${xCompact(selected.volume)}", color = XMuted, fontSize = 12.sp)
                }
            }

            val rs = rulerStart
            val re = rulerEnd
            if (rulerMode && rs != null && re != null) {
                val first = candles[rs.candleIndex]
                val second = candles[re.candleIndex]
                val priceDiff = re.price - rs.price
                val percent = if (rs.price != 0.0) priceDiff / rs.price * 100.0 else 0.0
                val candleDiff = abs(re.candleIndex - rs.candleIndex)
                val timeDiff = abs(second.openTime - first.openTime)
                val c = if (priceDiff >= 0) XGreen else XRed
                HorizontalDivider(color = Color.White.copy(alpha = .07f))
                Column(Modifier.padding(top = 10.dp)) {
                    Text("📏 Измерение", color = c, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XMeasure("Изменение", String.format(Locale.US, "%+.2f%%", percent), Modifier.weight(1f), c)
                        XMeasure("Цена", xSignedPrice(priceDiff), Modifier.weight(1f), c)
                        XMeasure("Время", xDuration(timeDiff), Modifier.weight(1f), Color.White)
                        XMeasure("Свечей", candleDiff.toString(), Modifier.weight(1f), Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun XToolButton(
    text: String, modifier: Modifier, selected: Boolean = false, accent: Boolean = false, onClick: () -> Unit
) {
    val bg = when {
        accent -> Color(0xFF193B7B)
        selected -> Color(0xFF4B4E70)
        else -> Color(0xFF121E31)
    }
    val border = when {
        accent -> XBlue
        selected -> Color(0xFF626984)
        else -> XBorder
    }
    Surface(
        modifier = modifier.height(52.dp).clickable(onClick = onClick), color = bg,
        shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, border)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, textAlign = TextAlign.Center, color = if (accent) Color(0xFF6C8CFF) else Color(0xFFE8EBF5), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun XCandleValue(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = XMuted, fontSize = 10.sp)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun XMeasure(label: String, value: String, modifier: Modifier, color: Color) {
    Surface(modifier, color = XSurface, shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(label, color = XMuted, fontSize = 9.sp, maxLines = 1)
            Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun XStat(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = XSurface, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = XMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun XSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { XHeader("Настройки", "Spot + Futures") }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = XSurface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Резервный REST-скан", fontWeight = FontWeight.Bold)
                    Text("Используется при потере Live WebSocket", fontSize = 12.sp, color = XMuted)
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = seconds, onValueChange = { seconds = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.weight(1f), label = { Text("Секунд") }, singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { RuleStore.setScanIntervalSeconds(activity, (seconds.toIntOrNull() ?: 1).coerceAtLeast(1)) }) { Text("Сохранить") }
                    }
                }
            }
        }
        item {
            XAction("🔋", "Оптимизация батареи", "Разрешить работу мониторинга в фоне") {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") })
                }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        item {
            XAction("🔇", "Остановить звук", "Заглушить активную тревогу") {
                activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE))
            }
        }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = XSurface, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto Alarm", fontWeight = FontWeight.Bold)
                    Text("Версия 1.0.0 • новый интерфейс графика", color = XMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun xFmt(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

private fun xPrice(v: Double): String = when {
    v >= 1000 -> "$" + DecimalFormat("#,##0.00").format(v)
    v >= 1 -> "$" + DecimalFormat("0.00##").format(v)
    else -> "$" + DecimalFormat("0.000000##").format(v)
}

private fun xAxisPrice(v: Double): String = when {
    v >= 1000 -> DecimalFormat("#,##0.00").format(v)
    v >= 1 -> DecimalFormat("0.00##").format(v)
    else -> DecimalFormat("0.000000").format(v)
}

private fun xCompact(v: Double): String = when {
    v >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", v / 1_000_000_000)
    v >= 1_000_000 -> String.format(Locale.US, "$%.2fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "$%.2fK", v / 1_000)
    else -> String.format(Locale.US, "%.4f", v)
}

private fun xSignedPrice(v: Double): String {
    val absValue = abs(v)
    val raw = when {
        absValue >= 1000 -> DecimalFormat("#,##0.00").format(absValue)
        absValue >= 1 -> DecimalFormat("0.00##").format(absValue)
        else -> DecimalFormat("0.000000##").format(absValue)
    }
    return (if (v >= 0) "+$" else "-$") + raw
}

private fun xDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    return when {
        totalMinutes < 60 -> "${totalMinutes}м"
        totalMinutes < 1440 -> "${totalMinutes / 60}ч ${totalMinutes % 60}м"
        else -> "${totalMinutes / 1440}д ${(totalMinutes % 1440) / 60}ч"
    }
}

private fun xTimeLabel(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
