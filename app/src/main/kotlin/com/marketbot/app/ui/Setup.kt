package com.marketbot.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marketbot.app.Alarms
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.Mode

object Setup {
    fun done(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).let { it.getBoolean("setup_v2", false) || it.getBoolean("accepted_v1", false) }
    fun finish(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("setup_v2", true).apply()
}

/**
 * First run, five short steps: what this is, the risk and paper-first, keeping it
 * running on this phone, keys (optional), and a first strategy in paper. The aim
 * is a useful screen in under a minute without a key.
 */
@Composable
fun SetupWizard(vm: MainViewModel, onDone: (goToAccounts: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var understood by rememberSaveable { mutableStateOf(false) }
    var startPaper by rememberSaveable { mutableStateOf(true) }
    var addKeys by rememberSaveable { mutableStateOf(false) }
    val steps = 5
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                repeat(steps) { i -> Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                    .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    0 -> {
                        Text("Welcome to Marketbot", style = MaterialTheme.typography.headlineMedium)
                        Text("It runs trading strategies on Kalshi and Polymarket from this phone, around the clock, the way they ran on a desktop.", style = MaterialTheme.typography.bodyLarge)
                        Text("Every number shows where it came from: the exchange (KALSHI / POLYMARKET), a calculation (CALC), the simulator (PAPER) or your settings. Tap a number for the exact field or formula.",
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { com.marketbot.core.provenance.Source.entries.forEach { SourceChip(it) } }
                        Text("Your keys stay on this phone and are only used to sign requests to the exchanges.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        Text("Paper first, real money only on purpose", style = MaterialTheme.typography.headlineSmall)
                        Text("Everything starts in paper mode: live public prices, simulated orders, nothing sent to an exchange.", style = MaterialTheme.typography.bodyLarge)
                        Text("Live trading needs a checklist, a preflight, typing LIVE and your fingerprint or screen lock, per strategy.", style = MaterialTheme.typography.bodyMedium)
                        Text("These strategies win small and often and lose big and rarely: one contract can lose up to about a dollar. Only the weather longshot has made money live, and its last stage check failed. Each card says how strong its evidence is.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Run one bot per account. If a computer is trading the same account, stop it first.", style = MaterialTheme.typography.bodyMedium, color = LocalDesk.current.warn)
                        Row(Modifier.fillMaxWidth().toggleable(understood, role = Role.Checkbox) { understood = it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (understood) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp)); Text("I understand this can lose money", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    2 -> KeepRunning(ctx)
                    3 -> {
                        Text("Accounts (optional)", style = MaterialTheme.typography.headlineSmall)
                        Text("Paper mode needs no keys. Keys let the app show your balances and, when you arm it, trade.", style = MaterialTheme.typography.bodyLarge)
                        Text("Kalshi: kalshi.com > Account > API keys gives a key id and a .pem file. Its private key goes into this phone's secure hardware and cannot be read back out.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Polymarket US: polymarket.us/developer, after verification.", style = MaterialTheme.typography.bodyMedium)
                        Choice("Add keys after setup", addKeys) { addKeys = true }
                        Choice("Later", !addKeys) { addKeys = false }
                    }
                    else -> {
                        Text("Your first strategy", style = MaterialTheme.typography.headlineSmall)
                        Text("Start the weather longshot in paper: it sells cheap YES on Kalshi's daily temperature buckets and is the one method with a live record. You'll see its first simulated offers on the next hourly pass.",
                            style = MaterialTheme.typography.bodyLarge)
                        Choice("Start it in paper (recommended)", startPaper) { startPaper = true }
                        Choice("Not yet: I'll look around first", !startPaper) { startPaper = false }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton({ step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step < steps - 1) Button({ step++ }, enabled = step != 1 || understood) { Text("Next") }
                else Button({
                    Setup.finish(ctx)
                    if (startPaper) vm.strategies.firstOrNull { it is WeatherLongshot }?.let { vm.setMode(it, Mode.PAPER) }
                    onDone(addKeys)
                }) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, role = Role.RadioButton) { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp)); Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/** Battery exemption, notifications and exact alarms, each with its live status. */
@Composable
private fun KeepRunning(ctx: Context) {
    val resumed = rememberResumeCount()
    var notif by remember(resumed) { mutableStateOf(notificationsAllowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = notificationsAllowed(ctx) }
    val battery = remember(resumed) { ignoringBattery(ctx) }
    val exact = remember(resumed) { Alarms.exactAllowed(ctx) }
    Text("Keep it running", style = MaterialTheme.typography.headlineSmall)
    Text("A phone pauses background apps to save battery. Three settings stop that from pausing your strategies.", style = MaterialTheme.typography.bodyLarge)
    SetupItem("Battery: let Marketbot run in the background", battery, "Allow") { requestBatteryExemption(ctx) }
    SetupItem("Notifications: hear about fills, brakes and the kill switch", notif, "Allow") {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    SetupItem("Exact alarms: run hourly passes on the hour", exact, "Allow") {
        if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}")))
    }
    com.marketbot.app.Health.oemAdvice()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.warn) }
}

@Composable
private fun SetupItem(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (ok) "Done" else "Not done",
            tint = if (ok) LocalDesk.current.gain else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick) { Text(action) }
    }
}
