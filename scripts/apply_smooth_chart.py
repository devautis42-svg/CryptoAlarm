from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
socket_path = root / "app/src/main/java/com/cryptoalarm/app/LiveChartSocket.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

if "SMOOTH_VIEWPORT_V12" not in app:
    app = app.replace(
        "import java.util.Locale\n",
        "import java.util.Locale\nimport java.util.concurrent.ConcurrentLinkedQueue\nimport kotlin.math.ceil\nimport kotlin.math.floor\n"
    )

    queue_anchor = '    var query by remember { mutableStateOf(symbol.removeSuffix("USDT")) }\n'
    if queue_anchor not in app:
        raise RuntimeError("Could not locate chart query state")
    app = app.replace(
        queue_anchor,
        queue_anchor + '    val liveTrades = remember(market, symbol, timeframe.api) { ConcurrentLinkedQueue<LiveTrade>() }\n',
        1
    )

    load_anchor = '''    LaunchedEffect(market, symbol, timeframe.api) {
        candles = emptyList()
        liveConnected = false
        candles = MarketDataRepository.loadCandles(symbol, timeframe.api, 500, market)
        ticker = MarketDataRepository.loadTicker(symbol, market)
    }
'''
    if load_anchor not in app:
        raise RuntimeError("Could not locate initial candle loader")
    app = app.replace(
        load_anchor,
        '''    LaunchedEffect(market, symbol, timeframe.api) {
        candles = emptyList()
        liveConnected = false
        liveTrades.clear()
        candles = MarketDataRepository.loadCandles(symbol, timeframe.api, 500, market)
        ticker = MarketDataRepository.loadTicker(symbol, market)
    }
''',
        1
    )

    socket_pattern = re.compile(
        r'''    DisposableEffect\(market, symbol, timeframe\.api\) \{.*?\n    \}\n\n    LazyColumn''',
        re.S,
    )
    socket_replacement = '''    // SMOOTH_VIEWPORT_V12: WebSocket events are queued and applied at a
    // display-friendly cadence instead of forcing a Compose recomposition for
    // every single BTC trade.
    LaunchedEffect(market, symbol, timeframe.api) {
        val intervalMillis = chartIntervalMillis(timeframe.api)
        while (true) {
            delay(33L)
            if (candles.isEmpty()) continue

            var nextCandles = candles
            var lastPrice: Double? = null
            var drained = 0
            while (drained < 2_000) {
                val trade = liveTrades.poll() ?: break
                nextCandles = applyLiveTradeToCandles(nextCandles, trade, intervalMillis, 500)
                lastPrice = trade.price
                drained++
            }

            if (drained > 0) {
                candles = nextCandles
                lastPrice?.let { price ->
                    ticker = ticker?.copy(price = price)
                        ?: MarketTicker(symbol, price, 0.0, price, price, 0.0, market)
                }
            }
        }
    }

    DisposableEffect(market, symbol, timeframe.api) {
        val socket = LiveChartSocket(
            symbol = symbol,
            marketType = market,
            onConnectedChanged = { liveConnected = it },
            onTrade = { trade -> liveTrades.offer(trade) }
        )
        socket.start()
        onDispose {
            socket.stop()
            liveTrades.clear()
        }
    }

    LazyColumn'''
    app, count = socket_pattern.subn(socket_replacement, app, count=1)
    if count != 1:
        raise RuntimeError(f"Could not replace chart socket block: {count}")

    smooth_panel = r'''@Composable
private fun VInteractiveChartPanel(candles: List<Candle>, live: Boolean) {
    val total = candles.size
    if (total == 0) return

    val minSpan = minOf(8f, total.toFloat()).coerceAtLeast(1f)
    val maxSpan = minOf(220f, total.toFloat()).coerceAtLeast(minSpan)

    // SMOOTH_VIEWPORT_V12: viewport is continuous candle-space, not integer
    // candle counts. This removes the one-candle snapping that made pinch zoom
    // feel rough.
    var viewportSpan by remember { mutableFloatStateOf(minOf(70f, total.toFloat()).coerceAtLeast(1f)) }
    var viewportEnd by remember { mutableFloatStateOf(total.toFloat()) }
    var previousTotal by remember { mutableIntStateOf(total) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var rulerMode by remember { mutableStateOf(false) }
    var rulerStart by remember { mutableStateOf<VRulerPoint?>(null) }
    var rulerEnd by remember { mutableStateOf<VRulerPoint?>(null) }

    LaunchedEffect(total) {
        val oldTotal = previousTotal.coerceAtLeast(1)
        val wasPinnedToLatest = viewportEnd >= oldTotal.toFloat() - 0.35f
        viewportSpan = viewportSpan.coerceIn(minSpan, maxSpan)
        viewportEnd = if (wasPinnedToLatest) {
            total.toFloat()
        } else {
            val delta = total - oldTotal
            (viewportEnd + delta).coerceIn(viewportSpan, total.toFloat())
        }
        previousTotal = total
    }

    val span = viewportSpan.coerceIn(minSpan, maxSpan)
    val end = viewportEnd.coerceIn(span, total.toFloat())
    val start = (end - span).coerceAtLeast(0f)
    val firstIndex = floor(start.toDouble()).toInt().coerceIn(0, total - 1)
    val lastExclusive = ceil(end.toDouble()).toInt().coerceIn(firstIndex + 1, total)
    val visible = candles.subList(firstIndex, lastExclusive)

    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val axisMax = maxPrice + pricePadding
    val axisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val priceRange = (axisMax - axisMin).coerceAtLeast(axisMax * .0001)

    fun clampViewport(rawStart: Float, rawSpan: Float) {
        val safeSpan = rawSpan.coerceIn(minSpan, maxSpan)
        val maxStart = (total.toFloat() - safeSpan).coerceAtLeast(0f)
        val safeStart = rawStart.coerceIn(0f, maxStart)
        viewportSpan = safeSpan
        viewportEnd = safeStart + safeSpan
    }

    fun resetToLatest() {
        viewportEnd = total.toFloat()
        selectedIndex = null
    }

    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        color = VSurface2,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .035f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VTool("📏  Линейка", Modifier.weight(1f), selected = rulerMode) {
                    rulerMode = !rulerMode
                    selectedIndex = null
                    if (!rulerMode) {
                        rulerStart = null
                        rulerEnd = null
                    }
                }
                VTool("К последней", Modifier.weight(1f), accent = true) { resetToLatest() }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "≈${span.roundToInt()} свечей • плавный pinch",
                    color = VMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (live) "⚡ LIVE" else "ожидание Live…",
                    color = if (live) VGreen else VMuted,
                    fontSize = 11.sp,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal
                )
                if (rulerMode) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            rulerStart == null -> "1-я точка"
                            rulerEnd == null -> "2-я точка"
                            else -> "готово"
                        },
                        color = VBlue,
                        fontSize = 11.sp
                    )
                    if (rulerStart != null || rulerEnd != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Очистить",
                            color = VMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                rulerStart = null
                                rulerEnd = null
                            }
                        )
                    }
                }
            }

            val axisLabels = List(6) { i -> axisMax - (axisMax - axisMin) * i / 5.0 }
            val timeFractions = listOf(0f, .25f, .5f, .75f, 1f)
            val timeIndices = timeFractions.map { f ->
                floor((start + span * f).toDouble()).toInt().coerceIn(0, total - 1)
            }.distinct()

            Box(Modifier.fillMaxWidth().height(430.dp)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(total) {
                            detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                                val plotWidth = size.width * .87f
                                if (plotWidth <= 1f) return@detectTransformGestures

                                val oldSpan = viewportSpan.coerceIn(minSpan, maxSpan)
                                val oldEnd = viewportEnd.coerceIn(oldSpan, total.toFloat())
                                val oldStart = oldEnd - oldSpan
                                val focalFraction = (centroid.x.coerceIn(0f, plotWidth) / plotWidth)
                                    .coerceIn(0f, 1f)
                                val focalCandle = oldStart + focalFraction * oldSpan

                                // Continuous zoom around the point between the fingers.
                                // No roundToInt() means no candle-by-candle snapping.
                                val safeZoom = zoom.coerceIn(.75f, 1.35f)
                                val newSpan = (oldSpan / safeZoom).coerceIn(minSpan, maxSpan)
                                var newStart = focalCandle - focalFraction * newSpan

                                // Continuous horizontal pan in candle-space.
                                newStart -= (pan.x / plotWidth) * newSpan
                                clampViewport(newStart, newSpan)
                                selectedIndex = null
                            }
                        }
                        .pointerInput(total, rulerMode) {
                            detectTapGestures(
                                onDoubleTap = { resetToLatest() },
                                onTap = { pos ->
                                    val plotWidth = size.width * .87f
                                    if (plotWidth <= 1f) return@detectTapGestures

                                    val currentSpan = viewportSpan.coerceIn(minSpan, maxSpan)
                                    val currentEnd = viewportEnd.coerceIn(currentSpan, total.toFloat())
                                    val currentStart = currentEnd - currentSpan
                                    val fraction = (pos.x.coerceIn(0f, plotWidth) / plotWidth).coerceIn(0f, 1f)
                                    val domain = currentStart + fraction * currentSpan
                                    val globalIndex = floor(domain.toDouble()).toInt().coerceIn(0, total - 1)

                                    if (rulerMode) {
                                        val fi = floor(currentStart.toDouble()).toInt().coerceIn(0, total - 1)
                                        val le = ceil(currentEnd.toDouble()).toInt().coerceIn(fi + 1, total)
                                        val currentVisible = candles.subList(fi, le)
                                        val localMax = currentVisible.maxOfOrNull { it.high } ?: 1.0
                                        val localMin = currentVisible.minOfOrNull { it.low } ?: 0.0
                                        val localPad = ((localMax - localMin) * .06).coerceAtLeast(localMax * .0004)
                                        val localAxisMax = localMax + localPad
                                        val localAxisMin = (localMin - localPad).coerceAtLeast(0.0)
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001)
                                        val plotHeight = size.height * .82f
                                        val price = localAxisMax - (pos.y.coerceIn(0f, plotHeight) / plotHeight) * localRange
                                        val point = VRulerPoint(globalIndex, price)
                                        if (rulerStart == null || rulerEnd != null) {
                                            rulerStart = point
                                            rulerEnd = null
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
                    val candleWidth = plotWidth / span.coerceAtLeast(1f)

                    repeat(6) { i ->
                        val y = plotHeight * i / 5f
                        drawLine(VGrid.copy(alpha = .55f), Offset(0f, y), Offset(plotWidth, y), 1f)
                    }
                    repeat(5) { i ->
                        val x = plotWidth * i / 4f
                        drawLine(VGrid.copy(alpha = .35f), Offset(x, 0f), Offset(x, plotHeight), 1f)
                    }
                    drawLine(VBorder.copy(alpha = .7f), Offset(plotWidth, 0f), Offset(plotWidth, plotHeight), 1f)

                    fun py(value: Double): Float =
                        plotHeight - (((value - axisMin) / priceRange).toFloat() * plotHeight)

                    fun px(globalIndex: Int): Float =
                        (((globalIndex + .5f) - start) / span) * plotWidth

                    visible.forEachIndexed { localIndex, c ->
                        val globalIndex = firstIndex + localIndex
                        val x = px(globalIndex)
                        if (x < -candleWidth || x > plotWidth + candleWidth) return@forEachIndexed

                        val color = if (c.close >= c.open) VGreen else VRed
                        val oy = py(c.open)
                        val cy = py(c.close)
                        val hy = py(c.high)
                        val ly = py(c.low)
                        drawLine(color, Offset(x, hy), Offset(x, ly), 1.7f)

                        val half = (candleWidth * .28f).coerceIn(1.2f, 8f)
                        val top = minOf(oy, cy)
                        val bottom = maxOf(oy, cy)
                        drawRect(
                            color,
                            Offset(x - half, top),
                            androidx.compose.ui.geometry.Size(half * 2f, (bottom - top).coerceAtLeast(2.2f))
                        )

                        val vh = (c.volume / maxVol).toFloat() * (plotHeight - volumeTop)
                        drawRect(
                            color.copy(alpha = .38f),
                            Offset(x - half, plotHeight - vh),
                            androidx.compose.ui.geometry.Size(half * 2f, vh)
                        )
                    }

                    visible.lastOrNull()?.let { last ->
                        val y = py(last.close)
                        drawLine(
                            if (live) VGreen.copy(alpha = .55f) else VRed.copy(alpha = .45f),
                            Offset(0f, y),
                            Offset(plotWidth, y),
                            1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
                        )
                    }

                    selectedIndex?.takeIf { it in firstIndex until lastExclusive }?.let { global ->
                        val c = candles[global]
                        val x = px(global)
                        val y = py(c.close)
                        drawLine(Color.White.copy(alpha = .62f), Offset(x, 0f), Offset(x, plotHeight), 1.2f)
                        drawLine(
                            Color.White.copy(alpha = .42f),
                            Offset(0f, y),
                            Offset(plotWidth, y),
                            1.2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
                        )
                    }

                    val rs = rulerStart
                    val re = rulerEnd
                    if (rs != null && rs.candleIndex in firstIndex until lastExclusive) {
                        val sx = px(rs.candleIndex)
                        val sy = py(rs.price)
                        drawCircle(VBlue, 6f, Offset(sx, sy))
                        drawCircle(Color.White, 2.3f, Offset(sx, sy))

                        if (re != null && re.candleIndex in firstIndex until lastExclusive) {
                            val ex = px(re.candleIndex)
                            val ey = py(re.price)
                            val rc = if (re.price >= rs.price) VGreen else VRed
                            drawRect(
                                rc.copy(alpha = .08f),
                                Offset(minOf(sx, ex), minOf(sy, ey)),
                                androidx.compose.ui.geometry.Size(
                                    abs(ex - sx).coerceAtLeast(1f),
                                    abs(ey - sy).coerceAtLeast(1f)
                                )
                            )
                            drawLine(rc, Offset(sx, sy), Offset(ex, ey), 3f)
                            drawCircle(rc, 6f, Offset(ex, ey))
                            drawCircle(Color.White, 2.3f, Offset(ex, ey))
                        }
                    }
                }

                Column(
                    Modifier.align(Alignment.TopEnd).fillMaxHeight().width(58.dp)
                        .padding(top = 2.dp, bottom = 70.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    axisLabels.forEach { p ->
                        Text(vAxisPrice(p), color = Color(0xFFAEB8CC), fontSize = 10.sp, maxLines = 1)
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(end = 62.dp, start = 2.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    timeIndices.forEach { index ->
                        Text(
                            vTimeLabel(candles.getOrNull(index)?.openTime ?: 0L),
                            color = Color(0xFFAEB8CC),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (!rulerMode && selected != null) {
                HorizontalDivider(color = Color.White.copy(alpha = .07f))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(selected.openTime)),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VCandleValue("O", vPrice(selected.open), Modifier.weight(1f))
                        VCandleValue("H", vPrice(selected.high), Modifier.weight(1f))
                        VCandleValue("L", vPrice(selected.low), Modifier.weight(1f))
                        VCandleValue("C", vPrice(selected.close), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Объём: ${vCompact(selected.volume)}", color = VMuted, fontSize = 12.sp)
                }
            }

            val rs = rulerStart
            val re = rulerEnd
            if (rulerMode && rs != null && re != null) {
                val first = candles[rs.candleIndex]
                val second = candles[re.candleIndex]
                val priceDiff = re.price - rs.price
                val percent = if (rs.price != 0.0) priceDiff / rs.price * 100.0 else 0.0
                val c = if (priceDiff >= 0) VGreen else VRed
                HorizontalDivider(color = Color.White.copy(alpha = .07f))
                Column(Modifier.padding(top = 10.dp)) {
                    Text("📏 Измерение", color = c, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VMeasure("Изменение", String.format(Locale.US, "%+.2f%%", percent), Modifier.weight(1f), c)
                        VMeasure("Цена", vSignedPrice(priceDiff), Modifier.weight(1f), c)
                        VMeasure("Время", vDuration(abs(second.openTime - first.openTime)), Modifier.weight(1f), Color.White)
                        VMeasure("Свечей", abs(re.candleIndex - rs.candleIndex).toString(), Modifier.weight(1f), Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun VTool'''

    panel_pattern = re.compile(
        r'''@Composable\nprivate fun VInteractiveChartPanel\(candles: List<Candle>, live: Boolean\) \{.*?\n\}\n\n@Composable\nprivate fun VTool''',
        re.S,
    )
    app, count = panel_pattern.subn(smooth_panel, app, count=1)
    if count != 1:
        raise RuntimeError(f"Could not replace chart panel: {count}")

    app = app.replace(
        'Text("Версия 1.1.2 • ⚡ Live Chart", color = VMuted, fontSize = 13.sp)',
        'Text("Версия 1.2.0 • плавный Live Chart", color = VMuted, fontSize = 13.sp)'
    )

    app_path.write_text(app, encoding="utf-8")

