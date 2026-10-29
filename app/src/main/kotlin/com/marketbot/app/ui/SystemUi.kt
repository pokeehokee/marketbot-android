package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
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
    Surface(color = tint.copy(alpha = 0.14f), shape = Radius.banner,
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
    val skew by produceState<Long?>(vm.clockSkewMs()) {
        while (true) { value = vm.clockSkewMs(); delay(15_000) }
    }
    var wasLive by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(killed) { wasLive = if (killed) vm.liveBeforeKill() else emptyList() }

    val running by vm.engineRunning.collectAsState()
    val missedBoot = com.marketbot.app.Health.missedBootRestart(ctx, com.marketbot.app.EngineService.wasRunning(ctx), running)
    val limit = remember(resumed) { com.marketbot.app.Background.limit(ctx) }
    val state by vm.modes.collectAsState()
    val now = LocalNow.current
    val gap = com.marketbot.app.Background.recentGap(state.firstOrNull { it.key == "engine.last_gap" }?.value, now)
    val status by vm.status.collectAsState()
    val overdue = if (running) overdue(status.values, now).mapNotNull { (id, mins) -> vm.strategies.firstOrNull { it.id == id }?.name?.let { it to mins } } else emptyList()
    Column(Modifier.padding(top = 4.dp)) {
        if (running && limit != com.marketbot.app.Background.Limit.NONE) Banner(Icons.Outlined.WarningAmber, LocalDesk.current.loss,
            if (limit == com.marketbot.app.Background.Limit.RESTRICTED) "Android has restricted Marketbot in the background: passes stop when the screen is off."
            else "Android has put Marketbot in a rarely-used group: passes can run hours late. Set its battery use to Unrestricted.",
            action = "Fix", onClick = { com.marketbot.app.Background.openSettings(ctx) })
        else if (running && gap != null) Banner(Icons.Outlined.Schedule, LocalDesk.current.warn,
            "The phone paused the engine for ${gap.second / 60} min at ${gap.first.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).withSecond(0)}. " +
                (com.marketbot.app.Health.oemAdvice() ?: "Set Marketbot's battery use to Unrestricted."),
            action = "Fix", onClick = { com.marketbot.app.Background.openSettings(ctx) })
        overdue.firstOrNull()?.let { (name, mins) -> Banner(Icons.Outlined.Schedule, LocalDesk.current.warn,
            "$name is $mins min overdue: figures on its screen are older than they look." + if (overdue.size > 1) " (${overdue.size - 1} more overdue)" else "") }
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
            Text(stringResource(R.string.demo_exchange), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.kalshi_s_practice_exchange_with_fake_mon), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(demo, { confirmDemo = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(sub, { sub = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text(stringResource(R.string.subaccount_0_primary)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedButton({ vm.setKalshiSubaccount(sub.toIntOrNull() ?: 0) }, enabled = (sub.toIntOrNull() ?: -1) != vm.kalshiSubaccount()) { Text(stringResource(R.string.use)) }
    }
    Text(stringResource(R.string.a_subaccount_keeps_the_bot_s_cash_and_po), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.hasKalshi()) ShardTransfer(vm, accounts.kalshi?.byExchangeIndex?.mapValues { it.value.value } ?: emptyMap())

    confirmDemo?.let { on ->
        AlertDialog(onDismissRequest = { confirmDemo = null },
            confirmButton = { Button({ if (on) vm.setKalshiDemo(true) else confirm("Switch Kalshi to real money") { vm.setKalshiDemo(false) }; confirmDemo = null }) { Text(if (on) "Use demo" else "Use real money") } },
            dismissButton = { TextButton({ confirmDemo = null }) { Text(stringResource(R.string.cancel)) } },
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
    Text(stringResource(R.string.move_cash_between_shards), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(toCrypto, { toCrypto = true }, { Text(stringResource(R.string.main_crypto_2)) })
        FilterChip(!toCrypto, { toCrypto = false }, { Text(stringResource(R.string.crypto_main)) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, Modifier.weight(1f), label = { Text(stringResource(R.string.amount)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { available?.let { Text(stringResource(R.string.shard_has, (from).toString(), (money(it)).toString())) } })
        Button({ confirm = true }, enabled = amt != null && amt > 0 && (available == null || amt <= available)) { Text(stringResource(R.string.move)) }
    }
    if (confirm && amt != null) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { Button({ auth("Move ${money(amt)} between shards") { vm.transferShards(amt, from, to) }; confirm = false; amount = "" }) { Text(stringResource(R.string.move_2, (money(amt)).toString())) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.move_3, (money(amt)).toString())) },
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
    Section(stringResource(R.string.appearance)) {
        Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Appearance.Mode.SYSTEM to "System", Appearance.Mode.LIGHT to "Light", Appearance.Mode.DARK to "Dark").forEach { (m, l) ->
                FilterChip(Appearance.mode == m, { Appearance.mode = m; Appearance.save(ctx) }, { Text(l) }) }
        }
        if (Build.VERSION.SDK_INT >= 31) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wallpaper_colours), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.material_you_source_tags_and_gains_losse), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(Appearance.dynamic, { Appearance.dynamic = it; Appearance.save(ctx) })
        }
    }
    Section(stringResource(R.string.security)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.confirm_money_actions), style = MaterialTheme.typography.bodyMedium)
                Text(if (com.marketbot.app.Confirm.available(ctx)) "Fingerprint, face or screen lock before arming live, turning the kill switch off, moving cash or changing keys."
                    else "This phone has no screen lock, so nothing can be confirmed. Set one in Android settings.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(confirmLive, { on -> if (on) { com.marketbot.app.Confirm.setEnabled(ctx, true); confirmLive = true }
                else authOff("Stop asking for confirmation") { com.marketbot.app.Confirm.setEnabled(ctx, false); confirmLive = false } })
        }
    }
    Section(stringResource(R.string.background_reliability)) {
        Health.oemAdvice()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val exact = Alarms.exactAllowed(ctx)
        Text(if (exact) "Exact alarms: allowed. Hourly passes run on time with the screen off." else "Exact alarms: not allowed. Android may run hourly passes a few minutes late.",
            style = MaterialTheme.typography.bodySmall, color = if (exact) LocalDesk.current.gain else LocalDesk.current.warn)
        if (!exact && Build.VERSION.SDK_INT >= 31) OutlinedButton({
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}")))
        }) { Text(stringResource(R.string.allow_exact_alarms)) }
    }
    Section(stringResource(R.string.network)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wi_fi_only), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.strategies_wait_on_mobile_data_btc_deep), style = MaterialTheme.typography.labelSmall,
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
    Section(stringResource(R.string.diagnostics)) {
        // Memory over time is what the 13-hour crash of build 116 needed and nobody could see.
        val mem by produceState(MemoryReading.now()) { while (true) { delay(5_000); value = MemoryReading.now() } }
        val warn = mem.usedMb > mem.maxMb * 0.75
        Text("Memory: ${mem.usedMb} MB of ${mem.maxMb} MB", style = MaterialTheme.typography.bodySmall, color = if (warn) LocalDesk.current.warn else MaterialTheme.colorScheme.onSurface)
        androidx.compose.material3.LinearProgressIndicator(progress = { (mem.usedMb.toFloat() / mem.maxMb).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = if (warn) LocalDesk.current.warn else MaterialTheme.colorScheme.primary)
        mem.caches.forEach { (n, v) -> Text("$n: $v", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Build ${com.marketbot.app.BuildConfig.VERSION_NAME} · ${com.marketbot.app.BuildConfig.GIT_SHA}", style = Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    crash?.let { c ->
        Section(stringResource(R.string.last_crash)) {
            Text(c.lineSequence().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c)
                        .putExtra(Intent.EXTRA_SUBJECT, "Marketbot crash report"), "Share crash report"))
                }) { Text(stringResource(R.string.share)) }
                TextButton({ vm.clearCrash(); crash = null }) { Text(stringResource(R.string.dismiss)) }
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
    Section(stringResource(R.string.backup)) {
        Text(stringResource(R.string.a_password_protected_file_with_every_ord),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton({ create.launch("marketbot-${java.time.LocalDate.now()}.mbk") }) { Text(stringResource(R.string.back_up)) }
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !running) { Text(stringResource(R.string.restore)) }
        }
        if (running) Text(stringResource(R.string.stop_the_engine_to_restore), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pending?.let { (restore, uri) ->
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        val ok = pw.length >= 8 && (restore || pw == pw2)
        AlertDialog(onDismissRequest = { pending = null },
            confirmButton = { Button({ if (restore) vm.restore(uri, pw.toCharArray()) else vm.backup(uri, pw.toCharArray()); pending = null }, enabled = ok) {
                Text(if (restore) "Replace everything" else "Save backup") } },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(if (restore) "Restore backup" else "Protect the backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (restore) Text(stringResource(R.string.this_replaces_every_order_setting_and_re),
                        style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.warn)
                    OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    if (!restore) OutlinedTextField(pw2, { pw2 = it }, label = { Text(stringResource(R.string.repeat_password)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = { Text(stringResource(R.string.at_least_8_characters_it_cannot_be_recov)) })
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
        OutlinedButton({ vm.checkUpdates() }) { Text(stringResource(R.string.check_for_updates)) }
        url?.let { u -> Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }) { Text(stringResource(R.string.download)) } }
    }
    msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton({ showToken = !showToken }) { Text(if (vm.hasGithubToken()) "GitHub token saved" else "Private repository? Add a token") }
    if (showToken) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(token, { token = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.read_only_token_contents_read)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedButton({ vm.saveGithubToken(token.trim()); token = ""; showToken = false }) { Text(stringResource(R.string.save)) }
    }
}


/** Heap use and the size of every long-lived structure, for the diagnostics card. */
data class MemoryReading(val usedMb: Long, val maxMb: Long, val caches: List<Pair<String, Int>>) {
    companion object {
        fun now(rt: Runtime = Runtime.getRuntime()) = MemoryReading((rt.totalMemory() - rt.freeMemory()) / 1_048_576, rt.maxMemory() / 1_048_576,
            com.marketbot.core.util.Diagnostics.sizes())
    }
}

/**
 * Running strategies whose next pass is late by more than its own period (and two
 * minutes): (id, minutes late). A pass that is late means every figure it feeds is old.
 */
fun overdue(status: Collection<com.marketbot.core.engine.StrategyStatus>, now: java.time.Instant): List<Pair<String, Long>> = status.filter { it.running }.mapNotNull { st ->
    val next = st.nextRunAt ?: return@mapNotNull null
    val period = st.lastRunAt?.let { java.time.Duration.between(it, next) }?.takeIf { !it.isNegative } ?: java.time.Duration.ZERO
    val late = java.time.Duration.between(next, now)
    if (late > maxOf(period, java.time.Duration.ofMinutes(2))) st.id to late.toMinutes() else null
}
