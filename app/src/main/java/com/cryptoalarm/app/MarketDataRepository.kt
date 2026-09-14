package com.cryptoalarm.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MarketTicker(
    val symbol: String,
    val price: Double,
    val changePercent: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVolume24h: Double
)

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

object MarketDataRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun loadPopularTickers(symbols: List<String>): List<MarketTicker> = withContext(Dispatchers.IO) {
        runCatching {
            val wanted = symbols.map { if (it.endsWith("USDT")) it else "${it}USDT" }.toSet()
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/ticker/24hr")
                .header("User-Agent", "CryptoAlarm/0.6")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("Empty response")
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val symbol = item.optString("symbol")
                        if (symbol in wanted) parseTicker(item)?.let(::add)
                    }
                }.sortedBy { wanted.indexOf(it.symbol) }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun loadTicker(symbol: String): MarketTicker? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol")
                .header("User-Agent", "CryptoAlarm/0.6")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parseTicker(JSONObject(body))
            }
        }.getOrNull()
    }

    suspend fun loadCandles(symbol: String, interval: String, limit: Int = 120): List<Candle> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(20, 500)
            val request = Request.Builder()
                .url("https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=$safeLimit")
                .header("User-Agent", "CryptoAlarm/0.6")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("Empty response")
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONArray(i)
                        add(
                            Candle(
                                openTime = c.getLong(0),
                                open = c.getString(1).toDouble(),
                                high = c.getString(2).toDouble(),
                                low = c.getString(3).toDouble(),
                                close = c.getString(4).toDouble(),
                                volume = c.getString(5).toDouble()
                            )
                        )
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun parseTicker(item: JSONObject): MarketTicker? {
        val symbol = item.optString("symbol")
        val price = item.optString("lastPrice").toDoubleOrNull() ?: return null
        return MarketTicker(
            symbol = symbol,
            price = price,
            changePercent = item.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
            high24h = item.optString("highPrice").toDoubleOrNull() ?: price,
            low24h = item.optString("lowPrice").toDoubleOrNull() ?: price,
            quoteVolume24h = item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        )
    }
}
