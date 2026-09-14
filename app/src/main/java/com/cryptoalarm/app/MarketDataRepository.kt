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
    val quoteVolume24h: Double,
    val marketType: MarketType = MarketType.SPOT
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

    suspend fun loadPopularTickers(symbols: List<String>, marketType: MarketType): List<MarketTicker> = withContext(Dispatchers.IO) {
        runCatching {
            val wanted = symbols.map { if (it.endsWith("USDT")) it else "${it}USDT" }.toSet()
            val url = when (marketType) {
                MarketType.SPOT -> "https://api.binance.com/api/v3/ticker/24hr"
                MarketType.FUTURES -> "https://fapi.binance.com/fapi/v1/ticker/24hr"
            }
            val request = Request.Builder().url(url).header("User-Agent", "CryptoAlarm/0.7").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("Empty response")
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val symbol = item.optString("symbol")
                        if (symbol in wanted) parseTicker(item, marketType)?.let(::add)
                    }
                }.sortedBy { wanted.indexOf(it.symbol) }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun loadTicker(symbol: String, marketType: MarketType): MarketTicker? = withContext(Dispatchers.IO) {
        runCatching {
            val base = when (marketType) {
                MarketType.SPOT -> "https://api.binance.com/api/v3/ticker/24hr"
                MarketType.FUTURES -> "https://fapi.binance.com/fapi/v1/ticker/24hr"
            }
            val request = Request.Builder().url("$base?symbol=$symbol").header("User-Agent", "CryptoAlarm/0.7").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parseTicker(JSONObject(body), marketType)
            }
        }.getOrNull()
    }

    suspend fun loadCandles(symbol: String, interval: String, limit: Int = 120, marketType: MarketType): List<Candle> = withContext(Dispatchers.IO) {
        runCatching {
            val safeLimit = limit.coerceIn(20, 500)
            val base = when (marketType) {
                MarketType.SPOT -> "https://api.binance.com/api/v3/klines"
                MarketType.FUTURES -> "https://fapi.binance.com/fapi/v1/klines"
            }
            val request = Request.Builder()
                .url("$base?symbol=$symbol&interval=$interval&limit=$safeLimit")
                .header("User-Agent", "CryptoAlarm/0.7")
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

    private fun parseTicker(item: JSONObject, marketType: MarketType): MarketTicker? {
        val symbol = item.optString("symbol")
        val price = item.optString("lastPrice").toDoubleOrNull() ?: return null
        return MarketTicker(
            symbol = symbol,
            price = price,
            changePercent = item.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
            high24h = item.optString("highPrice").toDoubleOrNull() ?: price,
            low24h = item.optString("lowPrice").toDoubleOrNull() ?: price,
            quoteVolume24h = item.optString("quoteVolume").toDoubleOrNull() ?: 0.0,
            marketType = marketType
        )
    }
}
