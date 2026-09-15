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

private val VBlue = Color(0xFF4E6DFF)
private val VGreen = Color(0xFF19D7A0)
private val VRed = Color(0xFFFF5B6B)
private val VBg = Color(0xFF07111D)
private val VSurface = Color(0xFF101C2C)
private val VSurface2 = Color(0xFF0B1624)
private val VBorder = Color(0xFF314058)
private val VMuted = Color(0xFF9AA6BC)
private val VGrid = Color(0xFF26364B)

private enum class VTab(val label: String, val icon: String) {
    HOME("Главная", "⌂"), ALARMS("Будильники", "◉"), MARKET("Рынок", "▥"), CHART("График", "⌁"), SETTINGS("Настройки", "⚙")
}

private data class VTimeframe(val label: String, val api: String)
private val vTimeframes = listOf(
    VTimeframe("1м", "1m"), VTimeframe("5м", "5m"), VTimeframe("15м", "15m"),
    VTimeframe("1ч", "1h"), VTimeframe("4ч", "4h"), VTimeframe("1д", "1d"), VTimeframe("1н", "1w")
)
private data class VRulerPoint(val candleIndex: Int, val price: Double)

@Composable
fun ComponentActivity.CryptoAlarmV11App() {
    val activity = this
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VBlue, secondary = VGreen, background = VBg, surface = VSurface, surfaceVariant = VSurface2,
            onBackground = Color(0xFFF1F3FA), onSurface = Color(0xFFF1F3FA), onSurfaceVariant = VMuted
        )
    ) {
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
                NavigationBar(containerColor = Color(0xFF0A1421), tonalElevation = 0.dp) {
                    VTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0xFF434C73),
                                unselectedIconColor = VMuted, unselectedTextColor = VMuted
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
                    VTab.HOME -> VHome(rules, monitoring, onToggle = {
                        if (monitoring) {
                            activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_STOP))
                            monitoring = false
                        } else if (rules.any { it.enabled }) {
                            ContextCompat.startForegroundService(activity, Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_START))
                            monitoring = true
                        }
                    }, onAlarms = { tab = VTab.ALARMS }, onMarket = { tab = VTab.MARKET }, onChart = { tab = VTab.CHART })

                    VTab.ALARMS -> VAlarms(rules, spotCoins, futuresCoins) {
                        rules = it
                        RuleStore.save(activity, it)
                    }

                    VTab.MARKET -> VMarket(market, if (market == MarketType.SPOT) spotCoins else futuresCoins, { market = it }) {
                        symbol = it
                        tab = VTab.CHART
                    }

                    VTab.CHART -> VChartScreen(
                        market, symbol, if (market == MarketType.SPOT) spotCoins else futuresCoins,
                        onMarket = {
                            market = it
                            val list = if (it == MarketType.SPOT) spotCoins else futuresCoins
                            if (symbol.removeSuffix("USDT") !in list) symbol = "BTCUSDT"
                        },
                        onSymbol = { symbol = it }
                    )

                    VTab.SETTINGS -> VSettings(activity, RuleStore.getScanIntervalSeconds(activity))
                }
            }
        }
    }
}

@Composable
private fun VHeader(title: String, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 17.sp, color = VMuted)
    }
}

@Composable
private fun VMarketSegment(market: MarketType, onMarket: (MarketType) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.Transparent, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, VBorder)) {
        Row(Modifier.padding(2.dp)) {
            VSegment("Spot", market == MarketType.SPOT, Modifier.weight(1f)) { onMarket(MarketType.SPOT) }
            Spacer(Modifier.width(2.dp))
            VSegment("Futures USDT-M", market == MarketType.FUTURES, Modifier.weight(1.65f)) { onMarket(MarketType.FUTURES) }
        }
    }
}

@Composable
private fun VSegment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(50.dp).clickable(onClick = onClick), color = if (selected) Color(0xFF4B4E70) else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Color(0xFFC5CBDB), fontSize = 15.sp) }
    }
}

