from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
app_path = root / "app/src/main/java/com/cryptoalarm/app/AppV11.kt"
build_path = root / "app/build.gradle.kts"

app = app_path.read_text(encoding="utf-8")

state_anchor = '    var priceScale by remember { mutableFloatStateOf(1f) }\n'
if state_anchor not in app:
    raise RuntimeError("Could not locate priceScale state")
if 'priceOffsetFraction' not in app:
    app = app.replace(state_anchor, state_anchor + '    var priceOffsetFraction by remember { mutableFloatStateOf(0f) }\n', 1)

old_view = '''    LaunchedEffect(total) {
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
'''

new_view = '''    fun futureAllowance(forSpan: Float): Float =
        (forSpan * .35f).coerceIn(8f, 80f)

    LaunchedEffect(total) {
        val oldTotal = previousTotal.coerceAtLeast(1)
        val currentSpan = viewportSpan.coerceIn(minSpan, maxSpan)
        val wasFollowingLatest = viewportEnd >= oldTotal.toFloat() - 0.35f
        val delta = (total - oldTotal).toFloat()

        viewportSpan = currentSpan
        viewportEnd = if (wasFollowingLatest) viewportEnd + delta else viewportEnd

        val maxEnd = total.toFloat() + futureAllowance(currentSpan)
        viewportEnd = viewportEnd.coerceIn(currentSpan, maxEnd)
        previousTotal = total
    }

    val span = viewportSpan.coerceIn(minSpan, maxSpan)
    val maxEnd = total.toFloat() + futureAllowance(span)
    val end = viewportEnd.coerceIn(span, maxEnd)
    val start = (end - span).coerceAtLeast(0f)
    val firstIndex = floor(start.toDouble()).toInt().coerceIn(0, total - 1)
    val dataEnd = minOf(end, total.toFloat())
    val lastExclusive = ceil(dataEnd.toDouble()).toInt().coerceIn(firstIndex + 1, total)
    val visible = candles.subList(firstIndex, lastExclusive)
'''

if old_view not in app:
    raise RuntimeError("Could not locate continuous viewport block")
app = app.replace(old_view, new_view, 1)

old_price = '''    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val autoAxisMax = maxPrice + pricePadding
    val autoAxisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val autoRange = (autoAxisMax - autoAxisMin).coerceAtLeast(autoAxisMax * .0001)
    val safePriceScale = priceScale.coerceIn(.25f, 8f)
    val axisCenter = (autoAxisMax + autoAxisMin) / 2.0
    val scaledRange = autoRange / safePriceScale.toDouble()
    val rawAxisMin = axisCenter - scaledRange / 2.0
    val axisMin = rawAxisMin.coerceAtLeast(0.0)
    val axisMax = if (rawAxisMin >= 0.0) axisCenter + scaledRange / 2.0 else scaledRange
    val priceRange = (axisMax - axisMin).coerceAtLeast(axisMax * .0001)
'''

new_price = '''    val selected = selectedIndex?.let { candles.getOrNull(it) }
    val maxPrice = visible.maxOfOrNull { it.high } ?: 1.0
    val minPrice = visible.minOfOrNull { it.low } ?: 0.0
    val pricePadding = ((maxPrice - minPrice) * .06).coerceAtLeast(maxPrice * .0004)
    val autoAxisMax = maxPrice + pricePadding
    val autoAxisMin = (minPrice - pricePadding).coerceAtLeast(0.0)
    val autoRange = (autoAxisMax - autoAxisMin).coerceAtLeast(autoAxisMax * .0001)
    val safePriceScale = priceScale.coerceIn(.25f, 8f)
    val safePriceOffset = priceOffsetFraction.coerceIn(-6f, 6f)
    val autoCenter = (autoAxisMax + autoAxisMin) / 2.0
    val axisCenter = autoCenter + autoRange * safePriceOffset.toDouble()
    val scaledRange = autoRange / safePriceScale.toDouble()
    val rawAxisMin = axisCenter - scaledRange / 2.0
    val rawAxisMax = axisCenter + scaledRange / 2.0
    val shiftUp = if (rawAxisMin < 0.0) -rawAxisMin else 0.0
    val axisMin = rawAxisMin + shiftUp
    val axisMax = rawAxisMax + shiftUp
    val priceRange = (axisMax - axisMin).coerceAtLeast(maxOf(axisMax, 1.0) * .0001)
'''

if old_price not in app:
    raise RuntimeError("Could not locate v1.3.1 price range block")
app = app.replace(old_price, new_price, 1)

old_clamp = '''    fun clampViewport(rawStart: Float, rawSpan: Float) {
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
'''

new_clamp = '''    fun clampViewport(rawStart: Float, rawSpan: Float) {
        val safeSpan = rawSpan.coerceIn(minSpan, maxSpan)
        val maxStart = (total.toFloat() + futureAllowance(safeSpan) - safeSpan).coerceAtLeast(0f)
        val safeStart = rawStart.coerceIn(0f, maxStart)
        viewportSpan = safeSpan
        viewportEnd = safeStart + safeSpan
    }

    fun resetToLatest() {
        val currentSpan = viewportSpan.coerceIn(minSpan, maxSpan)
        val rightSpace = (currentSpan * .18f).coerceIn(4f, 24f)
            .coerceAtMost(futureAllowance(currentSpan))
        viewportEnd = total.toFloat() + rightSpace
        priceOffsetFraction = 0f
        selectedIndex = null
    }
'''

