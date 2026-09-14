package com.cryptoalarm.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AlertDirection {
    DROP,
    RISE
}

data class AlarmRule(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val thresholdPercent: Double,
    val windowMinutes: Int,
    val direction: AlertDirection = AlertDirection.DROP,
    val enabled: Boolean = true
)

object RuleStore {
    private const val PREFS = "crypto_alarm_prefs"
    private const val RULES = "rules"
    private const val MONITORING = "monitoring"
    private const val SCAN_INTERVAL_SECONDS = "scan_interval_seconds"

    fun load(context: android.content.Context): List<AlarmRule> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(RULES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val percent = when {
                    o.has("thresholdPercent") -> o.getDouble("thresholdPercent")
                    o.has("dropPercent") -> o.getDouble("dropPercent")
                    else -> 1.0
                }
                AlarmRule(
                    id = o.getString("id"),
                    symbol = o.getString("symbol"),
                    thresholdPercent = percent,
                    windowMinutes = o.getInt("windowMinutes"),
                    direction = runCatching {
                        AlertDirection.valueOf(o.optString("direction", AlertDirection.DROP.name))
                    }.getOrDefault(AlertDirection.DROP),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: android.content.Context, rules: List<AlarmRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("symbol", r.symbol)
                put("thresholdPercent", r.thresholdPercent)
                put("windowMinutes", r.windowMinutes)
                put("direction", r.direction.name)
                put("enabled", r.enabled)
            })
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(RULES, arr.toString()).apply()
    }

    fun setMonitoring(context: android.content.Context, value: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(MONITORING, value).apply()

    fun isMonitoring(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(MONITORING, false)

    fun setScanIntervalSeconds(context: android.content.Context, seconds: Int) =
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt(SCAN_INTERVAL_SECONDS, seconds.coerceAtLeast(1))
            .apply()

    fun getScanIntervalSeconds(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, 0).getInt(SCAN_INTERVAL_SECONDS, 15).coerceAtLeast(1)
}