@Composable
private fun VHome(rules: List<AlarmRule>, monitoring: Boolean, onToggle: () -> Unit, onAlarms: () -> Unit, onMarket: () -> Unit, onChart: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { VHeader("Crypto Alarm", "Spot + Futures под контролем") }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = Color(0xFF0D2926), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (monitoring) "●" else "○", color = if (monitoring) VGreen else VMuted, fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (monitoring) "Live мониторинг активен" else "Мониторинг выключен", fontWeight = FontWeight.Bold)
                        Text("Spot и Futures работают раздельно", color = VMuted, fontSize = 12.sp)
                    }
                    Text("⚡", color = VGreen, fontSize = 25.sp)
                }
            }
        }
        item { Button(onClick = onToggle, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(58.dp), shape = RoundedCornerShape(17.dp)) { Text(if (monitoring) "■ Остановить мониторинг" else "▶ Начать мониторинг", fontWeight = FontWeight.Bold) } }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VMetric("${rules.count { it.enabled }}", "Активных", Modifier.weight(1f), VBlue)
                VMetric("${rules.count { it.marketType == MarketType.SPOT }}", "Spot", Modifier.weight(1f), VGreen)
                VMetric("${rules.count { it.marketType == MarketType.FUTURES }}", "Futures", Modifier.weight(1f), Color(0xFFFFB454))
            }
        }
        item { VAction("＋", "Создать будильник", "Spot или Futures", onAlarms) }
        item { VAction("▥", "Смотреть рынок", "Цены и изменение за 24ч", onMarket) }
        item { VAction("⌁", "Открыть график", "⚡ LIVE свечи в реальном времени", onChart) }
    }
}

@Composable
private fun VMetric(value: String, label: String, modifier: Modifier, color: Color) {
    Surface(modifier, color = VSurface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, color = VMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VAction(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable(onClick = onClick), color = VSurface, shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = VBlue, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = VMuted, fontSize = 12.sp) }
            Text("›", color = VMuted, fontSize = 22.sp)
        }
    }
}

@Composable
private fun VAlarms(rules: List<AlarmRule>, spotCoins: List<String>, futuresCoins: List<String>, onRules: (List<AlarmRule>) -> Unit) {
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
            item { VHeader("Будильники", "Сигналы Spot и Futures") }
            item { Row(Modifier.padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Все", "Spot", "Futures", "Рост", "Падение").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) } } }
            if (shown.isEmpty()) item { Text("Нет будильников", Modifier.padding(18.dp), color = VMuted) }
            items(shown, key = { it.id }) { rule ->
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text(rule.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.width(8.dp)); AssistChip(onClick = {}, label = { Text(rule.marketType.label) }) }
                            Text("${if (rule.direction == AlertDirection.RISE) "↗ Рост" else "↘ Падение"} ${vFmt(rule.thresholdPercent)}% за ${rule.windowMinutes} мин", color = if (rule.direction == AlertDirection.RISE) VGreen else VRed)
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { on -> onRules(rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }) })
                        TextButton(onClick = { onRules(rules.filterNot { it.id == rule.id }) }) { Text("×") }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = VBlue) { Text("＋", fontSize = 28.sp) }
    }
    if (showCreate) VCreateAlarm(spotCoins, futuresCoins, { showCreate = false }) { onRules(rules + it); showCreate = false }
}