socket = socket_path.read_text(encoding="utf-8")
if "// SMOOTH_LIVE_DISPATCH_V12" not in socket:
    socket = socket.replace(
        "    private var dataSeen = false\n",
        "    @Volatile\n    private var dataSeen = false\n"
    )
    old_dispatch = '''                    if (trade != null) {
                        mainHandler.post {
                            if (!stopped) {
                                if (!dataSeen) {
                                    dataSeen = true
                                    reconnectAttempt = 0
                                    onConnectedChanged(true)
                                }
                                onTrade(trade)
                            }
                        }
                    }
'''
    new_dispatch = '''                    if (trade != null && !stopped) {
                        // SMOOTH_LIVE_DISPATCH_V12: keep high-frequency market
                        // events off the Android main thread. The chart batches
                        // them before publishing Compose state.
                        if (!dataSeen) {
                            dataSeen = true
                            reconnectAttempt = 0
                            mainHandler.post { if (!stopped) onConnectedChanged(true) }
                        }
                        onTrade(trade)
                    }
'''
    if old_dispatch not in socket:
        raise RuntimeError("Could not locate LiveChartSocket dispatch block")
    socket = socket.replace(old_dispatch, new_dispatch, 1)
    socket_path.write_text(socket, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 14', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.2.0"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Smooth chart v1.2 source migration applied")
