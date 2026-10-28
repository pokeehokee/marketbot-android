package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
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
                        Text(stringResource(R.string.welcome_to_marketbot), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.it_runs_trading_strategies_on_kalshi_and), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.every_number_shows_where_it_came_from_th),
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { com.marketbot.core.provenance.Source.entries.forEach { SourceChip(it) } }
                        Text(stringResource(R.string.your_keys_stay_on_this_phone_and_are_onl), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        Text(stringResource(R.string.paper_first_real_money_only_on_purpose), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.everything_starts_in_paper_mode_live_pub), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.live_trading_needs_a_checklist_a_preflig), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.these_strategies_win_small_and_often_and),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.run_one_bot_per_account_if_a_computer_is), style = MaterialTheme.typography.bodyMedium, color = LocalDesk.current.warn)
                        Row(Modifier.fillMaxWidth().toggleable(understood, role = Role.Checkbox) { understood = it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (understood) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.i_understand_this_can_lose_money), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    2 -> KeepRunning(ctx)
                    3 -> {
                        Text(stringResource(R.string.accounts_optional), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.paper_mode_needs_no_keys_keys_let_the_ap), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.kalshi_kalshi_com_account_api_keys_gives),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.polymarket_us_polymarket_us_developer_af), style = MaterialTheme.typography.bodyMedium)
                        Choice("Add keys after setup", addKeys) { addKeys = true }
                        Choice("Later", !addKeys) { addKeys = false }
                    }
                    else -> {
                        Text(stringResource(R.string.your_first_strategy), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.start_the_weather_longshot_in_paper_it_s),
                            style = MaterialTheme.typography.bodyLarge)
                        Choice("Start it in paper (recommended)", startPaper) { startPaper = true }
                        Choice("Not yet: I'll look around first", !startPaper) { startPaper = false }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton({ step-- }) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.weight(1f))
                if (step < steps - 1) Button({ step++ }, enabled = step != 1 || understood) { Text(stringResource(R.string.next)) }
                else Button({
                    Setup.finish(ctx)
                    if (startPaper) vm.strategies.firstOrNull { it is WeatherLongshot }?.let { vm.setMode(it, Mode.PAPER) }
                    onDone(addKeys)
                }) { Text(stringResource(R.string.finish)) }
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
    Text(stringResource(R.string.keep_it_running), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.a_phone_pauses_background_apps_to_save_b), style = MaterialTheme.typography.bodyLarge)
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
