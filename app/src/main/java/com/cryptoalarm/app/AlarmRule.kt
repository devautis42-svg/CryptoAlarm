package com.cryptoalarm.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AlarmRule(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val dropPercent: Double,
    val windowMinutes: Int,
    val enabled: Boolean = true
)

object RuleStore {
    private const val PREFS = "crypto_alarm_prefs"
    private const val RULES = "rules"
    private const val MONITORING = "monitoring"

    fun load(context: android.content.Context): List<AlarmRule> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(RULES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AlarmRule(
                    id = o.getString("id"),
                    symbol = o.getString("symbol"),
                    dropPercent = o.getDouble("dropPercent"),
                    windowMinutes = o.getInt("windowMinutes"),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(context: android.content.Context, rules: List<AlarmRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("symbol", r.symbol); put("dropPercent", r.dropPercent)
                put("windowMinutes", r.windowMinutes); put("enabled", r.enabled)
            })
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(RULES, arr.toString()).apply()
    }

    fun setMonitoring(context: android.content.Context, value: Boolean) =
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(MONITORING, value).apply()

    fun isMonitoring(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(MONITORING, false)
}
