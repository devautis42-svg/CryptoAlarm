package com.cryptoalarm.app

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class MarketMonitorService : Service() {
    companion object {
        const val ACTION_START = "cryptoalarm.START"
        const val ACTION_STOP = "cryptoalarm.STOP"
        const val ACTION_SILENCE = "cryptoalarm.SILENCE"
        private const val CHANNEL_MONITOR = "market_monitor"
        private const val CHANNEL_ALARM = "price_alarm"
        private const val NOTIFICATION_MONITOR = 101
        private const val NOTIFICATION_ALARM = 202
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var loopJob: Job? = null
    private var player: MediaPlayer? = null
    private val latched = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMonitoring()
            ACTION_SILENCE -> silenceAlarm()
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        startForeground(NOTIFICATION_MONITOR, monitorNotification("Рынок отслеживается"))
        RuleStore.setMonitoring(this, true)
        if (loopJob?.isActive == true) return

        loopJob = scope.launch {
            while (isActive) {
                val rules = RuleStore.load(this@MarketMonitorService).filter { it.enabled }
                val scanSeconds = RuleStore.getScanIntervalSeconds(this@MarketMonitorService)

                if (rules.isEmpty()) {
                    updateMonitor("Нет активных правил • скан: ${scanSeconds}с")
                } else {
                    rules.groupBy { it.symbol }.forEach { (symbol, symbolRules) ->
                        val maxWindow = symbolRules.maxOf { it.windowMinutes }
                        val closes = fetchMinuteCloses(symbol, maxWindow + 2)
                        if (closes != null && closes.size >= 2) {
                            val current = closes.last()
                            symbolRules.forEach { rule ->
                                val idx = (closes.size - 1 - rule.windowMinutes).coerceAtLeast(0)
                                val old = closes[idx]
                                if (old <= 0.0) return@forEach
                                val change = ((current - old) / old) * 100.0

                                val triggered = when (rule.direction) {
                                    AlertDirection.DROP -> change <= -rule.thresholdPercent
                                    AlertDirection.RISE -> change >= rule.thresholdPercent
                                }
                                val reset = when (rule.direction) {
                                    AlertDirection.DROP -> change > -(rule.thresholdPercent * 0.75)
                                    AlertDirection.RISE -> change < rule.thresholdPercent * 0.75
                                }

                                if (triggered) {
                                    if (latched.add(rule.id)) triggerAlarm(rule, current, change)
                                } else if (reset) {
                                    latched.remove(rule.id)
                                }
                            }
                            updateMonitor("${symbol.removeSuffix("USDT")}: ${formatPrice(current)} • скан: ${scanSeconds}с • правил: ${rules.size}")
                        }
                    }
                }

                delay(scanSeconds.coerceAtLeast(1) * 1000L)
            }
        }
    }

    private fun fetchMinuteCloses(symbol: String, limit: Int): List<Double>? {
        val safeLimit = limit.coerceIn(2, 1000)
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1m&limit=$safeLimit"
        val req = Request.Builder().url(url).header("User-Agent", "CryptoAlarm/0.3").build()
        return runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val arr = JSONArray(body)
                (0 until arr.length()).map { i -> arr.getJSONArray(i).getString(4).toDouble() }
            }
        }.getOrNull()
    }

    private fun triggerAlarm(rule: AlarmRule, price: Double, change: Double) {
        startAlarmSound()
        val coin = rule.symbol.removeSuffix("USDT")
        val rising = rule.direction == AlertDirection.RISE
        val title = if (rising) "📈 $coin растёт!" else "📉 $coin падает!"
        val text = String.format("%+.2f%% за %d мин • цена %s", change, rule.windowMinutes, formatPrice(price))
        val thresholdText = if (rising) "+${rule.thresholdPercent}%" else "-${rule.thresholdPercent}%"

        val silenceIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MarketMonitorService::class.java).setAction(ACTION_SILENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nПорог: $thresholdText"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить звук", silenceIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ALARM, n)
    }

    private fun startAlarmSound() {
        if (player?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@MarketMonitorService, uri)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun silenceAlarm() {
        player?.runCatching { stop(); release() }
        player = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ALARM)
    }

    private fun stopMonitoring() {
        silenceAlarm()
        loopJob?.cancel()
        loopJob = null
        RuleStore.setMonitoring(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Мониторинг рынка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока Crypto Alarm следит за рынком"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Тревоги движения цены", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Срочные уведомления о росте или падении криптовалют"
                enableVibration(true)
                setSound(null, null)
            }
        )
    }

    private fun monitorNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MarketMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Crypto Alarm включён")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Выключить", stop)
            .build()
    }

    private fun updateMonitor(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION_MONITOR,
            monitorNotification(text)
        )
    }

    private fun formatPrice(p: Double): String = when {
        p >= 1000 -> String.format("$%,.0f", p)
        p >= 1 -> String.format("$%.2f", p)
        else -> String.format("$%.5f", p)
    }

    override fun onDestroy() {
        loopJob?.cancel()
        silenceAlarm()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
