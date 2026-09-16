package com.cryptoalarm.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

object ServiceWatchdog {
    private const val ACTION_WATCHDOG = "cryptoalarm.WATCHDOG"
    private const val REQUEST_CODE = 46021
    private const val DEFAULT_DELAY_MS = 10 * 60 * 1000L

    fun schedule(context: Context, delayMs: Long = DEFAULT_DELAY_MS) {
        if (!RuleStore.isMonitoring(context)) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = watchdogPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(5_000L)

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() ->
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                else ->
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
            }
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(watchdogPendingIntent(context))
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ServiceWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!RuleStore.isMonitoring(context)) {
            ServiceWatchdog.cancel(context)
            return
        }

        runCatching {
            val serviceIntent = Intent(context, MarketMonitorService::class.java)
                .setAction(MarketMonitorService.ACTION_START)
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        ServiceWatchdog.schedule(context)
    }
}
