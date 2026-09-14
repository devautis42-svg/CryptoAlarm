package com.cryptoalarm.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CoinRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val fallbackCoins = listOf(
        "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "TRX", "TON", "AVAX",
        "LINK", "SUI", "DOT", "LTC", "BCH", "HBAR", "XLM", "SHIB", "PEPE", "UNI",
        "APT", "NEAR", "ICP", "FIL", "ETC", "ARB", "OP", "AAVE", "INJ", "ATOM",
        "RUNE", "MKR", "GRT", "ALGO", "VET", "FET", "RENDER", "IMX", "SEI", "TIA"
    )

    suspend fun loadUsdtSymbols(marketType: MarketType = MarketType.SPOT): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = when (marketType) {
                MarketType.SPOT -> "https://api.binance.com/api/v3/exchangeInfo"
                MarketType.FUTURES -> "https://fapi.binance.com/fapi/v1/exchangeInfo"
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CryptoAlarm/0.7")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("Empty response")
                val symbols = JSONObject(body).getJSONArray("symbols")
                buildList {
                    for (i in 0 until symbols.length()) {
                        val item = symbols.getJSONObject(i)
                        val quote = item.optString("quoteAsset")
                        val status = item.optString("status")
                        val base = item.optString("baseAsset")
                        val allowed = when (marketType) {
                            MarketType.SPOT -> item.optBoolean("isSpotTradingAllowed", true)
                            MarketType.FUTURES -> item.optString("contractType") == "PERPETUAL"
                        }
                        if (quote == "USDT" && status == "TRADING" && allowed && base.isNotBlank()) add(base)
                    }
                }.distinct().sorted()
            }
        }.getOrElse { fallbackCoins }
    }

    fun fallback(): List<String> = fallbackCoins
}
