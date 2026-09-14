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

    private val reconnectRunnable = Runnable {
        if (!stopped) connect()
    }

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        socket?.close(1000, "chart closed")
        socket = null
        mainHandler.post { onConnectedChanged(false) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun connect() {
        if (stopped) return
        mainHandler.removeCallbacks(reconnectRunnable)

        val stream = "${symbol.lowercase()}@aggTrade"
        val url = when (marketType) {
            MarketType.SPOT -> "wss://stream.binance.com:9443/ws/$stream"
            MarketType.FUTURES -> "wss://fstream.binance.com/ws/$stream"
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CryptoAlarm/1.1")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post { onConnectedChanged(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val price = json.optString("p").toDoubleOrNull() ?: return
                    val quantity = json.optString("q").toDoubleOrNull() ?: 0.0
                    val tradeTime = when {
                        json.has("T") -> json.optLong("T")
                        json.has("E") -> json.optLong("E")
                        else -> System.currentTimeMillis()
                    }
                    val trade = LiveTrade(price, quantity, tradeTime)
                    mainHandler.post {
                        if (!stopped) onTrade(trade)
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
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                mainHandler.post {
                    onConnectedChanged(false)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 1_200L)
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
    var last = result.last()

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
