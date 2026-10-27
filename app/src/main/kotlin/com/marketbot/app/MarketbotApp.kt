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
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = MarketbotDb.open(this)
        vault = Vault(this)
        engine = Engine(roomStores(db), notifier = { title, body -> alert(this, title, body) })
        createChannels(this)
        appScope.launch { engine.setCredentials(vault.credentials()) }
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

        @Suppress("MissingPermission")
        fun alert(ctx: Context, title: String, body: String) {
            val n = NotificationCompat.Builder(ctx, CH_ALERTS).setSmallIcon(R.drawable.ic_stat_engine)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(ctx).notify((title + body).hashCode(), n) }
        }
    }
}
