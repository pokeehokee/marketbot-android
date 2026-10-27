package com.marketbot.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.marketbot.app.data.toRow
import com.marketbot.core.engine.ArmRecord
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.PreflightResult
import com.marketbot.core.strategy.Requirement
import com.marketbot.core.strategy.Stat
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.money
import com.marketbot.core.strategies.deepitm.DeepItmStrategy
import com.marketbot.core.strategies.rewards.RewardFarm
import kotlinx.coroutines.launch

@Composable
fun StrategyScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    val s = vm.strategies.first { it.id == id }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleLarge)
                Text(s.venue.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val st by vm.status.collectAsState()
            vm.modes.collectAsState().value
            ModePill(st[s.id]?.mode ?: vm.modeOf(s.id), st[s.id]?.armed == true)
        }
        PrimaryTabRow(tab) {
            listOf("Overview", "Settings", "Go live", "Orders").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> Overview(vm, s)
            1 -> SettingsForm(vm, s)
            2 -> GoLive(vm, s)
            else -> Orders(vm, s)
        }
    }
}

@Composable
private fun Overview(vm: MainViewModel, s: Strategy) {
    val status by vm.status.collectAsState()
    val st = status[s.id]
    var stats by remember { mutableStateOf<List<Stat>>(emptyList()) }
    LaunchedEffect(st?.lastRunAt) { stats = vm.stats(s) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(s.summary, style = MaterialTheme.typography.bodyLarge)
        Section("Mode") {
            val mode = st?.mode ?: vm.modeOf(s.id)
            ModeSelector(if (mode == Mode.LIVE) Mode.LIVE else mode, s.supportedModes - Mode.LIVE) { vm.setMode(s, it) }
            Spacer(Modifier.size(8.dp))
            ValueRow("Last pass", st?.lastRunAt?.short() ?: "never", null)
            ValueRow("Result", st?.lastSummary ?: "-", null)
            st?.nextRunAt?.let { ValueRow("Next pass", it.short(), null) }
            st?.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.loss) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.runNow(s) }) { Text("Run one pass now") }
                if (s is DeepItmStrategy || s is RewardFarm) OutlinedButton({ vm.resetKill(s) }) { Text("Reset stop") }
            }
        }
        Section("Results") {
            if (stats.isEmpty()) Text("Nothing yet. Turn on paper mode to start a record.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            stats.forEach { ValueRow(it.label, it.value.value, it.value.source, it.value.origin, it.value.note, it.value.at) }
        }
        Section("Evidence") {
            EvidenceBadge(s.evidence.level)
            Spacer(Modifier.size(6.dp))
            Text(s.evidence.headline, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(6.dp))
            s.evidence.details.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.size(6.dp))
            Text("Source: ${s.evidence.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsForm(vm: MainViewModel, s: Strategy) {
    val values = remember { mutableStateMapOf<String, String>() }
    val errors = remember { mutableStateListOf<String>() }
    LaunchedEffect(s.id) { values.putAll(vm.config(s).asMap) }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Defaults are the measured values from the original repos. Changing them changes what the evidence says.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(s.params, key = { it.key }) { p ->
            Column {
                when (p) {
                    is Param.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(values[p.key]?.toBooleanStrictOrNull() ?: p.defaultValue, { values[p.key] = it.toString() })
                    }
                    else -> {
                        val unit = when (p) { is Param.Num -> p.unit; is Param.Int_ -> p.unit; else -> "" }
                        val range = when (p) { is Param.Num -> "${p.min} to ${p.max}"; is Param.Int_ -> "${p.min} to ${p.max}"; else -> null }
                        OutlinedTextField(values[p.key] ?: p.default, { values[p.key] = it }, Modifier.fillMaxWidth(),
                            label = { Text(p.label + if (unit.isNotEmpty()) " ($unit)" else "") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = if (p is Param.Text) KeyboardType.Text else KeyboardType.Decimal),
                            supportingText = { Text(listOfNotNull(p.help, range?.let { "Allowed: $it. Default ${p.default}." }).joinToString(" ")) })
                    }
                }
            }
        }
        item {
            errors.forEach { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.saveConfig(s, values.toMap()) { e -> errors.clear(); errors.addAll(e) } }) { Text("Save") }
                OutlinedButton({ values.clear(); values.putAll(s.params.associate { it.key to it.default }) }) { Text("Restore defaults") }
            }
        }
    }
}

