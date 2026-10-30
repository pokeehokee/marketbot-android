@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.marketbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marketbot.app.NotifyPrefs
import com.marketbot.app.R
import com.marketbot.core.engine.StrategyStatus
import com.marketbot.core.strategy.Calibration
import com.marketbot.core.strategy.Markout
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Periods
import com.marketbot.core.strategy.Protection
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.TradingState
import com.marketbot.core.strategy.money
import java.time.format.DateTimeFormatter

@Composable
fun stateColor(s: TradingState?): Color = when (s) {
    TradingState.ACTIVE, null -> LocalDesk.current.gain
    TradingState.REDUCING -> LocalDesk.current.warn
    TradingState.HALTED -> LocalDesk.current.loss
}

/** ACTIVE / REDUCING / HALTED with its reason code, as one line. Nothing while off. */
@Composable
fun TradingStateLine(st: StrategyStatus?) {
    val state = st?.state ?: return
    if (st.mode == Mode.OFF) return
    val c = stateColor(state)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.xs)) {
        Text(when (state) { TradingState.ACTIVE -> "ACTIVE"; TradingState.REDUCING -> "NO NEW ORDERS"; TradingState.HALTED -> "HALTED" },
            style = MaterialTheme.typography.labelSmall, color = c,
            modifier = Modifier.clip(Radius.chip).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
        st.reason?.let { r ->
            Text("  ${r.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private val dayFmtShort = DateTimeFormatter.ofPattern("EEE MMM d")
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

/** A method's own settled record, one line, for lists. */
@Composable
fun MethodRecordLine(r: com.marketbot.core.strategy.MethodRecord?, showEmpty: Boolean = false) {
    if (r == null) {
        if (showEmpty) Text(stringResource(R.string.no_settled_record_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(r.line(), style = MaterialTheme.typography.bodySmall, maxLines = 2,
        color = moneyColor(r.pnl) ?: MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Settled P&L by day, week or month (freqtrade's /daily, /weekly, /monthly), pooled or split by method. */
@Composable
fun PeriodsSection(vm: MainViewModel) {
    var unit by rememberSaveable { mutableStateOf(Periods.Unit.DAY) }
    var mode by rememberSaveable { mutableStateOf(Mode.LIVE) }
    var split by rememberSaveable { mutableStateOf(false) }
    val settled by vm.settled.collectAsState()
    val n = when (unit) { Periods.Unit.DAY -> 7; Periods.Unit.WEEK -> 8; Periods.Unit.MONTH -> 6 }
    val rows = loaded(unit, mode, settled.size, settled.lastOrNull()?.resolvedAt) { vm.periods(mode, unit, n) }
    val byMethod = loaded(unit, mode, settled.size, settled.lastOrNull()?.resolvedAt, split) { if (split) vm.periodsByMethod(mode, unit, n) else emptyMap() }
    val names = vm.strategies.associate { it.id to it.name }
    Section(stringResource(R.string.by_period)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf(Periods.Unit.DAY to "Days", Periods.Unit.WEEK to "Weeks", Periods.Unit.MONTH to "Months").forEach { (u, l) ->
                FilterChip(unit == u, { unit = u }, { Text(l) }) }
            FilterChip(mode == Mode.LIVE, { mode = if (mode == Mode.LIVE) Mode.PAPER else Mode.LIVE }, { Text(if (mode == Mode.LIVE) "Live" else "Paper") })
        }
        FilterChip(split, { split = !split }, { Text(stringResource(R.string.split_by_method)) })
        if (rows == null) { SkeletonLine(); return@Section }
        Row(Modifier.padding(top = Space.s)) {
            listOf("Period" to 2f, "Settled" to 1f, "Lost" to 1f, "P&L" to 1.4f).forEach { (h, w) ->
                Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w),
                    textAlign = if (h == "Period") TextAlign.Start else TextAlign.End) }
        }
        rows.forEach { r ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(when (unit) { Periods.Unit.DAY -> dayFmtShort.format(r.start); Periods.Unit.WEEK -> "Week of " + dayFmtShort.format(r.start); Periods.Unit.MONTH -> monthFmt.format(r.start) },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
                Text("${r.settled}", style = Figures, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("${r.losses}", style = Figures, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text(if (r.settled == 0) "-" else money(r.pnl), style = Figures, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End,
                    color = if (r.settled == 0) MaterialTheme.colorScheme.onSurfaceVariant else moneyColor(r.pnl) ?: MaterialTheme.colorScheme.onSurface)
            }
            if (split) byMethod.orEmpty().forEach { (id, t) ->
                val m = t.firstOrNull { it.start == r.start }?.takeIf { it.settled > 0 } ?: return@forEach
                Row(Modifier.padding(start = Space.m, bottom = 2.dp)) {
                    Text(names[id] ?: id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(2f), maxLines = 1)
                    Text("${m.settled}", style = Figures, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text("${m.losses}", style = Figures, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    Text(money(m.pnl), style = Figures, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End,
                        color = moneyColor(m.pnl) ?: MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** How the price moved after fills: an hour's warning of adverse selection instead of a day's. */
@Composable
fun MarkoutSection(vm: MainViewModel, s: Strategy) {
    if (s.outcomeBands == null) return
    val status by vm.status.collectAsState()
    val data = loaded(status[s.id]?.lastRunAt) { val (m, rows) = vm.recordRows(s); m to Markout.summary(rows) }
    val (mode, lines) = data ?: return
    if (lines.all { it.n == 0 }) return
    Section(stringResource(R.string.after_the_fill, mode.name.lowercase())) {
        Text(stringResource(R.string.markout_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { l ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${l.minutes} min later", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
                Text(l.meanCents?.let { "%+.2fc".format(it) } ?: "-", style = Figures, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                    color = l.meanCents?.let { if (it < -0.5) LocalDesk.current.loss else if (it > 0.5) LocalDesk.current.gain else null } ?: MaterialTheme.colorScheme.onSurface)
                Text(l.shareNegative?.let { "%.0f%% against".format(it * 100) } ?: "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("n=${l.n}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Realised YES rate against the price's probability, by bucket, beside the research's ratio where there is one. */
@Composable
fun CalibrationSection(vm: MainViewModel, s: Strategy) {
    if (s.outcomeBands == null) return
    val status by vm.status.collectAsState()
    val data = loaded(status[s.id]?.lastRunAt) { val (m, rows) = vm.recordRows(s); m to Calibration.table(rows) }
    val (mode, table) = data ?: return
    if (table.isEmpty()) return
    Section(stringResource(R.string.calibration_title, mode.name.lowercase())) {
        Text(stringResource(R.string.calibration_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = Space.s)) {
            listOf("Price" to 1.2f, "Markets" to 1f, "Priced" to 1f, "Settled YES" to 1.2f, "Ratio" to 1f, "Research" to 1f).forEach { (h, w) ->
                Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w)) }
        }
        table.forEach { b ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("%.0f-%.0fc".format(b.lo * 100, b.hi * 100), style = Figures, modifier = Modifier.weight(1.2f))
                Text("${b.n}", style = Figures, modifier = Modifier.weight(1f))
                Text(b.implied?.let { "%.1f%%".format(it * 100) } ?: "-", style = Figures, modifier = Modifier.weight(1f))
                Text(b.realised?.let { "%.1f%%".format(it * 100) } ?: "-", style = Figures, modifier = Modifier.weight(1.2f))
                Text(b.ratio?.let { "%.2f".format(it) } ?: "-", style = Figures, modifier = Modifier.weight(1f),
                    color = b.ratio?.let { if (b.n < 30) null else if (it < 0.9) LocalDesk.current.gain else if (it > 1.0) LocalDesk.current.loss else LocalDesk.current.warn }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Calibration.RESEARCH_RATIO[b.lo]?.let { "%.2f".format(it) } ?: "", style = Figures, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.calibration_footer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Active locks, each with its reason and expiry and a button to lift it now. */
@Composable
fun LocksSection(vm: MainViewModel) {
    val changed by vm.locksChanged.collectAsState()
    val status by vm.status.collectAsState()
    val confirm = com.marketbot.app.rememberConfirm()
    val locks = loaded(changed, status.values.mapNotNull { it.lastRunAt }.maxOrNull()) { vm.activeLocks() } ?: return
    Section(stringResource(R.string.locks_title)) {
        if (locks.isEmpty()) Text(stringResource(R.string.no_locks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        locks.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                Column(Modifier.weight(1f)) {
                    Text("${vm.strategies.firstOrNull { it.id == l.strategyId }?.name ?: l.strategyId} · ${l.mode.name.lowercase()} · ${l.series ?: "all markets"}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${l.reason}. Lifts ${java.time.Instant.parse(l.until).short()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ confirm("Lift this lock now") { vm.unlock(l) } }) { Text(stringResource(R.string.unlock)) }
            }
        }
    }
}

/** The protections, each off until turned on, with its look-back, limit and lock length. */
@Composable
fun ProtectionsSection(vm: MainViewModel) {
    val changed by vm.locksChanged.collectAsState()
    val list = loaded(changed) { vm.protections() } ?: return
    Section(stringResource(R.string.protections_title)) {
        Text(stringResource(R.string.protections_help), style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.warn)
        list.forEach { p ->
            Column(Modifier.padding(top = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.type.label, style = MaterialTheme.typography.titleSmall)
                        Text(p.type.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(p.enabled, { vm.saveProtection(p.copy(enabled = it)) })
                }
                if (p.enabled) {
                    Stepper("Look back", p.lookbackHours, 1, 168, "h") { vm.saveProtection(p.copy(lookbackHours = it)) }
                    if (p.type != Protection.Type.COOLDOWN) Stepper(if (p.type == Protection.Type.LOSS_GUARD) "Losses" else "At least positions", p.tradeLimit, 1, 50, "") {
                        vm.saveProtection(p.copy(tradeLimit = it)) }
                    if (p.type == Protection.Type.MAX_DRAWDOWN) Stepper("Drawdown", p.maxDrawdownUsd.toInt(), 1, 1000, "$") { vm.saveProtection(p.copy(maxDrawdownUsd = it.toDouble())) }
                    Stepper("Lock for", p.stopHours, 1, 168, "h") { vm.saveProtection(p.copy(stopHours = it)) }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton({ onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text((if (unit == "$") "$" else "") + value + (if (unit.isNotEmpty() && unit != "$") " $unit" else ""), style = Figures,
            modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
        TextButton({ onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

/** On / silent / off for each kind of notification. */
@Composable
fun NotificationsSection() {
    val ctx = LocalContext.current
    Section(stringResource(R.string.notifications_title)) {
        NotifyPrefs.KINDS.forEach { (kind, label, help) ->
            var level by remember { mutableStateOf(NotifyPrefs.get(ctx, kind)) }
            Column(Modifier.padding(vertical = Space.xs)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val levels = NotifyPrefs.Level.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = Space.xs)) {
                    levels.forEachIndexed { i, l ->
                        SegmentedButton(level == l, { level = l; NotifyPrefs.set(ctx, kind, l) }, SegmentedButtonDefaults.itemShape(i, levels.size),
                            label = { Text(l.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                if (kind == "safety" && level == NotifyPrefs.Level.OFF) Text(stringResource(R.string.safety_off_warning), style = MaterialTheme.typography.labelSmall,
                    color = LocalDesk.current.loss)
            }
        }
    }
}

/** Live money tied up at each venue: what resting orders would lock and what held positions can lose. */
@Composable
fun ExposureSection(vm: MainViewModel) {
    val settled by vm.settled.collectAsState()
    val status by vm.status.collectAsState()
    val rows = loaded(settled.size, status.values.mapNotNull { it.lastRunAt }.maxOrNull()) { vm.exposure() } ?: return
    if (rows.isEmpty()) return
    Section(stringResource(R.string.exposure_title)) {
        rows.forEach { e ->
            Text(e.venue.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.xs))
            ValueRow("Resting orders", "${e.resting} · ${money(e.restingLocked)} would lock", com.marketbot.core.provenance.Source.DERIVED,
                "sum over unfilled live orders of contracts x money locked per contract (1 - price to sell YES, price to buy)")
            ValueRow("Held positions", "${e.held} · ${money(e.heldAtRisk)} at risk", com.marketbot.core.provenance.Source.DERIVED,
                "filled, unsettled live contracts x money locked per contract: the most they can lose")
            ValueRow("Realised today", money(e.realisedToday), if (e.venue == "kalshi") com.marketbot.core.provenance.Source.KALSHI_API else com.marketbot.core.provenance.Source.POLYMARKET_API,
                "settled live positions today", valueColor = moneyColor(e.realisedToday))
        }
        Text(stringResource(R.string.exposure_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-tap operations, freqtrade-keyboard style: pause or resume new orders, run every pass, check orders. */
@Composable
fun QuickActions(vm: MainViewModel) {
    val busy by vm.inFlight.collectAsState()
    val changed by vm.locksChanged.collectAsState()
    val paused = loaded(changed) { vm.paused() }
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (paused != null) androidx.compose.material3.FilledTonalButton({ vm.resumeAll() }, enabled = "pause" !in busy) {
            Text(stringResource(R.string.resume_new_orders)) }
        else androidx.compose.foundation.layout.Box {
            androidx.compose.material3.OutlinedButton({ menu = true }, enabled = "pause" !in busy) { Text(stringResource(R.string.pause_new_orders)) }
            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                listOf(1L to "For 1 hour", 4L to "For 4 hours", null to "Until I resume").forEach { (h, l) ->
                    androidx.compose.material3.DropdownMenuItem({ Text(l) }, { menu = false; vm.pauseAll(h) }) }
            }
        }
        androidx.compose.material3.OutlinedButton({ vm.runAll() }, enabled = "run_all" !in busy) { Text(stringResource(if ("run_all" in busy) R.string.running else R.string.run_all_now)) }
        androidx.compose.material3.OutlinedButton({ vm.reconcileOrdersNow() }, enabled = "order_recon" !in busy && (vm.hasKalshi() || vm.hasPolymarket())) {
            Text(stringResource(R.string.check_orders)) }
    }
    paused?.let { Text(stringResource(R.string.paused_until, if (it.untilInstant.isAfter(java.time.Instant.now().plusSeconds(86_400L * 365))) "you resume" else it.untilInstant.short()),
        style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.warn) }
}

/**
 * Paper variants: copies of this method with their own settings, run in paper on the
 * same markets, so a change can be judged against the current settings side by side
 * before it is made live.
 */
@Composable
fun VariantsSection(vm: MainViewModel, s: com.marketbot.core.strategy.Strategy, onOpen: (String) -> Unit) {
    val variants = remember(s.id) { vm.variantsOf(s.id) }
    if (variants.isEmpty()) return
    val settled by vm.settled.collectAsState()
    vm.modes.collectAsState().value
    val confirm = com.marketbot.app.rememberConfirm()
    Section(stringResource(R.string.variants_title)) {
        Text(stringResource(R.string.variants_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s is com.marketbot.core.strategies.rewards.RewardFarm) FarmVariantTable(vm, listOf(s) + variants)
        else LedgerVariantTable(vm, listOf(s) + variants, settled)
        variants.forEach { v ->
            val mode = vm.modeOf(v.id)
            Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text("Variant ${v.id.substringAfterLast('@').uppercase()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(mode == Mode.PAPER, { on -> vm.setMode(v, if (on) Mode.PAPER else Mode.OFF) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                TextButton({ onOpen(v.id) }) { Text(stringResource(R.string.variant_settings)) }
                TextButton({ confirm("Replace variant ${v.id.substringAfterLast('@').uppercase()}'s settings with the current ones") { vm.copyConfig(s, v) } }) {
                    Text(stringResource(R.string.variant_copy)) }
            }
        }
        Text(stringResource(R.string.variants_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun variantLabel(v: com.marketbot.core.strategy.Strategy) = if (v.baseId == null) "Current" else "Variant ${v.id.substringAfterLast('@').uppercase()}"

@Composable
private fun LedgerVariantTable(vm: MainViewModel, all: List<com.marketbot.core.strategy.Strategy>, settled: List<com.marketbot.core.store.QuoteRow>) {
        val rows = remember(settled, all.first().id) { all.map { it to vm.paperComparison(it.id) } }
        Row(Modifier.fillMaxWidth().padding(top = Space.s)) {
            listOf("" to 1.6f, "Settled" to 1f, "P&L" to 1f, "Per contract" to 1.2f).forEach { (h, w) ->
                Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w)) }
        }
        rows.forEach { (v, c) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(variantLabel(v), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.6f))
                Text("${c.n}", style = Figures, modifier = Modifier.weight(1f))
                Text(if (c.n == 0) "-" else money(c.pnl), style = Figures, color = moneyColor(c.pnl) ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(c.perContract?.let { "%+.2fc".format(it * 100) } ?: "-", style = Figures, modifier = Modifier.weight(1.2f))
            }
        }
}

/** The farm keeps its own paper book: realised fill P&L, maker rebates and the reward estimate, per variant. */
@Composable
private fun FarmVariantTable(vm: MainViewModel, all: List<com.marketbot.core.strategy.Strategy>) {
    val status by vm.status.collectAsState()
    val recs = loaded(all.mapNotNull { status[it.id]?.lastRunAt }.maxOrNull()) { all.map { it to vm.farmPaperRecord(it.id) } } ?: return
    Row(Modifier.fillMaxWidth().padding(top = Space.s)) {
        listOf("" to 1.6f, "Fill P&L" to 1f, "Rebates" to 1f, "Est. rewards" to 1.2f).forEach { (h, w) ->
            Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w)) }
    }
    recs.forEach { (v, r) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(variantLabel(v), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.6f))
            Text(r?.let { money(it.realized) } ?: "-", style = Figures, color = r?.let { moneyColor(it.realized) } ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(r?.let { money(it.rebates) } ?: "-", style = Figures, modifier = Modifier.weight(1f))
            Text(r?.estimatedRewards?.let { money(it) } ?: "-", style = Figures, modifier = Modifier.weight(1.2f))
        }
    }
    Text(stringResource(R.string.variants_farm_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