@Composable
private fun VCreateAlarm(spotCoins: List<String>, futuresCoins: List<String>, onDismiss: () -> Unit, onCreate: (AlarmRule) -> Unit) {
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
                VMarketSegment(market, { market = it; query = "BTC"; symbol = "BTCUSDT" }, Modifier.fillMaxWidth())
                OutlinedTextField(value = query, onValueChange = { query = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, label = { Text("Монета") }, singleLine = true)
                matches.forEach { coin -> Text("$coin / USDT${if (symbol == "${coin}USDT") "  ✓" else ""}", Modifier.fillMaxWidth().clickable { symbol = "${coin}USDT"; query = coin }.padding(vertical = 4.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = direction == AlertDirection.DROP, onClick = { direction = AlertDirection.DROP }, label = { Text("📉 Падение") })
                    FilterChip(selected = direction == AlertDirection.RISE, onClick = { direction = AlertDirection.RISE }, label = { Text("📈 Рост") })
                }
                OutlinedTextField(value = percent, onValueChange = { percent = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } }, label = { Text("Изменение, %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("Период, минут") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            val p = percent.toDoubleOrNull(); val m = minutes.toIntOrNull()
            if (p != null && p in 0.1..99.9 && m != null && m in 1..999 && symbol.removeSuffix("USDT") in coins) onCreate(AlarmRule(symbol = symbol, thresholdPercent = p, windowMinutes = m, direction = direction, marketType = market))
        }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun VMarket(market: MarketType, coins: List<String>, onMarket: (MarketType) -> Unit, onChart: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    val preferred = remember(coins) { listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "TON", "ADA", "PEPE", "SUI", "LINK", "AVAX").filter { it in coins }.ifEmpty { coins.take(12) } }
    LaunchedEffect(market, preferred) { tickers = emptyList(); while (true) { tickers = MarketDataRepository.loadPopularTickers(preferred, market); delay(8_000) } }
    val shown = tickers.filter { it.symbol.removeSuffix("USDT").contains(search, true) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item { VHeader("Рынок", if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M") }
        item { VMarketSegment(market, onMarket, Modifier.padding(horizontal = 16.dp).fillMaxWidth()); Spacer(Modifier.height(10.dp)) }
        item { OutlinedTextField(value = search, onValueChange = { search = it.uppercase() }, label = { Text("Поиск монеты") }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), singleLine = true); Spacer(Modifier.height(8.dp)) }
        if (shown.isEmpty()) item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        items(shown, key = { "${it.marketType}:${it.symbol}" }) { t ->
            Row(Modifier.fillMaxWidth().clickable { onChart(t.symbol) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(t.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold); Text("${t.marketType.label} • ${t.symbol.removeSuffix("USDT")}/USDT", fontSize = 12.sp, color = VMuted) }
                Text(vPrice(t.price), Modifier.width(118.dp), textAlign = TextAlign.End); Spacer(Modifier.width(12.dp))
                Text(String.format(Locale.US, "%+.2f%%", t.changePercent), color = if (t.changePercent >= 0) VGreen else VRed, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
        }
    }
}

@Composable
private fun VChartScreen(market: MarketType, symbol: String, coins: List<String>, onMarket: (MarketType) -> Unit, onSymbol: (String) -> Unit) {
    var timeframe by remember { mutableStateOf(vTimeframes[3]) }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var ticker by remember { mutableStateOf<MarketTicker?>(null) }
    var liveConnected by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(symbol.removeSuffix("USDT")) }

    LaunchedEffect(market, symbol, timeframe.api) {
        candles = emptyList()
        liveConnected = false
        candles = MarketDataRepository.loadCandles(symbol, timeframe.api, 500, market)
        ticker = MarketDataRepository.loadTicker(symbol, market)
    }

    LaunchedEffect(market, symbol) {
        while (true) {
            delay(30_000)
            val fresh = MarketDataRepository.loadTicker(symbol, market)
            if (fresh != null) {
                val livePrice = ticker?.price
                ticker = if (liveConnected && livePrice != null) fresh.copy(price = livePrice) else fresh
            }
        }
    }

    DisposableEffect(market, symbol, timeframe.api) {
        val socket = LiveChartSocket(
            symbol = symbol,
            marketType = market,
            onConnectedChanged = { liveConnected = it },
            onTrade = { trade ->
                ticker = ticker?.copy(price = trade.price) ?: MarketTicker(symbol, trade.price, 0.0, trade.price, trade.price, 0.0, market)
                if (candles.isNotEmpty()) {
                    candles = applyLiveTradeToCandles(candles, trade, chartIntervalMillis(timeframe.api), 500)
                }
            }
        )
        socket.start()
        onDispose { socket.stop() }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("График", fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(if (market == MarketType.SPOT) "Интерактивный Spot график" else "Интерактивный Futures график", fontSize = 17.sp, color = VMuted)
                }
                Surface(color = if (liveConnected) Color(0xFF10362E) else Color(0xFF33262B), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (liveConnected) VGreen.copy(alpha = .5f) else VRed.copy(alpha = .4f))) {
                    Text(if (liveConnected) "⚡ LIVE" else "● CONNECTING", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = if (liveConnected) VGreen else VMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
        item { VMarketSegment(market, onMarket, Modifier.padding(horizontal = 20.dp).fillMaxWidth()) }
        item {
            Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { choose = true }, color = VSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .025f))) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    VCoinBadge(symbol.removeSuffix("USDT")); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text("${symbol.removeSuffix("USDT")} / USDT", fontWeight = FontWeight.Bold, fontSize = 19.sp); Spacer(Modifier.height(2.dp)); Text(if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M", color = VMuted, fontSize = 13.sp) }
                    Text("Сменить", color = VBlue, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(6.dp)); Text("›", color = VBlue, fontSize = 24.sp)
                }
            }
        }
        item {
            val t = ticker
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(t?.let { vPrice(it.price) } ?: "—", fontSize = 39.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                val ch = t?.changePercent ?: 0.0
                Text(if (t == null) "Загрузка…" else String.format(Locale.US, "%+.2f%% за 24ч", ch), color = if (ch >= 0) VGreen else VRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        item { Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { vTimeframes.forEach { tf -> VTimeframeButton(tf.label, timeframe == tf, Modifier.weight(1f)) { timeframe = tf } } } }
        item {
            if (candles.isEmpty()) Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(520.dp), color = VSurface2, shape = RoundedCornerShape(22.dp)) { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else VInteractiveChartPanel(candles, liveConnected)
        }
        item { ticker?.let { t -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { VStat("24ч максимум", vPrice(t.high24h), Modifier.weight(1f)); VStat("24ч минимум", vPrice(t.low24h), Modifier.weight(1f)) } } }
        item { ticker?.let { t -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { VStat("Объём 24ч", vCompact(t.quoteVolume24h), Modifier.weight(1f)); VStat("Изменение", String.format(Locale.US, "%+.2f%%", t.changePercent), Modifier.weight(1f)) } } }
    }

    if (choose) AlertDialog(
        onDismissRequest = { choose = false }, title = { Text("Выбрать монету • ${market.label}") },
        text = { Column { OutlinedTextField(value = query, onValueChange = { query = it.uppercase() }, label = { Text("Поиск") }, singleLine = true); Spacer(Modifier.height(8.dp)); coins.filter { it.contains(query, true) }.take(8).forEach { coin -> Text("$coin / USDT", Modifier.fillMaxWidth().clickable { onSymbol("${coin}USDT"); query = coin; choose = false }.padding(vertical = 9.dp)) } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { choose = false }) { Text("Закрыть") } }
    )
}

@Composable
private fun VCoinBadge(symbol: String) {
    val label = when (symbol) { "BTC" -> "₿"; "ETH" -> "Ξ"; else -> symbol.take(1) }
    val bg = when (symbol) { "BTC" -> Color(0xFFF7931A); "ETH" -> Color(0xFF627EEA); else -> Color(0xFF293B7D) }
    Surface(Modifier.size(48.dp), shape = CircleShape, color = bg) { Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun VTimeframeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.height(48.dp).clickable(onClick = onClick), color = if (selected) Color(0xFF345CD7) else Color.Transparent, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) VBlue else Color(0xFF566178))) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) Color.White else Color(0xFFD4D9E5)) }
    }
}

