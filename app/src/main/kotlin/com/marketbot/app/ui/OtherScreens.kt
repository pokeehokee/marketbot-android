package com.marketbot.app.ui

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
fun ActivityScreen(vm: MainViewModel) {
    val events by vm.events.collectAsState()
    var level by remember { mutableStateOf<String?>(null) }
    var strat by remember { mutableStateOf<String?>(null) }
    val shown = events.filter { (level == null || it.level == level) && (strat == null || it.strategyId == strat) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(level == null, { level = null }, { Text("All") })
                listOf("TRADE", "WARN", "ERROR", "INFO").forEach { l -> FilterChip(level == l, { level = if (level == l) null else l }, { Text(l.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(strat == null, { strat = null }, { Text("Every strategy") })
                vm.strategies.forEach { s -> FilterChip(strat == s.id, { strat = if (strat == s.id) null else s.id }, { Text(s.name) }) }
            }
        }
        if (shown.isEmpty()) item { Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.id }) { e ->
            var open by remember { mutableStateOf(false) }
            val d = LocalDesk.current
            val c = when (e.level) { "ERROR" -> d.loss; "WARN" -> d.warn; "TRADE" -> d.gain; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.level, style = MaterialTheme.typography.labelSmall, color = c, modifier = Modifier.width(52.dp))
                    Text(Instant.ofEpochMilli(e.at).short(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.strategies.firstOrNull { it.id == e.strategyId }?.name ?: "Engine", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(e.message, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 3)
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
    val accounts by vm.accounts.collectAsState()
    var kId by remember { mutableStateOf("") }
    var kPem by remember { mutableStateOf("") }
    var pId by remember { mutableStateOf("") }
    var pSecret by remember { mutableStateOf("") }
    val pickPem = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { kPem = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty() }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        Text("Keys are encrypted with a key held in the phone's secure hardware and never leave the device except to sign requests. Paper mode needs no keys.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("Kalshi") {
            Text(if (vm.hasKalshi()) "Key saved (id ending ${vm.kalshiHint()})." else "No key saved.", style = MaterialTheme.typography.bodyMedium)
            Text("Create one at kalshi.com > Account > API keys. You get a key id and a private key file (.pem).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(kId, { kId = it }, Modifier.fillMaxWidth(), label = { Text("Key id") }, singleLine = true)
            OutlinedTextField(kPem, { kPem = it }, Modifier.fillMaxWidth(), label = { Text("Private key (paste PEM)") }, minLines = 3, maxLines = 6,
                visualTransformation = if (kPem.length > 60) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickPem.launch(arrayOf("*/*")) }) { Text("Import .pem file") }
                Button({ vm.saveKalshi(kId.trim(), kPem); kId = ""; kPem = "" }, enabled = kId.isNotBlank() && kPem.isNotBlank()) { Text("Save") }
                if (vm.hasKalshi()) TextButton({ vm.clearKalshi() }) { Text("Remove") }
            }
            accounts.kalshi?.let { b ->
                SourcedRow("Cash", b.cash, { money(it) }); b.portfolioValue?.let { SourcedRow("Positions", it, { v -> money(v) }) }
                b.byExchangeIndex.toSortedMap().forEach { (i, v) -> SourcedRow("Shard $i cash${if (i == 2) " (crypto)" else ""}", v, { money(it) }) }
            }
            accounts.kalshiError?.let { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
            Text("BTC deep-ITM trades on Kalshi's crypto shard (index 2), which needs its own cash.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KalshiAccountOptions(vm)
        }

        Section("Polymarket US") {
            Text(if (vm.hasPolymarket()) "Key saved (id ending ${vm.polymarketHint()})." else "No key saved.", style = MaterialTheme.typography.bodyMedium)
            Text("Create one at polymarket.us/developer after finishing KYC. The secret is shown once.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(pId, { pId = it }, Modifier.fillMaxWidth(), label = { Text("Key id") }, singleLine = true)
            OutlinedTextField(pSecret, { pSecret = it }, Modifier.fillMaxWidth(), label = { Text("Secret key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.savePolymarket(pId.trim(), pSecret.trim()); pId = ""; pSecret = "" }, enabled = pId.isNotBlank() && pSecret.isNotBlank()) { Text("Save") }
                if (vm.hasPolymarket()) TextButton({ vm.clearPolymarket() }) { Text("Remove") }
            }
            accounts.polymarket?.let { b ->
                SourcedRow("Balance", b.currentBalance, { money(it) }); SourcedRow("Buying power", b.buyingPower, { money(it) })
                b.openOrders?.let { SourcedRow("In open orders", it, { v -> money(v) }) }
            }
            accounts.polymarketError?.let { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton({ vm.refreshAccounts() }) { Text("Test connections") }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onSources: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Section("Running in the background") {
            Text(if (ignoringBattery(ctx)) "Battery optimisation: exempt." else "Battery optimisation: ON. Android may pause the engine with the screen off.",
                style = MaterialTheme.typography.bodyMedium, color = if (ignoringBattery(ctx)) LocalDesk.current.gain else LocalDesk.current.warn)
            Spacer(Modifier.size(6.dp))
            OutlinedButton({ requestBatteryExemption(ctx) }) { Text("Battery settings") }
            Spacer(Modifier.size(6.dp))
            Text("Some phones (Samsung, Xiaomi, OnePlus) also kill background apps in their own battery manager; set Marketbot to Unrestricted there. Keep the phone charging and on Wi-Fi for BTC deep-ITM, which re-quotes every few seconds.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SystemSettings(vm)
        Section("Data") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onSources) { Text("Data sources") }
                OutlinedButton({
                    scope.launch {
                        val uri = vm.exportCsvUri()
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Marketbot orders").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export orders"))
                    }
                }) { Text("Export orders (CSV)") }
            }
            Spacer(Modifier.size(6.dp))
            OutlinedButton({ vm.pruneLogs(30) }) { Text("Delete activity older than 30 days") }
            Text("Orders, fills and settlements are kept forever; they are the record. Routine activity lines are deleted after 14 days automatically.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("About") {
            Text("Marketbot ${com.marketbot.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("Strategies are ports of pokeehokee/weatherbot and pokeehokee/polymarket, tested against the original Python on recorded inputs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val audit by vm.audit.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text("Data sources", style = MaterialTheme.typography.headlineSmall)
            }
            Text("The latest reading of every value the app has used, with where it came from. Values from the exchange APIs are always preferred; CALC means the API has no field for it and the formula is shown.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            val counts = audit.groupingBy { it.source }.eachCount()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                counts.forEach { (s, n) -> runCatching { Source.valueOf(s) }.getOrNull()?.let { Row(verticalAlignment = Alignment.CenterVertically) { SourceChip(it); Text(" $n", style = MaterialTheme.typography.labelSmall) } } }
            }
        }
        if (audit.isEmpty()) item { Text("Nothing read yet. Values appear once a strategy runs or you refresh balances.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(audit, key = { it.key }) { a ->
            ValueRow(a.key, a.value.take(24), runCatching { Source.valueOf(a.source) }.getOrNull(), a.origin, a.note, Instant.ofEpochMilli(a.at))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

