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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
fun StrategyScreen(vm: MainViewModel, id: String, onBack: () -> Unit, initialTab: Int = 0) {
    val s = vm.strategies.first { it.id == id }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initialTab) }
    val running by vm.engineRunning.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleLarge)
                Text(s.venue.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val st by vm.status.collectAsState()
            vm.modes.collectAsState().value
            ModePill(st[s.id]?.mode ?: vm.modeOf(s.id), st[s.id]?.armed == true, running)
        }
        androidx.compose.material3.PrimaryScrollableTabRow(tab, edgePadding = 8.dp) {
            listOf("Overview", "Parameters", "Go live", "Orders").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        when (tab) {
            0 -> Overview(vm, s) { tab = 2 }
            1 -> SettingsForm(vm, s)
            2 -> GoLive(vm, s)
            else -> OrdersList(vm, s)
        }
    }
}

@Composable
private fun Overview(vm: MainViewModel, s: Strategy, onGoLive: () -> Unit) {
    val status by vm.status.collectAsState()
    val ui by vm.strategyUi.collectAsState()
    val running by vm.engineRunning.collectAsState()
    val st = status[s.id]
    val stats = ui[s.id]?.stats
    LaunchedEffect(st?.lastRunAt) { vm.loadStats(s) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(s.summary, style = MaterialTheme.typography.bodyLarge)
        Section("Mode") {
            val mode = st?.mode ?: vm.modeOf(s.id)
            ModeControl(vm, s, mode, onGoLive)
            if (mode != Mode.OFF && !running) Text("The engine is off, so nothing runs. Start it from Home.", style = MaterialTheme.typography.bodySmall,
                color = LocalDesk.current.warn, modifier = Modifier.padding(top = 6.dp))
            if (s.needsBtcIndex && mode != Mode.OFF && running) WarmUp(vm)
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
            when {
                stats == null -> repeat(3) { SkeletonLine() }
                stats.isEmpty() -> Text("Nothing yet. Turn on paper mode to start a record.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> stats.forEach { ValueRow(it.label, it.value.value, it.value.source, it.value.origin, it.value.note, it.value.at) }
            }
        }
        RecordSection(vm, s)
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

/** After a restart the BTC index needs 5 minutes of prices and the candle volatility before deep-ITM can quote. */
@Composable
private fun WarmUp(vm: MainViewModel) {
    val w by androidx.compose.runtime.produceState(vm.btcWarmup()) { while (true) { kotlinx.coroutines.delay(1_000); value = vm.btcWarmup() } }
    val (f, candles, venues) = w
    if (f >= 1.0 && candles) return
    Column(Modifier.padding(top = 8.dp)) {
        Text("Warming up volatility: ${"%.0f".format(f * 5)} of 5 min${if (!candles) ", waiting for candle data" else ""} · $venues of 4 price sources",
            style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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

private fun Modifier.checkToggle(on: Boolean, onChange: (Boolean) -> Unit) =
    this.then(Modifier.toggleable(value = on, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange))

@Composable
private fun GoLive(vm: MainViewModel, s: Strategy) {
    val confirm = com.marketbot.app.rememberConfirm()
    val all by vm.strategyUi.collectAsState()
    val ui = all[s.id] ?: MainViewModel.StrategyUi()
    var typed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val acks = remember { mutableStateListOf<String>() }
    val killed by vm.killed.collectAsState()
    LaunchedEffect(s.id) { vm.loadGoLive(s) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (Mode.LIVE !in s.supportedModes) { Text("This strategy has no live order path."); return@Column }
        ui.arm?.let { a ->
            Section("Live is armed") {
                Text("Armed ${runCatching { java.time.Instant.parse(a.at).short() }.getOrDefault(a.at)}. Acknowledged: ${a.acknowledged.joinToString().ifEmpty { "none outstanding" }}.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Text(s.onLeaveLive, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Button({ vm.disarmLive(s) }, colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.loss)) { Text("Disarm and switch to paper") }
            }
        }
        if (killed) Text("The kill switch is on. Turn it off on Home before arming anything.", color = LocalDesk.current.loss)
        Section("Checklist") {
            val r = ui.reqs
            if (r == null) repeat(4) { SkeletonLine() }
            r?.forEach { q ->
                val ticked = q.met || q.id in acks
                val rowMod = if (!q.hard && !q.met) Modifier.fillMaxWidth().checkToggle(ticked) { on -> if (on) acks.add(q.id) else acks.remove(q.id) }
                    else Modifier.fillMaxWidth()
                Row(rowMod.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    val d = LocalDesk.current
                    if (q.hard) Icon(if (q.met) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, if (q.met) "Met" else "Not met",
                        tint = if (q.met) d.gain else d.loss, modifier = Modifier.size(20.dp))
                    else Icon(if (ticked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                        tint = if (ticked) d.gain else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(q.label, style = MaterialTheme.typography.bodyMedium)
                        Text(q.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton({ vm.loadGoLive(s, force = true) }) { Text("Re-check") }
        }
        Section("Preflight") {
            Text("Reads your balance and the markets, and where the venue allows it places one tiny test order and cancels it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Button({ vm.runPreflight(s) }, enabled = !ui.preflightBusy) { Text(if (ui.preflightBusy) "Running..." else "Run preflight") }
            ui.preflight?.let { p ->
                Spacer(Modifier.size(8.dp))
                p.steps.forEach { (t, ok) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, if (ok) "Passed" else "Failed", Modifier.size(18.dp),
                            tint = if (ok) LocalDesk.current.gain else LocalDesk.current.loss)
                        Spacer(Modifier.width(8.dp)); Text(t, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(p.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (ui.arm == null) Section("Arm live trading") {
            val reqs = ui.reqs
            val ready = reqs?.all { it.met || !it.hard } == true && reqs.filter { !it.hard && !it.met }.all { it.id in acks }
            Text("Type LIVE to confirm that this strategy may place real orders with your money.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text("Type LIVE") })
            Spacer(Modifier.size(8.dp))
            Button({ confirm("Arm ${s.name} for live trading") { vm.armLive(s, acks.toSet()) } },
                enabled = ready && typed.trim() == "LIVE" && !killed,
                colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.live)) { Text("Arm live trading") }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!ready) Text("Meet every red item and tick every acknowledgement first.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
