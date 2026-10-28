package com.marketbot.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.marketbot.app.Alarms
import com.marketbot.app.Health
import com.marketbot.core.strategy.money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
private fun Banner(icon: ImageVector, tint: Color, text: String, onClick: (() -> Unit)? = null, action: String? = null) {
    Surface(color = tint.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null && onClick != null) TextButton(onClick) { Text(action) }
        }
    }
}

fun notificationsAllowed(ctx: Context): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

/** Conditions that change what every screen means: shown above all of them. */
@Composable
fun SystemBanners(vm: MainViewModel) {
    val ctx = LocalContext.current
    val demo by vm.demo.collectAsState()
    val killed by vm.killed.collectAsState()
    val resumed = rememberResumeCount()
    var notifOk by remember(resumed) { mutableStateOf(notificationsAllowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = notificationsAllowed(ctx) }
    // Clock skew is measured from responses, so re-read it now and then.
    val skew by produceState<Long?>(vm.clockSkewMs()) { while (true) { delay(15_000); value = vm.clockSkewMs() } }
    val wasLive by produceState(emptyList<String>(), killed) { value = if (killed) vm.liveBeforeKill() else emptyList() }

    val running by vm.engineRunning.collectAsState()
    val missedBoot = com.marketbot.app.Health.missedBootRestart(ctx, com.marketbot.app.EngineService.wasRunning(ctx), running)
    Column(Modifier.padding(top = 4.dp)) {
        if (missedBoot) Banner(Icons.Outlined.WarningAmber, LocalDesk.current.loss,
            "The engine didn't restart after the phone rebooted, so nothing has run since. Check battery settings for Marketbot.",
            action = "Start", onClick = { vm.ensureService() })
        if (demo) Banner(Icons.Outlined.Science, LocalDesk.current.warn, "KALSHI DEMO: fake money on Kalshi's demo exchange. Polymarket is unaffected.")
        if (killed && wasLive.isNotEmpty()) Banner(Icons.Outlined.WarningAmber, LocalDesk.current.loss,
            "Kill switch on. Were live: ${wasLive.joinToString()}. They run in paper until you re-arm each one.")
        if (!notifOk) Banner(Icons.Outlined.NotificationsOff, LocalDesk.current.warn,
            "Notifications are off: you will not hear about brakes, kills or failed orders.", action = "Allow", onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
        skew?.takeIf { abs(it) > 5_000 }?.let { s ->
            Banner(Icons.Outlined.Schedule, LocalDesk.current.warn,
                "The phone's clock is ${"%.0f".format(abs(s) / 1000.0)} s ${if (s > 0) "behind" else "ahead of"} the exchanges. Requests are corrected, but turn on automatic date and time.",
                action = "Fix", onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) })
        }
    }
}

