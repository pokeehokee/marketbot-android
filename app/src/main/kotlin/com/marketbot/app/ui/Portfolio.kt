package com.marketbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketbot.app.data.toRow
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.money
import com.marketbot.core.util.Tickers
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home's top: what the accounts hold, what was made today and in total (live and paper apart), and the curve. */
@Composable
fun PortfolioHeader(vm: MainViewModel) {
    val p by vm.portfolio.collectAsState()
    val acc by vm.accounts.collectAsState()
    var showPaper by rememberSaveable { mutableStateOf(false) }
    val liveEquity = listOfNotNull(acc.kalshi?.let { it.equity ?: it.cash }, acc.polymarket?.currentBalance)
    Section("Portfolio") {
        if (liveEquity.isNotEmpty()) {
            Text(money(liveEquity.sumOf { it.value }), style = Figures.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Live accounts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                liveEquity.map { it.source }.distinct().forEach { SourceChip(it) }
            }
        } else Text("No live accounts connected. Paper results below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Live today", p.liveToday, Source.KALSHI_API, Modifier.weight(1f))
            Stat("Live total", p.liveTotal, Source.KALSHI_API, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Stat("Paper today", p.paperToday, Source.PAPER_MODEL, Modifier.weight(1f))
            Stat("Paper total", p.paperTotal, Source.PAPER_MODEL, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!showPaper, { showPaper = false }, { Text("Live P&L (${p.liveN})") })
            FilterChip(showPaper, { showPaper = true }, { Text("Paper P&L (${p.paperN})") })
        }
        LineChart(if (showPaper) p.paperCurve else p.liveCurve, label = if (showPaper) "Paper cumulative P&L" else "Live cumulative P&L")
        Text("Realised only: settled positions, from the exchange's settlement figures (live) or the simulator (paper).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Stat(label: String, v: Double, src: Source, modifier: Modifier) {
    Column(modifier) {
        // Wraps instead of truncating the tag at large font sizes.
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceChip(src)
        }
        Text(money(v), style = Figures, color = moneyColor(v) ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Equity over 30 days per venue and mode, from the snapshots each pass records. */
@Composable
fun EquitySection(vm: MainViewModel) {
    val curves by vm.equityCurves.collectAsState()
    if (curves.isEmpty()) return
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val keys = curves.keys.sortedWith(compareBy({ it.second != "LIVE" }, { it.first }))
    val k = keys.firstOrNull { "${it.first}/${it.second}" == pick } ?: keys.first()
    Section("Equity, 30 days") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { key -> FilterChip(key == k, { pick = "${key.first}/${key.second}" },
                { Text("${key.first.replaceFirstChar { it.uppercase() }} ${key.second.lowercase()}") }) }
        }
        LineChart(curves.getValue(k), label = "${k.first} ${k.second.lowercase()} equity", color = MaterialTheme.colorScheme.primary)
    }
}

/** A strategy's own record against what its evidence predicts, and its curve. */
@Composable
fun RecordSection(vm: MainViewModel, s: Strategy) {
    vm.settled.collectAsState().value
    var mode by rememberSaveable { mutableStateOf(Mode.LIVE) }
    val rec = vm.record(s.id, mode)
    val exp = s.evidence.expectation
    Section("Record vs evidence") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == Mode.LIVE, { mode = Mode.LIVE }, { Text("Live") })
            FilterChip(mode == Mode.PAPER, { mode = Mode.PAPER }, { Text("Paper") })
        }
        fun c(v: Double) = "%+.2fc".format(v * 100)
        if (rec == null) Text("Needs at least two settled ${mode.name.lowercase()} positions.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text("${mode.name.lowercase().replaceFirstChar { it.uppercase() }} ${c(rec.perContract)}/contract over ${rec.n} positions " +
                "(${"%.0f".format(rec.contracts)} contracts), 95% range ${c(rec.low)} to ${c(rec.high)}" +
                (exp?.let { e -> val l = e.low; val h = e.high; " against ${c(e.perContract)} expected" + if (l != null && h != null) " [${c(l)}, ${c(h)}]." else "." } ?: "."),
                style = MaterialTheme.typography.bodyMedium)
            if (exp != null) {
                VersusBar(exp.perContract, exp.low, exp.high, rec.perContract, rec.low, rec.high, Modifier.padding(vertical = 6.dp))
                Text(when {
                    rec.high < (exp.low ?: exp.perContract) -> "The record is below what the evidence predicts, beyond chance at this size."
                    rec.low > (exp.high ?: exp.perContract) -> "The record is above the evidence."
                    else -> "Consistent with the evidence so far; the range narrows as positions settle."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Top line: expected (${exp.basis}). Bottom: this app's record.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        LineChart(vm.strategyCurve(s.id, mode), label = "${s.name} cumulative P&L")
    }
}

private val dayHeader = DateTimeFormatter.ofPattern("EEEE, MMM d")

/** Orders: filter by state and mode, grouped by day with the day's realised P&L, swipe a live resting order to cancel it. */
@Composable
fun OrdersList(vm: MainViewModel, s: Strategy) {
    val rows by vm.quotes(s.id).collectAsState(initial = emptyList())
    var state by rememberSaveable { mutableStateOf("all") }
    var mode by rememberSaveable { mutableStateOf("all") }
    var cancel by remember { mutableStateOf<QuoteRow?>(null) }
    val all = rows.map { it.toRow() }
    val shown = all.filter { q ->
        (mode == "all" || q.mode.name == mode) && when (state) {
            "open" -> q.resting
            "filled" -> (q.filledContracts ?: 0.0) > 0
            "settled" -> q.resolvedAt != null
            else -> true
        }
    }
    val zone = ZoneId.systemDefault()
    val byDay = shown.groupBy { it.placedAt.atZone(zone).toLocalDate() }.toSortedMap(compareByDescending { it })
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All", "open" to "Resting", "filled" to "Filled", "settled" to "Settled").forEach { (k, l) ->
                    FilterChip(state == k, { state = k }, { Text(l) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "Live and paper", "LIVE" to "Live", "PAPER" to "Paper").forEach { (k, l) -> FilterChip(mode == k, { mode = k }, { Text(l) }) }
            }
            if (all.any { it.mode == Mode.LIVE && it.resting }) Text("Swipe a resting live order left to cancel it.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isEmpty()) item { Text(if (all.isEmpty()) "No orders yet. Turn on paper mode to see what this strategy would do." else "Nothing matches these filters.",
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        byDay.forEach { (day, list) ->
            item(key = "d$day") {
                val pnl = list.filter { it.hypothetical == false && it.pnl != null }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayHeader.format(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (pnl.isNotEmpty()) Text(money(pnl.sumOf { it.pnl!! }), style = Figures, color = moneyColor(pnl.sumOf { it.pnl!! }) ?: MaterialTheme.colorScheme.onSurface)
                }
            }
            items(list, key = { it.id }) { q ->
                if (q.mode == Mode.LIVE && q.resting && q.orderId != null) {
                    val st = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) cancel = q; false })
                    SwipeToDismissBox(st, enableDismissFromStartToEnd = false, backgroundContent = {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) { Text("Cancel", color = LocalDesk.current.loss) }
                    }) { OrderCard(q) }
                } else OrderCard(q)
            }
        }
    }
    cancel?.let { q ->
        AlertDialog(onDismissRequest = { cancel = null },
            confirmButton = { TextButton({ vm.cancelOrder(s.id, q.id); cancel = null }) { Text("Cancel order", color = LocalDesk.current.loss) } },
            dismissButton = { TextButton({ cancel = null }) { Text("Keep it") } },
            title = { Text("Cancel this order?") },
            text = { Text("${Tickers.describe(q.ticker)}: ${q.action.replace('_', ' ')} ${q.contracts.toInt()} at ${"%.0f".format(q.price * 100)}c. " +
                "Anything already filled is read from the exchange first and kept. A resting offer's place in the queue is lost.") })
    }
}

