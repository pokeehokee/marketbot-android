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
import com.marketbot.core.store.LogEvent
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.flow.combine
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
    private var lastPrune = System.currentTimeMillis()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopEngine(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting strategies..."), type)
        val app = application as MarketbotApp
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("running", true).apply()
        if (engineScope == null) {
            val bootStart = intent?.getBooleanExtra(EXTRA_BOOT, false) == true
            Health.markStarted(this)
            // A heartbeat left behind means the engine died without a clean stop.
            val gap = Health.gapOnStart(this)
            app.appScope.launch {
                if (bootStart) app.note(LogEvent.Level.WARN, "boot", "Phone restarted: the engine was started again automatically.")
                gap?.let { (from, to) ->
                    val mins = java.time.Duration.between(from, to).toMinutes()
                    app.note(LogEvent.Level.WARN, "gap", "The engine was not running for ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"} " +
                        "(last seen ${from.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}). Android or the phone's battery manager stopped it.")
                }
            }
            // Strategy loops run off the main thread; the scope still ends with the service.
            val scope = CoroutineScope(lifecycleScope.coroutineContext + Dispatchers.Default)
            engineScope = scope
            app.engine.start(scope)
            lifecycleScope.launch {
                combine(app.engine.status, app.engine.globalKill) { st, k -> st to k }.collectLatest { (st, killed) ->
                    val active = st.values.filter { it.mode != Mode.OFF }
                    val live = active.filter { it.mode == Mode.LIVE && it.armed }
                    val title = when {
                        killed -> "Kill switch on"
                        live.isNotEmpty() -> "${live.size} live, ${active.size - live.size} paper"
                        active.isEmpty() -> "No strategies on"
                        else -> "${active.size} in paper mode"
                    }
                    val next = active.mapNotNull { it.nextRunAt }.minOrNull()
                    val lines = active.map { s0 -> "${app.engine.strategies.firstOrNull { it.id == s0.id }?.name ?: s0.id}: ${if (s0.mode == Mode.LIVE && s0.armed) "LIVE" else "paper"}" +
                        (s0.lastSummary?.let { " - $it" } ?: "") }
                    Surfaces.refresh(this@EngineService)
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID,
                        notification(title, next?.let { "Next pass ${it.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}" } ?: "Idle", lines, live.isNotEmpty() && !killed))
                }
            }
            lifecycleScope.launch { managePower(app) }
        }
        return START_STICKY
    }

    /** Re-evaluates every few seconds whether to hold the CPU or hand over to an alarm. */
    private var lastContractTry = 0L
    private var contractJob: kotlinx.coroutines.Job? = null
    private var lastSyncTry = 0L
    private var syncJob: kotlinx.coroutines.Job? = null

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
            Health.beat(this)
            Benchmark.beat(this)
            app.engine.dailySummaryIfDue()?.let { (t, b) -> MarketbotApp.post(this, "daily", t, b) }
            runCatching { app.engine.trackMarkoutsIfDue() }
            runCatching { app.engine.reconcileIfDue() }.getOrNull()?.let { app.recordCheck(it) }
            runCatching { app.engine.reconcileOrdersIfDue() }
            // The daily API wording check runs beside the loop (about 50 reads), at most one at a time.
            if (System.currentTimeMillis() - lastContractTry > 30 * 60_000L && contractJob?.isActive != true && Connectivity.online(this)) {
                lastContractTry = System.currentTimeMillis()
                contractJob = lifecycleScope.launch(Dispatchers.IO) { runCatching { app.engine.contractCheckIfDue() } }
            }
            // The Claude sync, once a day when a repository and token are saved.
            if (System.currentTimeMillis() - lastSyncTry > 30 * 60_000L && syncJob?.isActive != true && Connectivity.online(this)) {
                lastSyncTry = System.currentTimeMillis()
                syncJob = lifecycleScope.launch(Dispatchers.IO) {
                    val repo = app.vault.syncRepo(); val token = app.vault.syncToken()
                    if (!repo.isNullOrBlank() && !token.isNullOrBlank() && app.sync.due(java.time.Instant.now())) {
                        val before = app.sync.pending().size
                        runCatching { app.sync.sync(repo, token, BuildConfig.VERSION_NAME) }
                        val now = app.sync.pending().size
                        if (now > before) MarketbotApp.post(this@EngineService, "safety", "Claude proposed ${now - before} change(s)", "Review them under Settings > Claude sync.")
                    }
                }
            }
            // Off the main thread: the ping is a network call.
            kotlinx.coroutines.withContext(Dispatchers.IO) { Heartbeat.beatIfDue(app.vault.heartbeatUrl(), online = Connectivity.online(this@EngineService)) }
            if (System.currentTimeMillis() - lastPrune > 86_400_000L) { lastPrune = System.currentTimeMillis(); app.pruneRoutine() }
            delay(5_000)
        }
    }

    /**
     * The continuous wake lock is time-limited and re-armed on every power check
     * (every 5 s), so if this service ever dies without releasing it, Android drops
     * it within ten minutes instead of draining the battery.
     */
    private fun holdWake(on: Boolean) {
        if (on) {
            val w = wake ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marketbot:engine").apply { setReferenceCounted(false) }.also { wake = it }
            w.acquire(10 * 60_000L)
        } else {
            wake?.let { if (it.isHeld) it.release() }; wake = null
        }
    }

    private fun notification(title: String, text: String = "", lines: List<String> = emptyList(), live: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, MarketbotApp.CH_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_engine).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0, "Stop engine", stop)
        if (lines.isNotEmpty()) b.setStyle(NotificationCompat.InboxStyle().also { st -> lines.take(5).forEach { st.addLine(it) }; st.setSummaryText(text) })
        if (live) {
            // One tap from the shade: cancel every resting order and drop to paper.
            b.addAction(0, "Kill switch", KillReceiver.intent(this))
        }
        return b.build()
    }

    private fun stopEngine() {
        val app = application as MarketbotApp
        getSharedPreferences("service", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        Alarms.cancel(this)
        Health.clearBeat(this)
        app.appScope.launch {
            val summary = app.engine.stopAll()
            app.note(LogEvent.Level.INFO, "stopped", summary)
            MarketbotApp.alert(this@EngineService, "Engine stopped", summary)
        }
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
        const val EXTRA_BOOT = "boot"

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
        Benchmark.beat(context)
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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && EngineService.wasRunning(context))
            context.startForegroundService(Intent(context, EngineService::class.java).putExtra(EngineService.EXTRA_BOOT, true))
    }
}

/**
 * The clock or the time zone changed (travel, a manual set, a carrier update):
 * waiting strategies work out their next pass again, and the alarm follows.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val app = context.applicationContext as MarketbotApp
        app.engine.reschedule()
        app.appScope.launch {
            app.note(com.marketbot.core.store.LogEvent.Level.INFO, "clock",
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) "Time zone changed to ${java.util.TimeZone.getDefault().id}: schedules recomputed."
                else "The phone's clock was set: schedules recomputed.")
        }
    }
}