@Composable
private fun GoLive(vm: MainViewModel, s: Strategy) {
    val scope = rememberCoroutineScope()
    var reqs by remember { mutableStateOf<List<Requirement>?>(null) }
    var pre by remember { mutableStateOf<PreflightResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var arm by remember { mutableStateOf<ArmRecord?>(null) }
    var typed by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val acks = remember { mutableStateListOf<String>() }
    val killed by vm.killed.collectAsState()
    suspend fun reload() { reqs = vm.requirements(s); arm = vm.armRecord(s) }
    LaunchedEffect(s.id) { reload() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (Mode.LIVE !in s.supportedModes) { Text("This strategy has no live order path."); return@Column }
        arm?.let { a ->
            Section("Live is armed") {
                Text("Armed ${a.at}. Acknowledged: ${a.acknowledged.joinToString().ifEmpty { "none outstanding" }}.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Button({ vm.disarm(s); scope.launch { reload() } }, colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.loss)) { Text("Disarm and switch to paper") }
            }
        }
        if (killed) Text("The kill switch is on. Turn it off on Home before arming anything.", color = LocalDesk.current.loss)
        Section("Checklist") {
            val r = reqs
            if (r == null) Text("Checking...", style = MaterialTheme.typography.bodyMedium)
            r?.forEach { q ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    val d = LocalDesk.current
                    if (q.hard) Icon(if (q.met) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (q.met) d.gain else d.loss, modifier = Modifier.size(20.dp))
                    else Checkbox(q.met || q.id in acks, { on -> if (on) acks.add(q.id) else acks.remove(q.id) }, enabled = !q.met, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(q.label, style = MaterialTheme.typography.bodyMedium)
                        Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton({ scope.launch { reload() } }) { Text("Re-check") }
        }
        Section("Preflight") {
            Text("Reads your balance and the markets, and where the venue allows it places one tiny test order and cancels it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Button({ busy = true; scope.launch { pre = vm.preflight(s); busy = false; reload() } }, enabled = !busy) { Text(if (busy) "Running..." else "Run preflight") }
            pre?.let { p ->
                Spacer(Modifier.size(8.dp))
                p.steps.forEach { (t, ok) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = if (ok) LocalDesk.current.gain else LocalDesk.current.loss)
                        Spacer(Modifier.width(8.dp)); Text(t, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(p.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (arm == null) Section("Arm live trading") {
            val ready = reqs?.all { it.met || !it.hard } == true && reqs?.filter { !it.hard && !it.met }?.all { it.id in acks } == true
            Text("Type LIVE to confirm that this strategy may place real orders with your money.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type LIVE") })
            Spacer(Modifier.size(8.dp))
            Button({ scope.launch { val (ok, m) = vm.arm(s, acks.toSet()); msg = m; if (ok) reload() } },
                enabled = ready && typed.trim() == "LIVE" && !killed,
                colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.live)) { Text("Arm live trading") }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!ready) Text("Meet every red item and tick every acknowledgement first.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Orders(vm: MainViewModel, s: Strategy) {
    val rows by vm.quotes(s.id).collectAsState(initial = emptyList())
    var showPaper by remember { mutableStateOf(true) }
    val shown = rows.map { it.toRow() }.filter { showPaper || it.mode == Mode.LIVE }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} orders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("Show paper", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(6.dp))
                Switch(showPaper, { showPaper = it })
            }
        }
        if (shown.isEmpty()) item { Text("No orders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.id }) { q ->
            Section(q.ticker, trailing = { ModePill(q.mode, q.mode == Mode.LIVE) }) {
                ValueRow("Order", "${q.action.replace('_', ' ')} ${q.contracts.toInt()} @ ${"%.3f".format(q.price)}", com.marketbot.core.provenance.Source.CONFIG, "the strategy's own order")
                ValueRow("Placed", q.placedAt.short(), null)
                q.filledContracts?.let { ValueRow("Filled", "%.0f of %.0f".format(it, q.contracts),
                    if (q.fillSource?.startsWith("paper") == true) com.marketbot.core.provenance.Source.PAPER_MODEL else com.marketbot.core.provenance.Source.KALSHI_API.takeIf { q.venue == "kalshi" } ?: com.marketbot.core.provenance.Source.POLYMARKET_API,
                    q.fillSource) }
                q.cancelledAt?.let { ValueRow("Cancelled", it.short(), null) }
                q.resolvedAt?.let { ValueRow("Settled", "${it.short()}  ${q.result?.uppercase() ?: ""}", null) }
                q.pnl?.let { ValueRow(if (q.hypothetical == true) "P&L if filled (not counted)" else "P&L", money(it), q.pnlSource, q.pnlOrigin, valueColor = if (q.hypothetical == true) null else moneyColor(it)) }
                q.orderId?.let { Text("Order id $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
