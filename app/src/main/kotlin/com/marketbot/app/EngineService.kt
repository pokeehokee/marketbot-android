package com.marketbot.app

import android.app.AlarmManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Keeps the engine alive while the screen is off.
 *
 * The CPU is held awake (a partial wake lock) only while a strategy that
 * re-quotes every few seconds is on, i.e. deep-ITM or reward farming. When only
 * hourly strategies run, the wake lock is released and an OS alarm wakes the
 * phone for each pass: the difference between a few percent of battery a day and
 * keeping the phone awake around the clock.
 */
class EngineService : LifecycleService() {
    private var wake: PowerManager.WakeLock? = null
    private var engineScope: CoroutineScope? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopEngine(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting strategies..."), type)
        val app = application as MarketbotApp
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        if (engineScope == null) {
            // Strategy loops run off the main thread; the scope still ends with the service.
            val scope = CoroutineScope(lifecycleScope.coroutineContext + Dispatchers.Default)
            engineScope = scope
            app.engine.start(scope)
            lifecycleScope.launch {
                app.engine.status.collectLatest { st ->
                    val active = st.values.filter { it.mode != Mode.OFF }
                    val live = active.count { it.mode == Mode.LIVE && it.armed }
                    val text = when {
                        active.isEmpty() -> "No strategies on."
                        live > 0 -> "$live live, ${active.size - live} paper"
                        else -> "${active.size} running in paper mode"
                    }
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text))
                }
            }
            lifecycleScope.launch { managePower(app) }
        }
        return START_STICKY
    }

    /** Re-evaluates every few seconds whether to hold the CPU or hand over to an alarm. */
    private suspend fun managePower(app: MarketbotApp) {
        while (lifecycleScope.isActive) {
            val continuous = app.engine.needsContinuousWake()
            if (continuous) {
                holdWake(true)
                Alarms.cancel(this)
            } else {
                holdWake(false)
                app.engine.nextWakeAt()?.let { Alarms.schedule(this, it) }
            }
            if (app.engine.busy.value == 0) AlarmReceiver.releasePassLock()
            delay(5_000)
        }
    }

    private fun holdWake(on: Boolean) {
        if (on && wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marketbot:engine").apply { setReferenceCounted(false); acquire() }
        } else if (!on && wake != null) {
            wake?.release(); wake = null
        }
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
        Alarms.cancel(this)
        app.appScope.launch { app.engine.stopAll() }
        engineScope = null
        holdWake(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        holdWake(false)
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.marketbot.app.STOP"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, EngineService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, EngineService::class.java).setAction(ACTION_STOP))
        fun wasRunning(ctx: Context) = ctx.getSharedPreferences("service", Context.MODE_PRIVATE).getBoolean("running", false)
    }
}

/** OS alarms that wake the phone for the next scheduled pass. */
object Alarms {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Exact when the user allowed exact alarms, otherwise Android may batch it by a few minutes. */
    fun schedule(ctx: Context, at: Instant) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = at.toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending(ctx))
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))

    fun exactAllowed(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

/**
 * Fired by the alarm: holds the CPU (at most 10 minutes) and pokes the engine,
 * whose loops then run whatever pass is due. The service releases the lock once
 * no pass is in progress.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        synchronized(AlarmReceiver) {
            if (passLock?.isHeld != true) passLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marketbot:pass").apply { setReferenceCounted(false); acquire(10 * 60_000L) }
            heldSince = System.currentTimeMillis()
        }
        val app = context.applicationContext as MarketbotApp
        if (!app.engine.running.value && EngineService.wasRunning(context)) EngineService.start(context)
        app.engine.poke()
    }

    companion object {
        private var passLock: PowerManager.WakeLock? = null
        private var heldSince = 0L

        /** Release the alarm's wake lock once the poked passes have had time to start and finish. */
        fun releasePassLock() = synchronized(AlarmReceiver) {
            if (passLock?.isHeld == true && System.currentTimeMillis() - heldSince > 20_000) { passLock?.release(); passLock = null }
        }
    }
}

/** Restarts the engine after a reboot if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && EngineService.wasRunning(context)) EngineService.start(context)
    }
}
