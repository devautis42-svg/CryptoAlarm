package com.cryptoalarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!RuleStore.isMonitoring(context)) return

        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val serviceIntent = Intent(context, MarketMonitorService::class.java)
                .setAction(MarketMonitorService.ACTION_START)
            ContextCompat.startForegroundService(context, serviceIntent)
            ServiceWatchdog.schedule(context, 15_000L)
        }
    }
}
