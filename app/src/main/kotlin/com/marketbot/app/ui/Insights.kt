package com.marketbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marketbot.app.R
import com.marketbot.core.strategy.AdverseSelection
import com.marketbot.core.strategy.Check
import com.marketbot.core.strategy.Collateral
import com.marketbot.core.strategy.FunnelStep
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.money
import java.time.Duration
import java.time.Instant

@Composable
fun verdictColor(v: Check.Verdict): Color = when (v) {
    Check.Verdict.PASS, Check.Verdict.CLEAR -> LocalDesk.current.gain
    Check.Verdict.INSUFFICIENT -> LocalDesk.current.muted
    Check.Verdict.FAIL -> LocalDesk.current.warn
    Check.Verdict.KILL -> LocalDesk.current.loss
}

/**
 * Why the last pass did or did not trade: each step of the selection with the
 * markets still in the running after it, as bars against the first step.
 */
@Composable
fun FunnelView(steps: List<FunnelStep>) {
    if (steps.isEmpty()) return
    val top = steps.maxOf { it.count }.coerceAtLeast(1)
    Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(R.string.why_this_pass_traded_what_it_did), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        steps.forEachIndexed { i, s ->
            val drop = if (i > 0) steps[i - 1].count - s.count else 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${s.label}: ${s.count}" + if (drop > 0) ", $drop dropped" else "" }) {
                Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Box(Modifier.width(96.dp).height(8.dp).clip(Radius.chip).background(MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxWidth(s.count.toFloat() / top).height(8.dp).background(if (i == steps.lastIndex) LocalDesk.current.gain else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
                }
                Text("${s.count}", style = Figures, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            }
        }
    }
}

/** Kill criteria and stage gates on the live or paper record. */
@Composable
fun HealthSection(vm: MainViewModel, s: Strategy) {
    var mode by rememberSaveable { mutableStateOf(Mode.LIVE) }
    val status by vm.status.collectAsState()
    val checks = loaded(mode, status[s.id]?.lastRunAt) { vm.health(s, mode) }
    val list = checks ?: return Section(stringResource(R.string.health)) { repeat(2) { SkeletonLine() } }
    if (list.isEmpty()) return
    Section(stringResource(R.string.health)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FilterChip(mode == Mode.LIVE, { mode = Mode.LIVE }, { Text(stringResource(R.string.live)) })
            FilterChip(mode == Mode.PAPER, { mode = Mode.PAPER }, { Text(stringResource(R.string.paper)) })
        }
        list.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                Text(c.verdict.name, style = MaterialTheme.typography.labelSmall, color = verdictColor(c.verdict),
                    modifier = Modifier.width(92.dp).clip(Radius.chip).background(verdictColor(c.verdict).copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp))
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(if (mode == Mode.LIVE) stringResource(R.string.a_kill_on_the_live_record_disarms) else stringResource(R.string.paper_checks_are_information),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Fill rate by how the market settled, per price band: whether resting orders get
 * filled more on the markets that go against them (adverse selection).
 */
@Composable
fun OutcomesSection(vm: MainViewModel, s: Strategy) {
    val bands = s.outcomeBands ?: return
    val status by vm.status.collectAsState()
    val data = loaded(status[s.id]?.lastRunAt) { val (m, rows) = vm.recordRows(s); m to AdverseSelection.table(rows, bands) }
    val (mode, table) = data ?: return
    if (table.all { it.quotes == 0 }) return
    fun pct(x: Double?) = x?.let { "%.0f%%".format(it * 100) } ?: "-"
    Section(stringResource(R.string.fills_by_outcome)) {
        Text(stringResource(R.string.fills_by_outcome_help, mode.name.lowercase()), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = Space.s)) {
            listOf("Band" to 1.2f, "Orders" to 1f, "Filled, lost" to 1.2f, "Filled, won" to 1.2f, "Penalty" to 1f).forEach { (h, w) ->
                Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w))
            }
        }
        table.forEach { b ->
            val p = b.penalty
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(b.label, style = Figures, modifier = Modifier.weight(1.2f))
                Text("${b.quotes}", style = Figures, modifier = Modifier.weight(1f))
                Text("${pct(b.fillWhenLost)} (${b.lostQuotes})", style = Figures, modifier = Modifier.weight(1.2f))
                Text(pct(b.fillWhenWon), style = Figures, modifier = Modifier.weight(1.2f))
                Text(p?.let { "x%.2f".format(it) } ?: "-", style = Figures, modifier = Modifier.weight(1f),
                    color = when { p == null || b.lostQuotes < 5 -> MaterialTheme.colorScheme.onSurfaceVariant; p > 1.3 -> LocalDesk.current.loss; p > 1.1 -> LocalDesk.current.warn; else -> LocalDesk.current.gain })
            }
        }
        Text(stringResource(R.string.penalty_needs_losses), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Money locked over the last two days and now, from the ledger. */
@Composable
fun CollateralSection(vm: MainViewModel, s: Strategy) {
    if (s.outcomeBands == null) return
    val status by vm.status.collectAsState()
    val data = loaded(status[s.id]?.lastRunAt) {
        val (m, rows) = vm.recordRows(s)
        val t = Instant.now()
        m to Collateral.series(rows, t.minus(Duration.ofHours(48)), t).map { (at, v) -> at.toEpochMilli() to v }
    }
    val (mode, series) = data ?: return
    if (series.all { it.second == 0.0 }) return
    Section(stringResource(R.string.collateral_48h, mode.name.lowercase())) {
        Text(stringResource(R.string.collateral_now, money(series.last().second)), style = MaterialTheme.typography.bodyMedium)
        LineChart(series, label = "Locked collateral", color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.collateral_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** P&L reconciliation history against Kalshi, under Settings. */
@Composable
fun ReconciliationSection(vm: MainViewModel) {
    val runs by vm.reconciliations.collectAsState()
    val busy by vm.inFlight.collectAsState()
    Section(stringResource(R.string.reconciliation)) {
        Text(stringResource(R.string.reconciliation_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (runs.isEmpty()) Text(stringResource(R.string.no_reconciliation_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
        runs.take(5).forEach { r ->
            Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                Text(if (r.ok) "MATCH" else "CHECK", style = MaterialTheme.typography.labelSmall,
                    color = if (r.ok) LocalDesk.current.gain else LocalDesk.current.warn, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.summary, style = MaterialTheme.typography.bodySmall)
                    Text(Instant.ofEpochMilli(r.at).short(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton({ vm.reconcileNow() }, enabled = "recon" !in busy && vm.hasKalshi()) {
            Text(stringResource(if ("recon" in busy) R.string.running else R.string.check_now))
        }
    }
}

/** The result of [load], re-run whenever a key changes; null until the first result. */
@Composable
private fun <T> loaded(vararg keys: Any?, load: suspend () -> T): T? {
    var v by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) { v = load() }
    return v
}
