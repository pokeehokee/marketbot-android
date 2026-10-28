package com.marketbot.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marketbot.app.data.toSnapshot
import com.marketbot.core.provenance.Source
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.money

@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSources: () -> Unit) {
    val status by vm.status.collectAsState()
    val killed by vm.killed.collectAsState()
    val equity by vm.equity.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val running by vm.engineRunning.collectAsState()
    vm.modes.collectAsState().value
    val ctx = LocalContext.current
    var confirmKill by remember { mutableStateOf(false) }
    val confirm = com.marketbot.app.rememberConfirm()
    LaunchedEffect(Unit) { vm.refreshAccounts() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Marketbot", style = MaterialTheme.typography.headlineSmall)
                Text(if (running) "Engine running" else "Engine stopped", style = MaterialTheme.typography.bodyMedium,
                    color = if (running) LocalDesk.current.gain else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ if (running) vm.stopService() else vm.ensureService() }) { Icon(Icons.Outlined.PowerSettingsNew, "Start or stop engine") }
        }

        PortfolioHeader(vm)
        EquitySection(vm)
        val resumed = rememberResumeCount()
        val batteryOk = remember(resumed) { ignoringBattery(ctx) }
        if (!batteryOk) Surface(shape = RoundedCornerShape(12.dp), color = LocalDesk.current.warn.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth().clickable { requestBatteryExemption(ctx) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BatteryAlert, null, tint = LocalDesk.current.warn)
                Spacer(Modifier.width(12.dp))
                Text("Battery optimisation can pause the engine while the screen is off. Tap to exempt Marketbot.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Kill switch
        Surface(shape = RoundedCornerShape(14.dp), color = if (killed) LocalDesk.current.loss.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, if (killed) LocalDesk.current.loss else MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (killed) "Kill switch is ON" else "Kill switch", style = MaterialTheme.typography.titleMedium)
                    Text(if (killed) "Live trading is off. Paper strategies keep running." else "Cancels every resting order and turns live trading off.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (killed) OutlinedButton({ confirm("Turn the kill switch off") { vm.kill(false) } }) { Text("Turn off") }
                else Button({ confirmKill = true }, colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.loss)) { Text("KILL") }
            }
        }

        Section("Accounts", trailing = { IconButton({ vm.refreshAccounts() }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Refresh, "Refresh") } }) {
            if (!vm.hasKalshi() && !vm.hasPolymarket()) Text("No API keys yet. Paper mode works without them; add keys under Accounts to see balances and trade live.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            accounts.kalshi?.let { b ->
                SourcedRow("Kalshi cash", b.cash, { money(it) })
                b.portfolioValue?.let { SourcedRow("Kalshi positions (exchange mark)", it, { v -> money(v) }) }
                b.equity?.let { SourcedRow("Kalshi equity", it, { v -> money(v) }) }
                b.byExchangeIndex[2]?.let { SourcedRow("Kalshi crypto shard cash", it, { v -> money(v) }) }
            }
            accounts.kalshiError?.let { Text("Kalshi: $it", color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
            accounts.polymarket?.let { b ->
                SourcedRow("Polymarket balance", b.currentBalance, { money(it) })
                SourcedRow("Polymarket buying power", b.buyingPower, { money(it) })
            }
            accounts.polymarketError?.let { Text("Polymarket: $it", color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
            accounts.readAt?.let { Text("Read ${it.short()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        val paper = equity.filter { it.mode == Mode.PAPER.name }
        if (paper.isNotEmpty()) Section("Paper accounts") {
            paper.forEach { e -> val s = e.toSnapshot(); SourcedRow("${e.venue.replaceFirstChar { it.uppercase() }} paper equity", s.cash, { money(it) }) }
        }

        Section("Strategies") {
            vm.strategies.forEach { s ->
                val st = status[s.id]
                val mode = st?.mode ?: vm.modeOf(s.id)
                Row(Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(st?.lastSummary?.let { "Last: $it" } ?: s.venue.label, style = MaterialTheme.typography.bodySmall,
                            color = if (st?.lastError != null) LocalDesk.current.loss else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    ModePill(mode, st?.armed == true, running)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tags show where each number came from.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onSources) { Text("Data sources") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(Source.KALSHI_API, Source.POLYMARKET_API, Source.DERIVED, Source.PAPER_MODEL, Source.CONFIG).forEach { SourceChip(it) } }
    }

    if (confirmKill) AlertDialog(onDismissRequest = { confirmKill = false },
        confirmButton = { Button({ confirmKill = false; vm.kill(true) }, colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.loss)) { Text("Cancel everything") } },
        dismissButton = { TextButton({ confirmKill = false }) { Text("Back") } },
        title = { Text("Turn on the kill switch?") },
        text = { Text("Every resting order of every strategy is cancelled at the exchange and live trading is disarmed. Filled positions are kept and settle normally.") })
}

fun ignoringBattery(ctx: Context): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@android.annotation.SuppressLint("BatteryLife")
fun requestBatteryExemption(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
