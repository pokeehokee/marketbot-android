package com.marketbot.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.launch

/**
 * The kill switch from outside the app: the notification, the Quick Settings tile
 * and the widget all send this broadcast. A receiver, not the service, because
 * killing must work when the engine is not running.
 */
class KillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MarketbotApp
        val done: PendingResult? = goAsync()
        app.appScope.launch {
            try { app.engine.setGlobalKill(true) } finally { Surfaces.refresh(context); done?.finish() }
        }
    }

    companion object {
        fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, KillReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Quick Settings tile: shows whether the kill switch is on; tapping it turns it on.
 * Turning it off needs the app (and its confirmation), so then the tap opens the app.
 */
class KillTileService : TileService() {
    override fun onStartListening() { render() }

    private fun render() {
        val killed = (application as MarketbotApp).engine.globalKill.value
        qsTile?.apply {
            state = if (killed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Kill switch"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (killed) "On: live off" else "Cancel all orders"
            updateTile()
        }
    }

    // The Intent form is the only one before Android 14; the PendingIntent form is used from 14.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val app = application as MarketbotApp
        if (app.engine.globalKill.value) {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(open)
            else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        app.appScope.launch { app.engine.setGlobalKill(true); render(); Surfaces.refresh(this@KillTileService) }
    }
}

/** Home-screen widget: engine state, today's realised P&L (live and paper), and a kill button. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { Surfaces.refresh(context) }
}

object Surfaces {
    /** Redraw the widget and ask the tile to refresh. Cheap; called when status changes. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext as MarketbotApp
        app.appScope.launch {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isNotEmpty()) {
                val since = com.marketbot.core.strategy.startOfLocalDay(java.time.Instant.now())
                suspend fun today(mode: com.marketbot.core.strategy.Mode) = app.engine.strategies.sumOf { s ->
                    com.marketbot.core.strategy.realisedSince(app.engine.stores.ledger.all(s.id, mode), since) }
                val killed = app.engine.globalKill.value
                val v = RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                    setTextViewText(R.id.w_state, when { killed -> "Kill switch ON"; app.engine.running.value -> "Engine running"; else -> "Engine stopped" })
                    setTextViewText(R.id.w_live, "Live today " + com.marketbot.core.strategy.money(today(com.marketbot.core.strategy.Mode.LIVE)))
                    setTextViewText(R.id.w_paper, "Paper today " + com.marketbot.core.strategy.money(today(com.marketbot.core.strategy.Mode.PAPER)))
                    setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    setOnClickPendingIntent(R.id.w_kill, KillReceiver.intent(ctx))
                    setBoolean(R.id.w_kill, "setEnabled", !killed)
                }
                m.updateAppWidget(ids, v)
            }
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, KillTileService::class.java)) }
        }
    }
}
