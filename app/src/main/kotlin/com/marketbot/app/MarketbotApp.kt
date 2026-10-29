package com.marketbot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.marketbot.app.data.MarketbotDb
import com.marketbot.app.data.Vault
import com.marketbot.app.data.roomStores
import com.marketbot.core.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the one engine, database and vault for the process. */
class MarketbotApp : Application() {
    lateinit var db: MarketbotDb
    lateinit var vault: Vault
    lateinit var engine: Engine
    /** Background work outside the engine. A failure here is logged, never allowed to crash the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("marketbot", "background task failed", e)
    })

    override fun onCreate() {
        super.onCreate()
        instance = this
        Health.installCrashLog(this)
        db = MarketbotDb.open(this)
        vault = Vault(this)
        engine = Engine(roomStores(db), notifier = { title, body -> alert(this, title, body) }, online = { Connectivity.online(this) },
            tradeNotifier = { title, body -> trade(this, title, body) })
        createChannels(this)
        appScope.launch {
            engine.setCredentials(vault.credentials())
            pruneRoutine()
        }
    }

    /** Routine activity lines older than 14 days; warnings, errors, trades and the ledger are kept. */
    suspend fun pruneRoutine() {
        db.events().pruneRoutine(System.currentTimeMillis() - 14 * 86_400_000L)
        db.checks().prune(System.currentTimeMillis() - 180 * 86_400_000L)
    }

    /** Keep a reconciliation in the history shown under Settings. */
    suspend fun recordCheck(r: com.marketbot.core.strategy.PnlReconciliation) = db.checks().insert(com.marketbot.app.data.CheckEntity(
        at = runCatching { java.time.Instant.parse(r.at).toEpochMilli() }.getOrDefault(System.currentTimeMillis()), kind = "pnl_recon", ok = r.clean,
        summary = r.summary, detail = com.marketbot.core.util.JSON.encodeToString(com.marketbot.core.strategy.PnlReconciliation.serializer(), r)))

    /** Log an engine-level line from outside the engine (boot restart, a gap found at start). */
    suspend fun note(level: com.marketbot.core.store.LogEvent.Level, kind: String, message: String) {
        engine.stores.events.log(com.marketbot.core.store.LogEvent(java.time.Instant.now(), null, level, kind, message))
    }

    companion object {
        lateinit var instance: MarketbotApp
            private set

        const val CH_ENGINE = "engine"
        const val CH_ALERTS = "alerts"
        const val CH_TRADES = "trades"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ENGINE, "Engine running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while strategies are running in the background."
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Kill switches, halts, brakes and errors that need you."
            })
            nm.createNotificationChannel(NotificationChannel(CH_TRADES, "Trades", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Live orders and settlements."
            })
        }

        /** Fill and settlement notices, grouped under one summary so a busy day stays one entry in the shade. */
        @Suppress("MissingPermission")
        fun trade(ctx: Context, title: String, body: String) {
            val nm = NotificationManagerCompat.from(ctx)
            val n = NotificationCompat.Builder(ctx, CH_TRADES).setSmallIcon(R.drawable.ic_stat_engine)
                .setContentTitle(title).setContentText(body).setGroup(GROUP_TRADES).setAutoCancel(true).build()
            val summary = NotificationCompat.Builder(ctx, CH_TRADES).setSmallIcon(R.drawable.ic_stat_engine)
                .setContentTitle("Trades").setGroup(GROUP_TRADES).setGroupSummary(true).setAutoCancel(true).build()
            runCatching { nm.notify((title + body + System.nanoTime()).hashCode(), n); nm.notify(GROUP_TRADES.hashCode(), summary) }
        }
        private const val GROUP_TRADES = "com.marketbot.trades"

        @Suppress("MissingPermission")
        fun alert(ctx: Context, title: String, body: String) {
            val n = NotificationCompat.Builder(ctx, CH_ALERTS).setSmallIcon(R.drawable.ic_stat_engine)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(ctx).notify((title + body).hashCode(), n) }
        }
    }
}
