package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.marketbot.app.data.values
import com.marketbot.core.provenance.Source
import com.marketbot.core.strategy.money
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ActivityScreen(vm: MainViewModel, initialQuery: String = "") {
    val events by vm.events.collectAsState()
    var level by remember { mutableStateOf<String?>(null) }
    var strat by remember { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val shown = events.filter { (level == null || it.level == level) && (strat == null || it.strategyId == strat) &&
        (query.isBlank() || it.message.contains(query.trim(), ignoreCase = true) || it.values.contains(query.trim(), ignoreCase = true)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(stringResource(R.string.activity), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_a_city_a_ticker_brake)) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null) })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(level == null, { level = null }, { Text(stringResource(R.string.all)) })
                listOf("TRADE" to "Trades", "WARN" to "Warnings", "ERROR" to "Errors", "INFO" to "Info").forEach { (l, name) ->
                    FilterChip(level == l, { level = if (level == l) null else l }, { Text(name) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(strat == null, { strat = null }, { Text(stringResource(R.string.every_strategy)) })
                vm.strategies.forEach { s -> FilterChip(strat == s.id, { strat = if (strat == s.id) null else s.id }, { Text(s.name) }) }
            }
        }
        if (shown.isEmpty()) item { Text(stringResource(R.string.nothing_logged_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.id }) { e ->
            var open by remember { mutableStateOf(false) }
            val d = LocalDesk.current
            val c = when (e.level) { "ERROR" -> d.loss; "WARN" -> d.warn; "TRADE" -> d.gain; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (e.level) { "TRADE" -> "Trade"; "WARN" -> "Warning"; "ERROR" -> "Error"; "DEBUG" -> "Debug"; else -> "Info" },
                        style = MaterialTheme.typography.labelSmall, color = c)
                    Spacer(Modifier.width(8.dp))
                    Text(Instant.ofEpochMilli(e.at).short(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.strategies.firstOrNull { it.id == e.strategyId }?.name ?: "Engine", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(e.message, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 3)
                if (!open && e.values().isNotEmpty()) Text(stringResource(R.string.tap_for_the_figures_and_where_they_came), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open) e.values().forEach { v ->
                    ValueRow(v.key.replace('_', ' '), v.value, runCatching { Source.valueOf(v.source) }.getOrNull(), v.origin, v.note, Instant.ofEpochMilli(v.at))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun AccountsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val confirm = com.marketbot.app.rememberConfirm()
    val accounts by vm.accounts.collectAsState()
    var kId by remember { mutableStateOf("") }
    var kPem by remember { mutableStateOf("") }
    var pId by remember { mutableStateOf("") }
    var pSecret by remember { mutableStateOf("") }
    val pickPem = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { kPem = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty() }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.accounts), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.keys_are_encrypted_with_a_key_held_in_th),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section(stringResource(R.string.kalshi)) {
            Text(if (vm.hasKalshi()) "Key saved (id ending ${vm.kalshiHint()}), ${if (vm.kalshiInHardware()) "private key in secure hardware: it signs requests and cannot be read back out" else "encrypted on this phone"}." else "No key saved.", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.create_one_at_kalshi_com_account_api_key), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(kId, { kId = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.key_id)) }, singleLine = true)
            OutlinedTextField(kPem, { kPem = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.private_key_paste_pem)) }, minLines = 3, maxLines = 6,
                visualTransformation = if (kPem.length > 60) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickPem.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_pem_file)) }
                Button({ val id = kId.trim(); val pem = kPem; confirm("Save Kalshi key") { vm.saveKalshi(id, pem) }; kId = ""; kPem = "" }, enabled = kId.isNotBlank() && kPem.isNotBlank()) { Text(stringResource(R.string.save)) }
                if (vm.hasKalshi()) TextButton({ confirm("Remove Kalshi key") { vm.clearKalshi() } }) { Text(stringResource(R.string.remove)) }
            }
            accounts.kalshi?.let { b ->
                SourcedRow("Cash", b.cash, { money(it) }); b.portfolioValue?.let { SourcedRow("Positions", it, { v -> money(v) }) }
                b.byExchangeIndex.toSortedMap().forEach { (i, v) -> SourcedRow("Shard $i cash${if (i == 2) " (crypto)" else ""}", v, { money(it) }) }
            }
            accounts.kalshiError?.let { ErrorCard("Kalshi: $it") { vm.refreshAccounts() } }
            Text(stringResource(R.string.btc_deep_itm_trades_on_kalshi_s_crypto_s),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KalshiAccountOptions(vm)
        }

        Section(stringResource(R.string.polymarket_us)) {
            Text(if (vm.hasPolymarket()) "Key saved (id ending ${vm.polymarketHint()})." else "No key saved.", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.create_one_at_polymarket_us_developer_af), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(pId, { pId = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.key_id)) }, singleLine = true)
            OutlinedTextField(pSecret, { pSecret = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.secret_key)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ val id = pId.trim(); val sec = pSecret.trim(); confirm("Save Polymarket key") { vm.savePolymarket(id, sec) }; pId = ""; pSecret = "" }, enabled = pId.isNotBlank() && pSecret.isNotBlank()) { Text(stringResource(R.string.save)) }
                if (vm.hasPolymarket()) TextButton({ confirm("Remove Polymarket key") { vm.clearPolymarket() } }) { Text(stringResource(R.string.remove)) }
            }
            accounts.polymarket?.let { b ->
                SourcedRow("Balance", b.currentBalance, { money(it) }); SourcedRow("Buying power", b.buyingPower, { money(it) })
                b.openOrders?.let { SourcedRow("In open orders", it, { v -> money(v) }) }
            }
            accounts.polymarketError?.let { ErrorCard("Polymarket: $it") { vm.refreshAccounts() } }
        }
        OutlinedButton({ vm.refreshAccounts() }) { Text(stringResource(R.string.test_connections)) }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onSources: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        Section(stringResource(R.string.running_in_the_background)) {
            val resumed = rememberResumeCount()
            val exempt = remember(resumed) { ignoringBattery(ctx) }
            Text(if (exempt) "Battery optimisation: exempt." else "Battery optimisation: ON. Android may pause the engine with the screen off.",
                style = MaterialTheme.typography.bodyMedium, color = if (exempt) LocalDesk.current.gain else LocalDesk.current.warn)
            Spacer(Modifier.size(6.dp))
            OutlinedButton({ requestBatteryExemption(ctx) }) { Text(stringResource(R.string.battery_settings)) }
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.some_phones_samsung_xiaomi_oneplus_also),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SystemSettings(vm)
        BackupSection(vm)
        Section(stringResource(R.string.data)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSources) { Text(stringResource(R.string.data_sources)) }
                OutlinedButton({
                    scope.launch {
                        val uri = vm.exportCsvUri()
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Marketbot orders").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export orders"))
                    }
                }) { Text(stringResource(R.string.export_orders_csv)) }
            }
            Spacer(Modifier.size(6.dp))
            OutlinedButton({ vm.pruneLogs(30) }) { Text(stringResource(R.string.delete_activity_older_than_30_days)) }
            Text(stringResource(R.string.orders_fills_and_settlements_are_kept_fo), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(stringResource(R.string.about)) {
            Text(stringResource(R.string.marketbot_2, (com.marketbot.app.BuildConfig.VERSION_NAME).toString()), style = MaterialTheme.typography.bodyMedium)
            UpdateRow(vm)
            Text(stringResource(R.string.strategies_are_ports_of_pokeehokee_weath),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel, onBack: () -> Unit, onFind: (String) -> Unit = {}) {
    val audit by vm.audit.collectAsState()
    val now = System.currentTimeMillis()
    fun group(key: String) = when {
        key.startsWith("kalshi") -> "Kalshi"; key.startsWith("polymarket") || key.startsWith("pm.") -> "Polymarket"
        key.startsWith("btc") -> "BTC price sources"; else -> "Calculated and settings"
    }
    val groups = audit.groupBy { group(it.key) }.toSortedMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(stringResource(R.string.data_sources), style = MaterialTheme.typography.headlineSmall)
            }
            Text(stringResource(R.string.the_latest_reading_of_every_value_the_ap),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            val counts = audit.groupingBy { it.source }.eachCount()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                counts.forEach { (s, n) -> runCatching { Source.valueOf(s) }.getOrNull()?.let { Row(verticalAlignment = Alignment.CenterVertically) { SourceChip(it); Text(" $n", style = MaterialTheme.typography.labelSmall) } } }
            }
        }
        if (audit.isEmpty()) item { Text(stringResource(R.string.nothing_read_yet_values_appear_once_a_st), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        groups.forEach { (g, rows) ->
            item(key = g) {
                Section(g) {
                    rows.sortedBy { it.key }.forEach { a ->
                        val ageMin = (now - a.at) / 60_000
                        val stale = ageMin > 60
                        ValueRow(a.key.substringAfter('.').replace('_', ' '), a.value.take(24), runCatching { Source.valueOf(a.source) }.getOrNull(), a.origin, a.note, Instant.ofEpochMilli(a.at))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when { ageMin < 1 -> "just now"; ageMin < 60 -> "$ageMin min ago"; ageMin < 2880 -> "${ageMin / 60} h ago"; else -> "${ageMin / 1440} days ago" } +
                                if (stale) " · stale" else "", style = MaterialTheme.typography.labelSmall,
                                color = if (stale) LocalDesk.current.warn else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton({ onFind(a.key.substringAfter('.').replace('_', ' ')) }) { Text(stringResource(R.string.find)) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** How a strategy makes and loses money, and every setting explained. */
@Composable
fun HelpScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    val s = vm.strategies.first { it.id == id }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(stringResource(R.string.how_works, (s.name).toString()), style = MaterialTheme.typography.headlineSmall)
        }
        Section(stringResource(R.string.how_it_makes_money)) { Text(s.help.makes, style = MaterialTheme.typography.bodyMedium) }
        Section(stringResource(R.string.how_it_loses_money)) { Text(s.help.loses, style = MaterialTheme.typography.bodyMedium) }
        if (s.help.notes.isNotEmpty()) Section(stringResource(R.string.good_to_know)) { s.help.notes.forEach { Text(stringResource(R.string.s_2, (it).toString()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) } }
        Section(stringResource(R.string.every_setting)) {
            s.params.forEach { p ->
                Text(p.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(p.help + " Default: ${p.default.ifEmpty { "none" }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Section(stringResource(R.string.evidence)) {
            EvidenceBadge(s.evidence.level)
            Text(s.evidence.headline, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.source, (s.evidence.source).toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
