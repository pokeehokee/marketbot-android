package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
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
fun StrategyScreen(vm: MainViewModel, id: String, onBack: () -> Unit, initialTab: Int = 0, showBack: Boolean = true, onHelp: ((String) -> Unit)? = null) {
    val s = vm.strategies.first { it.id == id }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initialTab) }
    val running by vm.engineRunning.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var askLeave by remember { mutableStateOf(false) }
            // The system back gesture asks too, from every tab (the Parameters tab has its own).
            androidx.activity.compose.BackHandler(showBack && tab != 1 && vm.dirty(s)) { askLeave = true }
            if (showBack) IconButton({ if (vm.dirty(s)) askLeave = true else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            else Spacer(Modifier.width(12.dp))
            if (askLeave) androidx.compose.material3.AlertDialog(onDismissRequest = { askLeave = false },
                confirmButton = { androidx.compose.material3.TextButton({ vm.drafts.remove(s.id); askLeave = false; onBack() }) { Text(stringResource(R.string.discard_changes)) } },
                dismissButton = { androidx.compose.material3.TextButton({ askLeave = false }) { Text(stringResource(R.string.keep_editing)) } },
                title = { Text(stringResource(R.string.unsaved_changes)) }, text = { Text(stringResource(R.string.has_changed_settings_that_are_not_saved, (s.name).toString())) })
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleLarge)
                Text(s.venue.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val st by vm.status.collectAsState()
            vm.modes.collectAsState().value
            ModePill(st[s.id]?.mode ?: vm.modeOf(s.id), st[s.id]?.armed == true, running)
            onHelp?.let { IconButton({ it(s.id) }) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.HelpOutline, "How ${s.name} works") } }
        }
        androidx.compose.material3.PrimaryScrollableTabRow(tab, edgePadding = 8.dp) {
            listOf("Overview", "Parameters", "Go live", "Orders").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        when (tab) {
            0 -> Overview(vm, s) { tab = 2 }
            1 -> ParametersForm(vm, s, onLeave = onBack)
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
    val busy by vm.inFlight.collectAsState()
    LaunchedEffect(st?.lastRunAt) { vm.loadStats(s) }
    // A strategy's own figures are old once a few of its passes have gone by without them.
    val staleAfter = staleAfter(st?.lastRunAt, st?.nextRunAt)
    // Pull down to read the strategy's figures again (it reads the venue where a figure comes from one).
    androidx.compose.material3.pulltorefresh.PullToRefreshBox("stats:${s.id}" in busy, { vm.loadStats(s) }) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(s.summary, style = MaterialTheme.typography.bodyLarge)
        Section(stringResource(R.string.mode)) {
            val mode = st?.mode ?: vm.modeOf(s.id)
            ModeControl(vm, s, mode, onGoLive)
            if (mode != Mode.OFF && !running) Text(stringResource(R.string.the_engine_is_off_so_nothing_runs_start), style = MaterialTheme.typography.bodySmall,
                color = LocalDesk.current.warn, modifier = Modifier.padding(top = 6.dp))
            if (s.needsBtcIndex && mode != Mode.OFF && running) WarmUp(vm)
            TradingStateLine(st)
            Spacer(Modifier.size(8.dp))
            ValueRow("Last pass", st?.lastRunAt?.short() ?: "never", null)
            ValueRow("Result", st?.lastSummary ?: "-", null)
            st?.nextRunAt?.let { ValueRow("Next pass", it.short(), null) }
            st?.funnel?.let { FunnelView(it) }
            st?.lastError?.let { ErrorCard(it) { vm.runNow(s) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.runNow(s) }, enabled = "run:${s.id}" !in busy) {
                    Text(stringResource(if ("run:${s.id}" in busy) R.string.running else R.string.run_one_pass_now)) }
                if (s is DeepItmStrategy || s is RewardFarm) OutlinedButton({ vm.resetKill(s) }) { Text(stringResource(R.string.reset_stop)) }
            }
        }
        Section(stringResource(R.string.results)) {
            when {
                stats == null -> repeat(3) { SkeletonLine() }
                stats.isEmpty() -> Text(stringResource(R.string.nothing_yet_turn_on_paper_mode_to_start), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> androidx.compose.runtime.CompositionLocalProvider(LocalStaleAfter provides staleAfter) {
                    stats.forEach { ValueRow(it.label, it.value.value, it.value.source, it.value.origin, it.value.note, it.value.at) } }
            }
        }
        RecordSection(vm, s)
        HealthSection(vm, s)
        OutcomesSection(vm, s)
        MarkoutSection(vm, s)
        CalibrationSection(vm, s)
        CollateralSection(vm, s)
        Section(stringResource(R.string.evidence)) {
            EvidenceBadge(s.evidence.level)
            Spacer(Modifier.size(6.dp))
            Text(s.evidence.headline, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(6.dp))
            s.evidence.details.forEach { Text(stringResource(R.string.s_3, (it).toString()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.source, (s.evidence.source).toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

private fun Modifier.checkToggle(on: Boolean, onChange: (Boolean) -> Unit) =
    this.then(Modifier.toggleable(value = on, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange))

@Composable
private fun GoLive(vm: MainViewModel, s: Strategy) {
    val confirm = com.marketbot.app.rememberConfirm()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val all by vm.strategyUi.collectAsState()
    val ui = all[s.id] ?: MainViewModel.StrategyUi()
    var typed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val acks = remember { mutableStateListOf<String>() }
    val killed by vm.killed.collectAsState()
    val busy by vm.inFlight.collectAsState()
    LaunchedEffect(s.id) { vm.loadGoLive(s) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (Mode.LIVE !in s.supportedModes) { Text(stringResource(R.string.this_strategy_has_no_live_order_path)); return@Column }
        ui.arm?.let { a ->
            Section(stringResource(R.string.live_is_armed)) {
                Text("Armed ${runCatching { java.time.Instant.parse(a.at).short() }.getOrDefault(a.at)}. Acknowledged: ${a.acknowledged.joinToString().ifEmpty { "none outstanding" }}.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Text(s.onLeaveLive, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Button({ vm.disarmLive(s) }, colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.loss)) { Text(stringResource(R.string.disarm_and_switch_to_paper)) }
            }
        }
        if (killed) Text(stringResource(R.string.the_kill_switch_is_on_turn_it_off_on_hom), color = LocalDesk.current.loss)
        Section(stringResource(R.string.checklist)) {
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
            OutlinedButton({ vm.loadGoLive(s, force = true) }) { Text(stringResource(R.string.re_check)) }
        }
        Section(stringResource(R.string.preflight)) {
            Text(stringResource(R.string.reads_your_balance_and_the_markets_and_w),
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
        if (ui.arm == null) Section(stringResource(R.string.arm_live_trading)) {
            val reqs = ui.reqs
            val ready = reqs?.all { it.met || !it.hard } == true && reqs.filter { !it.hard && !it.met }.all { it.id in acks }
            Text(stringResource(R.string.type_live_to_confirm_that_this_strategy), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(typed, { typed = it }, singleLine = true, label = { Text(stringResource(R.string.type_live)) })
            Spacer(Modifier.size(8.dp))
            Button({ confirm("Arm ${s.name} for live trading") { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); vm.armLive(s, acks.toSet()) } },
                enabled = ready && typed.trim() == "LIVE" && !killed && "arm:${s.id}" !in busy,
                colors = ButtonDefaults.buttonColors(containerColor = LocalDesk.current.live)) { Text(stringResource(R.string.arm_live_trading)) }
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!ready) Text(stringResource(R.string.meet_every_red_item_and_tick_every_ackno), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