@Composable
private fun OrderCard(q: QuoteRow) {
    Section(Tickers.describe(q.ticker), trailing = { ModePill(q.mode, q.mode == Mode.LIVE) }) {
        val action = when (q.action) { "sell_yes" -> "Sell YES"; "buy_yes" -> "Buy YES"; "buy_no" -> "Buy NO"; else -> q.action.replace('_', ' ') }
        ValueRow("Order", "$action ${q.contracts.toInt()} at ${"%.1f".format(q.price * 100)}c", Source.CONFIG, "the strategy's own order")
        ValueRow("Placed", q.placedAt.short(), null)
        q.closeTime?.let { ValueRow("Market closes", it.closeLabel(), null) }
        q.filledContracts?.let { ValueRow("Filled", "%.0f of %.0f".format(it, q.contracts),
            // A paper fill is the simulator's, whatever its row says; a live one is the venue's.
            if (q.mode == Mode.PAPER) Source.PAPER_MODEL else if (q.venue == "kalshi") Source.KALSHI_API else Source.POLYMARKET_API,
            q.fillSource ?: if (q.mode == Mode.PAPER) "paper fill simulator" else "order fill count at the venue") }
        q.cancelledAt?.let { ValueRow("Withdrawn", it.short(), null) }
        q.resolvedAt?.let { ValueRow("Settled", "${it.short()}  ${q.result?.uppercase() ?: ""}", null) }
        q.pnl?.let { ValueRow(if (q.hypothetical == true) "P&L if filled (not counted)" else "P&L", money(it), q.pnlSource, q.pnlOrigin,
            valueColor = if (q.hypothetical == true) null else moneyColor(it)) }
        Text(q.ticker + (q.orderId?.let { " · order $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
