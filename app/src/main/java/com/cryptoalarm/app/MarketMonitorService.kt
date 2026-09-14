package com.cryptoalarm.app

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    private data class PricePoint(val timestamp: Long, val price: Double)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var configJob: Job? = null
    private var fallbackJob: Job? = null
    private var socket: WebSocket? = null
    private var socketGeneration = 0
    private val reconnectScheduled = AtomicBoolean(false)
    private val lastStatusUpdate = AtomicLong(0L)
    private val history = ConcurrentHashMap<String, ArrayDeque<PricePoint>>()
    private val maxWindowBySymbol = ConcurrentHashMap<String, Int>()
    private val latched = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var activeRules: List<AlarmRule> = emptyList()
    @Volatile private var activeSymbols: Set<String> = emptySet()
    @Volatile private var webSocketConnected = false

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        prepareAlarmPlayer()
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
        startForeground(NOTIFICATION_MONITOR, monitorNotification("Подключение к Live WebSocket…"))
        RuleStore.setMonitoring(this, true)
        acquireWakeLock()
        prepareAlarmPlayer()

        if (configJob?.isActive == true) return

        configJob = scope.launch {
            while (isActive) {
                refreshRulesAndConnection()
                delay(1_000)
            }
        }

        fallbackJob = scope.launch {
            while (isActive) {
                val fallbackSeconds = RuleStore.getScanIntervalSeconds(this@MarketMonitorService).coerceAtLeast(1)
                delay(fallbackSeconds * 1000L)
                if (!webSocketConnected && activeRules.isNotEmpty()) {
                    pollRestFallback()
                }
            }
        }
    }

    private suspend fun refreshRulesAndConnection() {
        val rules = RuleStore.load(this).filter { it.enabled }
        val symbols = rules.map { it.symbol }.toSet()
        activeRules = rules

        val newMaxWindows = rules.groupBy { it.symbol }
            .mapValues { (_, symbolRules) -> symbolRules.maxOf { it.windowMinutes } }

        newMaxWindows.forEach { (symbol, maxWindow) ->
            val oldWindow = maxWindowBySymbol[symbol] ?: 0
            if (!history.containsKey(symbol) || maxWindow > oldWindow) {
                seedHistory(symbol, maxWindow)
            }
            maxWindowBySymbol[symbol] = maxWindow
        }

        maxWindowBySymbol.keys.filterNot { it in symbols }.forEach { symbol ->
            maxWindowBySymbol.remove(symbol)
            history.remove(symbol)
        }

        if (symbols != activeSymbols) {
            activeSymbols = symbols
            reconnectWebSocket(symbols)
        }

        if (rules.isEmpty()) {
            updateMonitor("Нет активных правил")
        }
    }

    private fun reconnectWebSocket(symbols: Set<String>) {
        socketGeneration++
        val generation = socketGeneration
        reconnectScheduled.set(false)
        webSocketConnected = false
        socket?.cancel()
        socket = null

        if (symbols.isEmpty()) return

        val streams = symbols.sorted().joinToString("/") { "${it.lowercase()}@aggTrade" }
        val url = "wss://stream.binance.com:9443/stream?streams=$streams"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CryptoAlarm/0.5")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != socketGeneration) return
                webSocketConnected = true
                reconnectScheduled.set(false)
                updateMonitor("⚡ Live WebSocket • ${symbols.size} монет • реакция по сделкам")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != socketGeneration) return
                runCatching {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root
                    val symbol = data.optString("s")
                    val price = data.optString("p").toDoubleOrNull() ?: return
                    val eventTime = data.optLong("E", System.currentTimeMillis())
                    if (symbol.isNotBlank()) processLivePrice(symbol, price, eventTime)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration) return
                webSocketConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration) return
                webSocketConnected = false
                scheduleReconnect(generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration) return
                webSocketConnected = false
                updateMonitor("Live временно недоступен • включён REST-резерв")
                scheduleReconnect(generation)
            }
        })
    }

    private fun scheduleReconnect(generation: Int) {
        if (!RuleStore.isMonitoring(this) || activeSymbols.isEmpty()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(1_500)
            reconnectScheduled.set(false)
            if (generation == socketGeneration && RuleStore.isMonitoring(this@MarketMonitorService)) {
                reconnectWebSocket(activeSymbols)
            }
        }
    }

    private fun processLivePrice(symbol: String, price: Double, eventTime: Long) {
        val symbolRules = activeRules.filter { it.symbol == symbol }
        if (symbolRules.isEmpty()) return

        val maxWindow = symbolRules.maxOf { it.windowMinutes }
        val deque = history.getOrPut(symbol) { ArrayDeque() }
        synchronized(deque) {
            val last = deque.peekLast()
            if (last == null || eventTime - last.timestamp >= 1_000L) {
                deque.addLast(PricePoint(eventTime, price))
            }
            val cutoff = eventTime - (maxWindow + 3L) * 60_000L
            while (deque.isNotEmpty() && deque.peekFirst().timestamp < cutoff) deque.removeFirst()
        }

        symbolRules.forEach { rule ->
            val baseline = findBaseline(symbol, eventTime - rule.windowMinutes * 60_000L) ?: return@forEach
            evaluateRule(rule, price, baseline, eventTime)
        }

        val now = System.currentTimeMillis()
        if (now - lastStatusUpdate.get() >= 5_000L && lastStatusUpdate.compareAndSet(lastStatusUpdate.get(), now)) {
            updateMonitor("⚡ Live • ${symbol.removeSuffix("USDT")}: ${formatPrice(price)} • правил: ${activeRules.size}")
        }
    }

    private fun findBaseline(symbol: String, targetTime: Long): Double? {
        val deque = history[symbol] ?: return null
        synchronized(deque) {
            val iterator = deque.descendingIterator()
            while (iterator.hasNext()) {
                val point = iterator.next()
                if (point.timestamp <= targetTime) return point.price
            }
        }
        return null
    }

    private fun evaluateRule(rule: AlarmRule, current: Double, baseline: Double, eventTime: Long) {
        if (baseline <= 0.0) return
        val change = ((current - baseline) / baseline) * 100.0
        val triggered = when (rule.direction) {
            AlertDirection.DROP -> change <= -rule.thresholdPercent
            AlertDirection.RISE -> change >= rule.thresholdPercent
        }
        val reset = when (rule.direction) {
            AlertDirection.DROP -> change > -(rule.thresholdPercent * 0.75)
            AlertDirection.RISE -> change < rule.thresholdPercent * 0.75
        }

        if (triggered) {
            if (latched.add(rule.id)) triggerAlarm(rule, current, change, eventTime)
        } else if (reset) {
            latched.remove(rule.id)
        }
    }

    private suspend fun seedHistory(symbol: String, maxWindow: Int) = withContext(Dispatchers.IO) {
        val points = fetchHistoryPoints(symbol, maxWindow + 2) ?: return@withContext
        val deque = history.getOrPut(symbol) { ArrayDeque() }
        synchronized(deque) {
            deque.clear()
            points.forEach { deque.addLast(it) }
        }
    }

    private fun fetchHistoryPoints(symbol: String, limit: Int): List<PricePoint>? {
        val safeLimit = limit.coerceIn(2, 1000)
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1m&limit=$safeLimit"
        val req = Request.Builder().url(url).header("User-Agent", "CryptoAlarm/0.5").build()
        return runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val arr = JSONArray(body)
                val now = System.currentTimeMillis()
                buildList {
                    for (i in 0 until arr.length()) {
                        val candle = arr.getJSONArray(i)
                        val closeTime = candle.getLong(6)
                        val close = candle.getString(4).toDouble()
                        if (closeTime <= now) add(PricePoint(closeTime, close))
                    }
                }
            }
        }.getOrNull()
    }

    private fun pollRestFallback() {
        val rules = activeRules
        if (rules.isEmpty()) return
        rules.groupBy { it.symbol }.forEach { (symbol, symbolRules) ->
            val maxWindow = symbolRules.maxOf { it.windowMinutes }
            val points = fetchHistoryPoints(symbol, maxWindow + 2) ?: return@forEach
            if (points.size < 2) return@forEach
            val current = points.last().price
            val now = System.currentTimeMillis()

            val deque = history.getOrPut(symbol) { ArrayDeque() }
            synchronized(deque) {
                deque.clear()
                points.forEach { deque.addLast(it) }
                deque.addLast(PricePoint(now, current))
            }

            symbolRules.forEach { rule ->
                val baseline = findBaseline(symbol, now - rule.windowMinutes * 60_000L) ?: return@forEach
                evaluateRule(rule, current, baseline, now)
            }
        }
        updateMonitor("REST-резерв • переподключаю Live…")
    }

    private fun triggerAlarm(rule: AlarmRule, price: Double, change: Double, eventTime: Long) {
        startAlarmSound()
        val coin = rule.symbol.removeSuffix("USDT")
        val rising = rule.direction == AlertDirection.RISE
        val title = if (rising) "📈 $coin растёт!" else "📉 $coin падает!"
        val text = String.format("%+.2f%% за %d мин • цена %s", change, rule.windowMinutes, formatPrice(price))
        val thresholdText = if (rising) "+${rule.thresholdPercent}%" else "-${rule.thresholdPercent}%"
        val latency = (System.currentTimeMillis() - eventTime).coerceAtLeast(0)

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
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nПорог: $thresholdText\nLive-задержка обработки: ~${latency} мс"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить звук", silenceIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ALARM, n)
    }

    private fun prepareAlarmPlayer() {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@MarketMonitorService, uri)
                isLooping = true
                prepare()
            }
        }.getOrNull()
    }

    private fun startAlarmSound() {
        if (player == null) prepareAlarmPlayer()
        player?.let { p ->
            runCatching {
                if (!p.isPlaying) {
                    p.seekTo(0)
                    p.start()
                }
            }
        }
    }

    private fun silenceAlarm() {
        player?.let { p ->
            runCatching {
                if (p.isPlaying) p.pause()
                p.seekTo(0)
            }
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ALARM)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptoAlarm::LiveMonitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun stopMonitoring() {
        silenceAlarm()
        configJob?.cancel()
        fallbackJob?.cancel()
        configJob = null
        fallbackJob = null
        socketGeneration++
        socket?.cancel()
        socket = null
        webSocketConnected = false
        RuleStore.setMonitoring(this, false)
        releaseWakeLock()
        player?.runCatching { release() }
        player = null
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
            .setContentTitle("Crypto Alarm ⚡ Live")
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
        else -> String.format("$%.6f", p)
    }

    override fun onDestroy() {
        configJob?.cancel()
        fallbackJob?.cancel()
        socketGeneration++
        socket?.cancel()
        socket = null
        releaseWakeLock()
        player?.runCatching { release() }
        player = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
