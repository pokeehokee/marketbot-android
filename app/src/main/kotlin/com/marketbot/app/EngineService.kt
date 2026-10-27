package com.marketbot.app

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the engine running while the screen is off. A foreground service with
 * a partial wake lock is what Android allows for this; it still cannot stop a
 * phone that is switched off, out of signal, or force-stopped by its maker's
 * battery manager, which is why the app asks to be exempted from battery
 * optimisation and the strategies reconcile and close orphans on restart.
 */
class EngineService : LifecycleService() {
    private var wake: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopEngine(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting strategies..."), type)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marketbot:engine").apply { setReferenceCounted(false); acquire() }
        }
        val app = application as MarketbotApp
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        if (!started) {
            started = true
            // Strategy loops run off the main thread; the scope still ends with the service.
            app.engine.start(kotlinx.coroutines.CoroutineScope(lifecycleScope.coroutineContext + kotlinx.coroutines.Dispatchers.Default))
            lifecycleScope.launch {
                app.engine.status.collectLatest { st ->
                    val active = st.values.filter { it.mode != Mode.OFF }
                    val live = active.count { it.mode == Mode.LIVE && it.armed }
                    val text = when {
                        active.isEmpty() -> "No strategies on."
                        live > 0 -> "$live LIVE, ${active.size - live} paper"
                        else -> "${active.size} running in paper mode"
                    }
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text))
                }
            }
        }
        return START_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, MarketbotApp.CH_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_engine).setContentTitle("Marketbot").setContentText(text)
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop engine", stop).build()
    }

    private fun stopEngine() {
        val app = application as MarketbotApp
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        app.appScope.launch {
            app.engine.stopAll()
            started = false
        }
        wake?.release(); wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wake?.release(); wake = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.marketbot.app.STOP"
        @Volatile var started = false

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, EngineService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, EngineService::class.java).setAction(ACTION_STOP))
        fun wasRunning(ctx: Context) = ctx.getSharedPreferences("service", Context.MODE_PRIVATE).getBoolean("running", false)
    }
}

/** Restarts the engine after a reboot if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && EngineService.wasRunning(context)) EngineService.start(context)
    }
}