@Composable
private fun VInteractiveChartPanel(candles: List<Candle>, live: Boolean) {
    val total = candles.size
    val minVisible = minOf(15, total).coerceAtLeast(1)
    val maxVisible = minOf(220, total).coerceAtLeast(minVisible)
    var visibleCount by remember(total) { mutableStateOf(minOf(70, total).coerceAtLeast(1)) }
    var endIndex by remember(total) { mutableStateOf(total) }
    var selectedIndex by remember(total) { mutableStateOf<Int?>(null) }
    var rulerMode by remember { mutableStateOf(false) }
    var rulerStart by remember(total) { mutableStateOf<VRulerPoint?>(null) }
    var rulerEnd by remember(total) { mutableStateOf<VRulerPoint?>(null) }

    val wasAtEnd = endIndex >= total - 1
    if (wasAtEnd) endIndex = total
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

    fun resetToLatest() { endIndex = total; selectedIndex = null }

    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface2, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .035f))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VTool("📏  Линейка", Modifier.weight(1f), selected = rulerMode) { rulerMode = !rulerMode; selectedIndex = null; if (!rulerMode) { rulerStart = null; rulerEnd = null } }
                VTool("К последней", Modifier.weight(1f), accent = true) { resetToLatest() }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$visibleCount свечей • масштаб двумя пальцами", color = VMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (live) "⚡ обновляется по сделкам" else "ожидание Live…", color = if (live) VGreen else VMuted, fontSize = 11.sp)
                if (rulerMode) {
                    Spacer(Modifier.width(8.dp)); Text(when { rulerStart == null -> "1-я точка"; rulerEnd == null -> "2-я точка"; else -> "готово" }, color = VBlue, fontSize = 11.sp)
                    if (rulerStart != null || rulerEnd != null) { Spacer(Modifier.width(8.dp)); Text("Очистить", color = VMuted, fontSize = 11.sp, modifier = Modifier.clickable { rulerStart = null; rulerEnd = null }) }
                }
            }

            val axisLabels = remember(axisMax, axisMin) { List(6) { i -> axisMax - (axisMax - axisMin) * i / 5.0 } }
            val timeIndices = remember(startIndex, endIndex) { if (visible.size <= 1) listOf(0) else listOf(0, visible.lastIndex / 4, visible.lastIndex / 2, visible.lastIndex * 3 / 4, visible.lastIndex).distinct() }

            Box(Modifier.fillMaxWidth().height(430.dp)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(total, visibleCount, endIndex) {
                            var panAccumulator = 0f
                            detectTransformGestures { _, pan, zoom, _ ->
                                val effectiveZoom = 1f + (zoom - 1f) * 1.2f
                                val newVisible = (visibleCount / effectiveZoom).roundToInt().coerceIn(minVisible, maxVisible)
                                if (newVisible != visibleCount) { visibleCount = newVisible; endIndex = endIndex.coerceIn(visibleCount, total); selectedIndex = null }
                                val plotWidth = size.width * .87f
                                val pxPerCandle = plotWidth / visibleCount.coerceAtLeast(1)
                                panAccumulator += pan.x
                                val steps = (panAccumulator / pxPerCandle.coerceAtLeast(1f)).toInt()
                                if (steps != 0) { endIndex = (endIndex - steps).coerceIn(visibleCount, total); panAccumulator -= steps * pxPerCandle; selectedIndex = null }
                            }
                        }
                        .pointerInput(total, visibleCount, endIndex, rulerMode) {
                            detectTapGestures(onDoubleTap = { resetToLatest() }, onTap = { pos ->
                                val currentStart = (endIndex - visibleCount).coerceAtLeast(0)
                                val currentVisible = candles.subList(currentStart, endIndex)
                                val plotWidth = size.width * .87f
                                val localIndex = (((pos.x.coerceIn(0f, plotWidth)) / plotWidth) * visibleCount).toInt().coerceIn(0, visibleCount - 1)
                                val globalIndex = (currentStart + localIndex).coerceIn(0, total - 1)
                                if (rulerMode) {
                                    val localMax = currentVisible.maxOfOrNull { it.high } ?: 1.0; val localMin = currentVisible.minOfOrNull { it.low } ?: 0.0
                                    val localPad = ((localMax - localMin) * .06).coerceAtLeast(localMax * .0004); val localAxisMax = localMax + localPad; val localAxisMin = (localMin - localPad).coerceAtLeast(0.0)
                                    val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001); val plotHeight = size.height * .82f
                                    val price = localAxisMax - (pos.y.coerceIn(0f, plotHeight) / plotHeight) * localRange
                                    val point = VRulerPoint(globalIndex, price)
                                    if (rulerStart == null || rulerEnd != null) { rulerStart = point; rulerEnd = null } else rulerEnd = point
                                    selectedIndex = null
                                } else selectedIndex = globalIndex
                            })
                        }
                ) {
                    val plotWidth = size.width * .87f; val plotHeight = size.height * .82f; val volumeTop = plotHeight * .82f
                    val maxVol = visible.maxOfOrNull { it.volume }?.coerceAtLeast(1.0) ?: 1.0; val candleWidth = plotWidth / visible.size.coerceAtLeast(1)
                    repeat(6) { i -> val y = plotHeight * i / 5f; drawLine(VGrid.copy(alpha = .55f), Offset(0f, y), Offset(plotWidth, y), 1f) }
                    repeat(5) { i -> val x = plotWidth * i / 4f; drawLine(VGrid.copy(alpha = .35f), Offset(x, 0f), Offset(x, plotHeight), 1f) }
                    drawLine(VBorder.copy(alpha = .7f), Offset(plotWidth, 0f), Offset(plotWidth, plotHeight), 1f)
                    fun py(value: Double): Float = plotHeight - (((value - axisMin) / priceRange).toFloat() * plotHeight)
                    fun px(globalIndex: Int): Float = candleWidth * (globalIndex - startIndex) + candleWidth / 2f
                    visible.forEachIndexed { i, c ->
                        val x = candleWidth * i + candleWidth / 2f; val color = if (c.close >= c.open) VGreen else VRed
                        val oy = py(c.open); val cy = py(c.close); val hy = py(c.high); val ly = py(c.low)
                        drawLine(color, Offset(x, hy), Offset(x, ly), 1.7f)
                        val half = (candleWidth * .28f).coerceIn(1.2f, 7f); val top = minOf(oy, cy); val bottom = maxOf(oy, cy)
                        drawRect(color, Offset(x - half, top), androidx.compose.ui.geometry.Size(half * 2f, (bottom - top).coerceAtLeast(2.2f)))
                        val vh = (c.volume / maxVol).toFloat() * (plotHeight - volumeTop); drawRect(color.copy(alpha = .38f), Offset(x - half, plotHeight - vh), androidx.compose.ui.geometry.Size(half * 2f, vh))
                    }
                    visible.lastOrNull()?.let { last -> val y = py(last.close); drawLine(if (live) VGreen.copy(alpha = .55f) else VRed.copy(alpha = .45f), Offset(0f, y), Offset(plotWidth, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))) }
                    selectedVisibleIndex?.let { local -> val c = visible[local]; val x = candleWidth * local + candleWidth / 2f; val y = py(c.close); drawLine(Color.White.copy(alpha = .62f), Offset(x, 0f), Offset(x, plotHeight), 1.2f); drawLine(Color.White.copy(alpha = .42f), Offset(0f, y), Offset(plotWidth, y), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))) }
                    val rs = rulerStart; val re = rulerEnd
                    if (rs != null && rs.candleIndex in startIndex until endIndex) {
                        val sx = px(rs.candleIndex); val sy = py(rs.price); drawCircle(VBlue, 6f, Offset(sx, sy)); drawCircle(Color.White, 2.3f, Offset(sx, sy))
                        if (re != null && re.candleIndex in startIndex until endIndex) { val ex = px(re.candleIndex); val ey = py(re.price); val rc = if (re.price >= rs.price) VGreen else VRed; drawRect(rc.copy(alpha = .08f), Offset(minOf(sx, ex), minOf(sy, ey)), androidx.compose.ui.geometry.Size(abs(ex - sx).coerceAtLeast(1f), abs(ey - sy).coerceAtLeast(1f))); drawLine(rc, Offset(sx, sy), Offset(ex, ey), 3f); drawCircle(rc, 6f, Offset(ex, ey)); drawCircle(Color.White, 2.3f, Offset(ex, ey)) }
                    }
                }
                Column(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(58.dp).padding(top = 2.dp, bottom = 70.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) { axisLabels.forEach { p -> Text(vAxisPrice(p), color = Color(0xFFAEB8CC), fontSize = 10.sp, maxLines = 1) } }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 62.dp, start = 2.dp, bottom = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { timeIndices.forEach { idx -> Text(vTimeLabel(visible.getOrNull(idx)?.openTime ?: 0L), color = Color(0xFFAEB8CC), fontSize = 10.sp) } }
            }

            if (!rulerMode && selected != null) {
                HorizontalDivider(color = Color.White.copy(alpha = .07f)); Column(Modifier.padding(top = 10.dp)) {
                    Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(selected.openTime)), fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { VCandleValue("O", vPrice(selected.open), Modifier.weight(1f)); VCandleValue("H", vPrice(selected.high), Modifier.weight(1f)); VCandleValue("L", vPrice(selected.low), Modifier.weight(1f)); VCandleValue("C", vPrice(selected.close), Modifier.weight(1f)) }
                    Spacer(Modifier.height(6.dp)); Text("Объём: ${vCompact(selected.volume)}", color = VMuted, fontSize = 12.sp)
                }
            }
            val rs = rulerStart; val re = rulerEnd
            if (rulerMode && rs != null && re != null) {
                val first = candles[rs.candleIndex]; val second = candles[re.candleIndex]; val priceDiff = re.price - rs.price; val percent = if (rs.price != 0.0) priceDiff / rs.price * 100.0 else 0.0
                val c = if (priceDiff >= 0) VGreen else VRed
                HorizontalDivider(color = Color.White.copy(alpha = .07f)); Column(Modifier.padding(top = 10.dp)) { Text("📏 Измерение", color = c, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { VMeasure("Изменение", String.format(Locale.US, "%+.2f%%", percent), Modifier.weight(1f), c); VMeasure("Цена", vSignedPrice(priceDiff), Modifier.weight(1f), c); VMeasure("Время", vDuration(abs(second.openTime - first.openTime)), Modifier.weight(1f), Color.White); VMeasure("Свечей", abs(re.candleIndex - rs.candleIndex).toString(), Modifier.weight(1f), Color.White) } }
            }
        }
    }
}

