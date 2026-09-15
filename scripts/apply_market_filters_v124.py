from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

market_block = r'''@Composable
private fun VMarket(market: MarketType, coins: List<String>, onMarket: (MarketType) -> Unit, onChart: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var tickers by remember { mutableStateOf<List<MarketTicker>>(emptyList()) }
    var sortMode by remember { mutableStateOf("volume") }
    var descending by remember { mutableStateOf(true) }

    LaunchedEffect(market, coins) {
        tickers = emptyList()
        while (true) {
            tickers = MarketDataRepository.loadPopularTickers(coins, market)
            delay(8_000)
        }
    }

    val filtered = tickers.filter { it.symbol.removeSuffix("USDT").contains(search, true) }
    val shown = when (sortMode) {
        "change" -> if (descending) filtered.sortedByDescending { it.changePercent } else filtered.sortedBy { it.changePercent }
        else -> if (descending) filtered.sortedByDescending { it.quoteVolume24h } else filtered.sortedBy { it.quoteVolume24h }
    }

    fun toggleSort(mode: String) {
        if (sortMode == mode) descending = !descending
        else {
            sortMode = mode
            descending = true
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item { VHeader("Рынок", if (market == MarketType.SPOT) "Binance Spot" else "Binance Futures USDT-M") }
        item {
            VMarketSegment(market, onMarket, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
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
        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sortMode == "volume",
                    onClick = { toggleSort("volume") },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            "Объём ${if (sortMode == "volume") if (descending) "↓" else "↑" else "↕"}",
                            maxLines = 1
                        )
                    }
                )
                FilterChip(
                    selected = sortMode == "change",
                    onClick = { toggleSort("change") },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            "Изменение % ${if (sortMode == "change") if (descending) "↓" else "↑" else "↕"}",
                            maxLines = 1
                        )
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (tickers.isEmpty()) {
            item { LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) }
        } else if (shown.isEmpty()) {
            item { Text("Ничего не найдено", Modifier.padding(18.dp), color = VMuted) }
        }

        items(shown, key = { "${it.marketType}:${it.symbol}" }) { t ->
            Row(
                Modifier.fillMaxWidth().clickable { onChart(t.symbol) }
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.symbol.removeSuffix("USDT"), fontWeight = FontWeight.Bold)
                    Text(
                        "Объём 24ч: ${vCompact(t.quoteVolume24h)}",
                        fontSize = 11.sp,
                        color = VMuted,
                        maxLines = 1
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(vPrice(t.price), textAlign = TextAlign.End)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        String.format(Locale.US, "%+.2f%%", t.changePercent),
                        color = if (t.changePercent >= 0) VGreen else VRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
        }
    }
}

@Composable
private fun VChartScreen'''

pattern = re.compile(
    r'''@Composable\nprivate fun VMarket\(market: MarketType, coins: List<String>, onMarket: \(MarketType\) -> Unit, onChart: \(String\) -> Unit\) \{.*?\n\}\n\n@Composable\nprivate fun VChartScreen''',
    re.S,
)
app, count = pattern.subn(market_block, app, count=1)
if count != 1:
    raise RuntimeError(f"Could not replace VMarket block: {count}")

app = app.replace(
    "Версия 1.2.3 • Futures WS + REST fallback",
    "Версия 1.2.4 • фильтры рынка"
)
app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 18', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.4"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.2.4 market filters")