if old_clamp not in app:
    raise RuntimeError("Could not locate viewport clamp/reset block")
app = app.replace(old_clamp, new_clamp, 1)

app = app.replace(
    '"≈${span.roundToInt()} свечей • цена ×${String.format(Locale.US, "%.2f", safePriceScale)}"',
    '"≈${span.roundToInt()} свечей • свободный pan • цена ×${String.format(Locale.US, "%.2f", safePriceScale)}"',
    1,
)

old_gesture = '''                        .pointerInput(total) {
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
'''

new_gesture = '''                        .pointerInput(total, safePriceScale) {
                            detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                                val plotWidth = size.width * .87f
                                val plotHeight = size.height * .82f
                                if (plotWidth <= 1f || plotHeight <= 1f) return@detectTransformGestures

                                val oldSpan = viewportSpan.coerceIn(minSpan, maxSpan)
                                val oldMaxEnd = total.toFloat() + futureAllowance(oldSpan)
                                val oldEnd = viewportEnd.coerceIn(oldSpan, oldMaxEnd)
                                val oldStart = oldEnd - oldSpan
                                val focalFraction = (centroid.x.coerceIn(0f, plotWidth) / plotWidth)
                                    .coerceIn(0f, 1f)
                                val focalCandle = oldStart + focalFraction * oldSpan

                                val safeZoom = zoom.coerceIn(.75f, 1.35f)
                                val newSpan = (oldSpan / safeZoom).coerceIn(minSpan, maxSpan)
                                var newStart = focalCandle - focalFraction * newSpan

                                newStart -= (pan.x / plotWidth) * newSpan
                                clampViewport(newStart, newSpan)

                                if (abs(pan.y) > .01f) {
                                    val priceShift = (pan.y / plotHeight) / safePriceScale.coerceAtLeast(.25f)
                                    priceOffsetFraction = (priceOffsetFraction + priceShift).coerceIn(-6f, 6f)
                                }

                                selectedIndex = null
                            }
                        }
'''

if old_gesture not in app:
    raise RuntimeError("Could not locate chart transform gesture")
app = app.replace(old_gesture, new_gesture, 1)

app = app.replace(
    '.pointerInput(total, rulerMode, safePriceScale) {',
    '.pointerInput(total, rulerMode, safePriceScale, safePriceOffset) {',
    1,
)

old_local_center = '''                                        val localCenter = (localAutoMax + localAutoMin) / 2.0
                                        val localScaledRange = localAutoRange / safePriceScale.toDouble()
                                        val localRawMin = localCenter - localScaledRange / 2.0
                                        val localAxisMin = localRawMin.coerceAtLeast(0.0)
                                        val localAxisMax = if (localRawMin >= 0.0) localCenter + localScaledRange / 2.0 else localScaledRange
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(localAxisMax * .0001)
'''

new_local_center = '''                                        val localAutoCenter = (localAutoMax + localAutoMin) / 2.0
                                        val localCenter = localAutoCenter + localAutoRange * safePriceOffset.toDouble()
                                        val localScaledRange = localAutoRange / safePriceScale.toDouble()
                                        val localRawMin = localCenter - localScaledRange / 2.0
                                        val localRawMax = localCenter + localScaledRange / 2.0
                                        val localShiftUp = if (localRawMin < 0.0) -localRawMin else 0.0
                                        val localAxisMin = localRawMin + localShiftUp
                                        val localAxisMax = localRawMax + localShiftUp
                                        val localRange = (localAxisMax - localAxisMin).coerceAtLeast(maxOf(localAxisMax, 1.0) * .0001)
'''

if old_local_center not in app:
    raise RuntimeError("Could not locate ruler scaled price center")
app = app.replace(old_local_center, new_local_center, 1)

app = app.replace(
    '''                                onDoubleTap = {
                                    priceScale = 1f
                                    selectedIndex = null
                                }
''',
    '''                                onDoubleTap = {
                                    priceScale = 1f
                                    priceOffsetFraction = 0f
                                    selectedIndex = null
                                }
''',
    1,
)

app = app.replace(
    'Text("Версия 1.3.2 • мелодии + масштаб цены", color = VMuted, fontSize = 13.sp)',
    'Text("Версия 1.3.3 • свободный график TradingView", color = VMuted, fontSize = 13.sp)',
    1,
)

app_path.write_text(app, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = re.sub(r'versionCode = \d+', 'versionCode = 27', build, count=1)
build = re.sub(r'versionName = "[^"]+"', 'versionName = "1.3.3"', build, count=1)
build_path.write_text(build, encoding="utf-8")

print("Applied Crypto Alarm v1.3.3 free TradingView-style chart pan")