@Composable
private fun VTool(text: String, modifier: Modifier, selected: Boolean = false, accent: Boolean = false, onClick: () -> Unit) {
    val bg = when { accent -> Color(0xFF193B7B); selected -> Color(0xFF4B4E70); else -> Color(0xFF121E31) }
    val border = when { accent -> VBlue; selected -> Color(0xFF626984); else -> VBorder }
    Surface(modifier.height(52.dp).clickable(onClick = onClick), color = bg, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, border)) { Box(contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center, color = if (accent) Color(0xFF6C8CFF) else Color(0xFFE8EBF5), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) } }
}

@Composable private fun VCandleValue(label: String, value: String, modifier: Modifier) { Column(modifier) { Text(label, color = VMuted, fontSize = 10.sp); Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) } }
@Composable private fun VMeasure(label: String, value: String, modifier: Modifier, color: Color) { Surface(modifier, color = VSurface, shape = RoundedCornerShape(11.dp)) { Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { Text(label, color = VMuted, fontSize = 9.sp, maxLines = 1); Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) } } }
@Composable private fun VStat(label: String, value: String, modifier: Modifier) { Surface(modifier, color = VSurface, shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(14.dp)) { Text(label, color = VMuted, fontSize = 12.sp); Spacer(Modifier.height(4.dp)); Text(value, fontWeight = FontWeight.Bold) } } }