/** Demo exchange, subaccount and the shard transfer, under Accounts > Kalshi. */
@Composable
fun KalshiAccountOptions(vm: MainViewModel) {
    val confirm = com.marketbot.app.rememberConfirm()
    val demo by vm.demo.collectAsState()
    val accounts by vm.accounts.collectAsState()
    var confirmDemo by remember { mutableStateOf<Boolean?>(null) }
    var sub by remember { mutableStateOf(vm.kalshiSubaccount().toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Demo exchange", style = MaterialTheme.typography.bodyMedium)
            Text("Kalshi's practice exchange with fake money. Needs a key made on demo.kalshi.co.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(demo, { confirmDemo = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(sub, { sub = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Subaccount (0 = primary)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedButton({ vm.setKalshiSubaccount(sub.toIntOrNull() ?: 0) }, enabled = (sub.toIntOrNull() ?: -1) != vm.kalshiSubaccount()) { Text("Use") }
    }
    Text("A subaccount keeps the bot's cash and positions apart from anything you trade by hand.", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.hasKalshi()) ShardTransfer(vm, accounts.kalshi?.byExchangeIndex?.mapValues { it.value.value } ?: emptyMap())

    confirmDemo?.let { on ->
        AlertDialog(onDismissRequest = { confirmDemo = null },
            confirmButton = { Button({ if (on) vm.setKalshiDemo(true) else confirm("Switch Kalshi to real money") { vm.setKalshiDemo(false) }; confirmDemo = null }) { Text(if (on) "Use demo" else "Use real money") } },
            dismissButton = { TextButton({ confirmDemo = null }) { Text("Cancel") } },
            title = { Text(if (on) "Switch Kalshi to demo?" else "Switch Kalshi to the real exchange?") },
            text = { Text(if (on) "Kalshi strategies will trade fake money. The paper and live ledgers are not reset, so demo fills will appear in the live book until you switch back."
                else "Live Kalshi strategies will trade real money again, with your real key.") })
    }
}

@Composable
private fun ShardTransfer(vm: MainViewModel, shards: Map<Int, Double>) {
    var amount by remember { mutableStateOf("") }
    var toCrypto by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val auth = com.marketbot.app.rememberConfirm()
    val from = if (toCrypto) 0 else 2
    val to = if (toCrypto) 2 else 0
    val amt = amount.toDoubleOrNull()
    val available = shards[from]
    Text("Move cash between shards", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(toCrypto, { toCrypto = true }, { Text("Main → crypto (2)") })
        FilterChip(!toCrypto, { toCrypto = false }, { Text("Crypto → main") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, Modifier.weight(1f), label = { Text("Amount, $") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { available?.let { Text("Shard $from has ${money(it)}") } })
        Button({ confirm = true }, enabled = amt != null && amt > 0 && (available == null || amt <= available)) { Text("Move") }
    }
    if (confirm && amt != null) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { Button({ auth("Move ${money(amt)} between shards") { vm.transferShards(amt, from, to) }; confirm = false; amount = "" }) { Text("Move ${money(amt)}") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        title = { Text("Move ${money(amt)}?") },
        text = { Text("From shard $from to shard $to of ${if (vm.kalshiSubaccount() == 0) "your primary account" else "subaccount #${vm.kalshiSubaccount()}"}. Kalshi processes transfers in the background; it cannot be undone from here, only reversed by another transfer.") })
}

/** Settings sections for running in the background, network use and crash reports. */
@Composable
fun SystemSettings(vm: MainViewModel) {
    val ctx = LocalContext.current
    val authOff = com.marketbot.app.rememberConfirm()
    var wifi by remember { mutableStateOf(vm.wifiOnly()) }
    var crash by remember { mutableStateOf(vm.lastCrash()) }
    val usage by produceState(vm.netUsage()) { while (true) { delay(10_000); value = vm.netUsage() } }

    var confirmLive by remember { mutableStateOf(com.marketbot.app.Confirm.enabled(ctx)) }
    Section("Security") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Confirm money actions", style = MaterialTheme.typography.bodyMedium)
                Text(if (com.marketbot.app.Confirm.available(ctx)) "Fingerprint, face or screen lock before arming live, turning the kill switch off, moving cash or changing keys."
                    else "This phone has no screen lock, so nothing can be confirmed. Set one in Android settings.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(confirmLive, { on -> if (on) { com.marketbot.app.Confirm.setEnabled(ctx, true); confirmLive = true }
                else authOff("Stop asking for confirmation") { com.marketbot.app.Confirm.setEnabled(ctx, false); confirmLive = false } })
        }
    }
    Section("Background reliability") {
        Health.oemAdvice()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val exact = Alarms.exactAllowed(ctx)
        Text(if (exact) "Exact alarms: allowed. Hourly passes run on time with the screen off." else "Exact alarms: not allowed. Android may run hourly passes a few minutes late.",
            style = MaterialTheme.typography.bodySmall, color = if (exact) LocalDesk.current.gain else LocalDesk.current.warn)
        if (!exact && Build.VERSION.SDK_INT >= 31) OutlinedButton({
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}")))
        }) { Text("Allow exact alarms") }
    }
    Section("Network") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                Text("Strategies wait on mobile data. BTC deep-ITM and reward farming poll every few seconds.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(wifi, { wifi = it; vm.setWifiOnly(it) })
        }
        val total = usage.values.sumOf { it.bytes }
        Text("Today: ${usage.values.sumOf { it.requests }} requests, ${"%.1f".format(total / 1_048_576.0)} MB (approximate, since the app started)",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        usage.entries.sortedByDescending { it.value.bytes }.take(6).forEach { (h, u) ->
            Text("$h: ${u.requests} req, ${"%.1f".format(u.bytes / 1_048_576.0)} MB", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    crash?.let { c ->
        Section("Last crash") {
            Text(c.lineSequence().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c)
                        .putExtra(Intent.EXTRA_SUBJECT, "Marketbot crash report"), "Share crash report"))
                }) { Text("Share") }
                TextButton({ vm.clearCrash(); crash = null }) { Text("Dismiss") }
            }
        }
    }
}


/** Back up to, and restore from, a password-sealed file the user picks. */
@Composable
fun BackupSection(vm: MainViewModel) {
    var pending by remember { mutableStateOf<Pair<Boolean, android.net.Uri>?>(null) }   // (isRestore, uri)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let { u -> pending = false to u } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> pending = true to u } }
    val running by vm.engineRunning.collectAsState()
    Section("Backup") {
        Text("A password-protected file with every order, setting and record. API keys are not included; the Kalshi key cannot leave this phone at all.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton({ create.launch("marketbot-${java.time.LocalDate.now()}.mbk") }) { Text("Back up") }
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !running) { Text("Restore") }
        }
        if (running) Text("Stop the engine to restore.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pending?.let { (restore, uri) ->
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        val ok = pw.length >= 8 && (restore || pw == pw2)
        AlertDialog(onDismissRequest = { pending = null },
            confirmButton = { Button({ if (restore) vm.restore(uri, pw.toCharArray()) else vm.backup(uri, pw.toCharArray()); pending = null }, enabled = ok) {
                Text(if (restore) "Replace everything" else "Save backup") } },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } },
            title = { Text(if (restore) "Restore backup" else "Protect the backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (restore) Text("This replaces every order, setting and record on this phone with the backup's. Strategies come back in paper.",
                        style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.warn)
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (!restore) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = { Text("At least 8 characters. It cannot be recovered.") })
                }
            })
    }
}


@Composable
fun UpdateRow(vm: MainViewModel) {
    val ctx = LocalContext.current
    val msg by vm.update.collectAsState()
    val url by vm.updateUrl.collectAsState()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ vm.checkUpdates() }) { Text("Check for updates") }
        url?.let { u -> Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }) { Text("Download") } }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton({ showToken = !showToken }) { Text(if (vm.hasGithubToken()) "GitHub token saved" else "Private repository? Add a token") }
    if (showToken) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(token, { token = it }, Modifier.weight(1f), label = { Text("Read-only token (contents: read)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedButton({ vm.saveGithubToken(token.trim()); token = ""; showToken = false }) { Text("Save") }
    }
}
