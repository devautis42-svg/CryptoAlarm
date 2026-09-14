package com.cryptoalarm.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit


data class LiveTrade(
    val price: Double,
    val quantity: Double,
    val tradeTime: Long
)

class LiveChartSocket(
    private val symbol: String,
    private val marketType: MarketType,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onTrade: (LiveTrade) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var stopped = false
    private var socket: WebSocket? = null
    private var dataSeen = false
    private var reconnectAttempt = 0

    private val reconnectRunnable = Runnable {
        if (!stopped) connect()
    }

    private val noDataWatchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (!dataSeen) {
                socket?.cancel()
                scheduleReconnect(400L)
            } else {
                mainHandler.postDelayed(this, 10_000L)
            }
        }
    }

    fun start() {
        stopped = false
        reconnectAttempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(noDataWatchdog)
        socket?.close(1000, "chart closed")
        socket = null
        mainHandler.post { onConnectedChanged(false) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connect() {
        if (stopped) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(noDataWatchdog)
        dataSeen = false
        mainHandler.post { onConnectedChanged(false) }

        val s = symbol.lowercase()
        val streams = "$s@aggTrade/$s@miniTicker"
        val url = when (marketType) {
            MarketType.SPOT -> {
                if (reconnectAttempt % 2 == 0) {
                    "wss://stream.binance.com:443/stream?streams=$streams"
                } else {
                    "wss://data-stream.binance.vision/stream?streams=$streams"
                }
            }
            MarketType.FUTURES -> "wss://fstream.binance.com/stream?streams=$streams"
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CryptoAlarm/1.1.1")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Do not show LIVE yet. We only mark the stream live after
                // receiving an actual market-data event.
                mainHandler.postDelayed(noDataWatchdog, 6_000L)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val outer = JSONObject(text)
                    val data = if (outer.has("data")) outer.optJSONObject("data") ?: outer else outer
                    val eventType = data.optString("e")

                    val trade = when (eventType) {
                        "aggTrade" -> {
                            val price = data.optString("p").toDoubleOrNull() ?: return
                            val quantity = data.optString("q").toDoubleOrNull() ?: 0.0
                            val eventTime = when {
                                data.has("T") -> data.optLong("T")
                                data.has("E") -> data.optLong("E")
                                else -> System.currentTimeMillis()
                            }
                            LiveTrade(price, quantity, eventTime)
                        }
                        "24hrMiniTicker" -> {
                            val price = data.optString("c").toDoubleOrNull() ?: return
                            val eventTime = data.optLong("E", System.currentTimeMillis())
                            LiveTrade(price, 0.0, eventTime)
                        }
                        else -> null
                    }

                    if (trade != null) {
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
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped) return
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                mainHandler.post {
                    onConnectedChanged(false)
                    reconnectAttempt++
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect(delayMs: Long = 1_000L) {
        if (stopped) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(noDataWatchdog)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }
}

fun chartIntervalMillis(interval: String): Long = when (interval) {
    "1m" -> 60_000L
    "5m" -> 5 * 60_000L
    "15m" -> 15 * 60_000L
    "1h" -> 60 * 60_000L
    "4h" -> 4 * 60 * 60_000L
    "1d" -> 24 * 60 * 60_000L
    "1w" -> 7 * 24 * 60 * 60_000L
    else -> 60_000L
}

fun applyLiveTradeToCandles(
    current: List<Candle>,
    trade: LiveTrade,
    intervalMillis: Long,
    maxCandles: Int = 500
): List<Candle> {
    if (current.isEmpty()) return current

    val result = current.toMutableList()
    val last = result.last()

    if (trade.tradeTime < last.openTime) return current

    if (trade.tradeTime < last.openTime + intervalMillis) {
        result[result.lastIndex] = last.copy(
            high = maxOf(last.high, trade.price),
            low = minOf(last.low, trade.price),
            close = trade.price,
            volume = last.volume + trade.quantity
        )
    } else {
        var nextOpenTime = last.openTime + intervalMillis
        var previousClose = last.close

        while (trade.tradeTime >= nextOpenTime + intervalMillis) {
            result.add(
                Candle(
                    openTime = nextOpenTime,
                    open = previousClose,
                    high = previousClose,
                    low = previousClose,
                    close = previousClose,
                    volume = 0.0
                )
            )
            nextOpenTime += intervalMillis
            previousClose = result.last().close
        }

        result.add(
            Candle(
                openTime = nextOpenTime,
                open = previousClose,
                high = maxOf(previousClose, trade.price),
                low = minOf(previousClose, trade.price),
                close = trade.price,
                volume = trade.quantity
            )
        )
    }

    return if (result.size > maxCandles) result.takeLast(maxCandles) else result
}