@Composable
private fun VSettings(activity: ComponentActivity, scan: Int) {
    var seconds by remember { mutableStateOf(scan.toString()) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { VHeader("Настройки", "Spot + Futures") }
        item { Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(16.dp)) { Text("Резервный REST-скан", fontWeight = FontWeight.Bold); Text("Для сигналов при потере Live WebSocket", fontSize = 12.sp, color = VMuted); Spacer(Modifier.height(9.dp)); Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(value = seconds, onValueChange = { seconds = it.filter(Char::isDigit).take(5) }, modifier = Modifier.weight(1f), label = { Text("Секунд") }, singleLine = true); Spacer(Modifier.width(8.dp)); Button(onClick = { RuleStore.setScanIntervalSeconds(activity, (seconds.toIntOrNull() ?: 1).coerceAtLeast(1)) }) { Text("Сохранить") } } } } }
        item { VAction("🔋", "Оптимизация батареи", "Разрешить работу мониторинга в фоне") { runCatching { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${activity.packageName}") }) }.onFailure { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } } }
        item { VAction("🔇", "Остановить звук", "Заглушить активную тревогу") { activity.startService(Intent(activity, MarketMonitorService::class.java).setAction(MarketMonitorService.ACTION_SILENCE)) } }
        item { Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = VSurface, shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(16.dp)) { Text("Crypto Alarm", fontWeight = FontWeight.Bold); Text("Версия 1.1.2 • pinch-to-zoom", color = VMuted, fontSize = 13.sp) } } }
    }
}

private fun vFmt(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)
private fun vPrice(v: Double): String = when { v >= 1000 -> "$" + DecimalFormat("#,##0.00").format(v); v >= 1 -> "$" + DecimalFormat("0.00##").format(v); else -> "$" + DecimalFormat("0.000000##").format(v) }
private fun vAxisPrice(v: Double): String = when { v >= 1000 -> DecimalFormat("#,##0.00").format(v); v >= 1 -> DecimalFormat("0.00##").format(v); else -> DecimalFormat("0.000000").format(v) }
private fun vCompact(v: Double): String = when { v >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", v / 1_000_000_000); v >= 1_000_000 -> String.format(Locale.US, "$%.2fM", v / 1_000_000); v >= 1_000 -> String.format(Locale.US, "$%.2fK", v / 1_000); else -> String.format(Locale.US, "%.4f", v) }
private fun vSignedPrice(v: Double): String { val a = abs(v); val raw = when { a >= 1000 -> DecimalFormat("#,##0.00").format(a); a >= 1 -> DecimalFormat("0.00##").format(a); else -> DecimalFormat("0.000000##").format(a) }; return (if (v >= 0) "+$" else "-$") + raw }
private fun vDuration(ms: Long): String { val m = ms / 60_000L; return when { m < 60 -> "${m}м"; m < 1440 -> "${m / 60}ч ${m % 60}м"; else -> "${m / 1440}д ${(m % 1440) / 60}ч" } }
private fun vTimeLabel(timestamp: Long): String = if (timestamp <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
