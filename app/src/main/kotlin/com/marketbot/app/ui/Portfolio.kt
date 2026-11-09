package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
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
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
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

/**
 * Home's top card: what the live accounts hold now, today's and total realised P&L (live and
 * paper apart), and one chart that switches between the live accounts' value (a point an hour),
 * live realised P&L and paper realised P&L, over 24 hours, 7 days or 30 days.
 */
@Composable
fun PortfolioHeader(vm: MainViewModel) {
    val p by vm.portfolio.collectAsState()
    val acc by vm.accounts.collectAsState()
    val readings by vm.liveReadings.collectAsState()
    val now by vm.minute.collectAsState()
    var view by rememberSaveable { mutableStateOf(0) }       // 0 live value, 1 live P&L, 2 paper P&L
    var days by rememberSaveable { mutableStateOf(7) }
    var exclFlows by rememberSaveable { mutableStateOf(false) }
    val changed by vm.flowsChanged.collectAsState()
    val flows = loaded(changed) { vm.cashFlows() }.orEmpty()
    val liveEquity = listOfNotNull(acc.kalshi?.let { it.equity ?: it.cash }, acc.polymarket?.currentBalance)
    Section(stringResource(R.string.portfolio)) {
        if (liveEquity.isNotEmpty()) {
            MoneyTicker(liveEquity.sumOf { it.value }, Figures.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold), MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_accounts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                liveEquity.map { it.source }.distinct().forEach { SourceChip(it) }
            }
        } else Text(stringResource(R.string.no_live_accounts_connected_paper_results), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            FilterChip(view == 0, { view = 0 }, { Text(stringResource(R.string.portfolio_value)) })
            FilterChip(view == 1, { view = 1 }, { Text(stringResource(R.string.chart_live_n, p.liveN.toString())) })
            FilterChip(view == 2, { view = 2 }, { Text(stringResource(R.string.chart_paper_n, p.paperN.toString())) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to R.string.range_24h, 7 to R.string.range_7d, 30 to R.string.range_30d).forEach { (d, label) ->
                FilterChip(days == d, { days = d }, { Text(stringResource(label)) }) }
            if (view == 0 && flows.isNotEmpty()) FilterChip(exclFlows, { exclFlows = !exclFlows }, { Text(stringResource(R.string.excl_deposits)) })
        }
        val from = now - days * 86_400_000L
        when (view) {
            0 -> {
                val h = androidx.compose.runtime.remember(readings, now, days) {
                    if (readings.isEmpty()) null else com.marketbot.core.strategy.HourlyEquity.of(readings, from, now) }
                if (h == null) Text(stringResource(R.string.live_portfolio_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val pts = if (exclFlows) com.marketbot.core.strategy.CashFlows.performance(h.points, flows) else h.points
                    HourlyChart(pts, label = stringResource(R.string.portfolio_value))
                    Text(stringResource(R.string.hourly_coverage, h.recordedHours, h.hours),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val curve = if (view == 1) p.liveCurve else p.paperCurve
                val pts = androidx.compose.runtime.remember(curve, now, days) {
                    val before = curve.lastOrNull { it.first < from }?.second ?: 0.0
                    listOf(from to before) + curve.filter { it.first >= from } + listOf(now to (curve.lastOrNull()?.second ?: 0.0)) }
                HourlyChart(pts, label = stringResource(if (view == 1) R.string.live_pl else R.string.paper_pl), nowLabel = stringResource(R.string.realised_so_far))
                Text(stringResource(R.string.realised_only_settled_positions_from_the),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
        MoneyTicker(v, Figures, moneyColor(v) ?: MaterialTheme.colorScheme.onSurface, announce = label.endsWith("today"))
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
    var perf by rememberSaveable { mutableStateOf(false) }
    val changed by vm.flowsChanged.collectAsState()
    val flows = loaded(changed) { vm.cashFlows() }.orEmpty().filter { it.venue == k.first }
    Section(stringResource(R.string.equity_30_days)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.forEach { key -> FilterChip(key == k, { pick = "${key.first}/${key.second}" },
                { Text("${key.first.replaceFirstChar { it.uppercase() }} ${key.second.lowercase()}") }) }
        }
        if (k.second == "LIVE") Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!perf, { perf = false }, { Text(stringResource(R.string.equity_label)) })
            FilterChip(perf, { perf = true }, { Text(stringResource(R.string.performance_label)) })
        }
        val series = curves.getValue(k)
        val shown = if (perf && k.second == "LIVE") com.marketbot.core.strategy.CashFlows.performance(series, flows) else series
        LineChart(shown, label = "${k.first} ${k.second.lowercase()} ${if (perf) "performance" else "equity"}", color = MaterialTheme.colorScheme.primary)
        if (perf && k.second == "LIVE") Text(
            if (flows.isEmpty()) stringResource(R.string.performance_no_flows) else stringResource(R.string.performance_with_flows, flows.size),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Deposits and withdrawals: entered by hand (no exchange API lists them), with suggestions from unexplained equity jumps. */
@Composable
fun CashFlowsSection(vm: MainViewModel) {
    val changed by vm.flowsChanged.collectAsState()
    val flows = loaded(changed) { vm.cashFlows() } ?: return
    val suggested = loaded(changed) { vm.cashFlowCandidates() }.orEmpty()
    var venue by rememberSaveable { mutableStateOf("kalshi") }
    var amount by rememberSaveable { mutableStateOf("") }
    Section(stringResource(R.string.cash_flows_title)) {
        Text(stringResource(R.string.cash_flows_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        suggested.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${c.venue.replaceFirstChar { it.uppercase() }} ${money(c.amount)}?", style = MaterialTheme.typography.bodyMedium)
                    Text("${java.time.Instant.parse(c.at).short()}: ${c.note.removePrefix("suggested: ")}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ vm.addCashFlow(c) }) { Text(stringResource(R.string.record)) }
                TextButton({ vm.dismissCashFlow(c) }) { Text(stringResource(R.string.dismiss)) }
            }
        }
        flows.sortedByDescending { it.at }.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${java.time.Instant.parse(f.at).short()} · ${f.venue} · ${money(f.amount)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton({ vm.removeCashFlow(f) }) { Text(stringResource(R.string.remove)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("kalshi", "polymarket").forEach { v -> FilterChip(venue == v, { venue = v }, { Text(v.replaceFirstChar { it.uppercase() }) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                label = { Text(stringResource(R.string.cash_flow_amount)) }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            val v = amount.toDoubleOrNull()
            TextButton({ if (v != null && v != 0.0) { vm.addCashFlow(com.marketbot.core.strategy.CashFlow(venue, java.time.Instant.now().toString(), v, "entered by hand")); amount = "" } },
                enabled = v != null && v != 0.0) { Text(stringResource(R.string.record)) }
        }
    }
}

/** A strategy's own record against what its evidence predicts, and its curve. */
@Composable
fun RecordSection(vm: MainViewModel, s: Strategy) {
    val settled by vm.settled.collectAsState()
    var mode by rememberSaveable { mutableStateOf(Mode.LIVE) }
    val rec = remember(settled, s.id, mode) { vm.record(s.id, mode) }
    val curve = remember(settled, s.id, mode) { vm.strategyCurve(s.id, mode) }
    val exp = s.evidence.expectation
    Section(stringResource(R.string.record_vs_evidence)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == Mode.LIVE, { mode = Mode.LIVE }, { Text(stringResource(R.string.live)) })
            FilterChip(mode == Mode.PAPER, { mode = Mode.PAPER }, { Text(stringResource(R.string.paper)) })
        }
        fun c(v: Double) = "%+.2fc".format(v * 100)
        if (rec == null) Text(stringResource(R.string.needs_at_least_two_settled_positions, (mode.name.lowercase()).toString()), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text(stringResource(R.string.contract_over_positions, (mode.name.lowercase().replaceFirstChar { it.uppercase() }).toString(), (c(rec.perContract)).toString(), (rec.n).toString()) +
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
                Text(stringResource(R.string.top_line_expected_bottom_this_app_s_reco, (exp.basis).toString()), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        LineChart(curve, label = "${s.name} cumulative P&L")
        // Starting from zero, so a strategy that lost from its first trade shows that as its biggest drop.
        maxDrawdown(listOf((curve.firstOrNull()?.first ?: 0L) to 0.0) + curve)?.let { (dd, from, to) ->
            ValueRow("Biggest drop", "-${money(dd)}  (${java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} to " +
                "${java.time.Instant.ofEpochMilli(to).atZone(java.time.ZoneId.systemDefault()).toLocalDate()})", Source.DERIVED,
                "largest fall of cumulative realised P&L from an earlier high", valueColor = LocalDesk.current.loss)
        }
    }
}

private val dayHeader = DateTimeFormatter.ofPattern("EEEE, MMM d")

/** Orders: filter by state and mode, grouped by day with the day's realised P&L, swipe a live resting order to cancel it. */
@Composable
fun OrdersList(vm: MainViewModel, s: Strategy) {
    // remember: a new Room flow per recomposition re-ran the query and resubscribed every frame.
    val rows by remember(s.id) { vm.quotes(s.id) }.collectAsState(initial = emptyList())
    var state by rememberSaveable { mutableStateOf("all") }
    var mode by rememberSaveable { mutableStateOf("all") }
    var cancel by remember { mutableStateOf<QuoteRow?>(null) }
    var detail by remember { mutableStateOf<QuoteRow?>(null) }
    val busy by vm.inFlight.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
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
            if (all.any { it.mode == Mode.LIVE && it.resting }) Text(stringResource(R.string.swipe_a_resting_live_order_left_to_cance), style = MaterialTheme.typography.labelSmall,
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
                // The row stays until the exchange confirms: an unconfirmed cancel is not a cancel.
                val cancelling = "cancel:${q.id}" in busy
                if (q.mode == Mode.LIVE && q.resting && q.orderId != null && !cancelling) {
                    val st = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v == SwipeToDismissBoxValue.EndToStart) cancel = q; false })
                    // A tick as the swipe crosses the point where letting go asks to cancel.
                    LaunchedEffect(st.targetValue) {
                        if (st.targetValue == SwipeToDismissBoxValue.EndToStart) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                    SwipeToDismissBox(st, enableDismissFromStartToEnd = false, backgroundContent = {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) { Text(stringResource(R.string.cancel), color = LocalDesk.current.loss) }
                    }) { OrderRow(q) { detail = q } }
                } else OrderRow(q, cancelling) { detail = q }
            }
        }
    }
    // The whole order, with every value's source, in a sheet over the list.
    detail?.let { q ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { detail = null }, shape = Radius.sheet) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                OrderCard(rows.firstOrNull { it.id == q.id }?.toRow() ?: q, "cancel:${q.id}" in busy)
                if (q.mode == Mode.LIVE && q.resting && q.orderId != null)
                    TextButton({ cancel = q; detail = null }, enabled = "cancel:${q.id}" !in busy) { Text(stringResource(R.string.cancel_order), color = LocalDesk.current.loss) }
            }
        }
    }
    cancel?.let { q ->
        AlertDialog(onDismissRequest = { cancel = null },
            confirmButton = { TextButton({ vm.cancelOrder(s.id, q.id); cancel = null }) { Text(stringResource(R.string.cancel_order), color = LocalDesk.current.loss) } },
            dismissButton = { TextButton({ cancel = null }) { Text(stringResource(R.string.keep_it)) } },
            title = { Text(stringResource(R.string.cancel_this_order)) },
            text = { Text("${Tickers.describe(q.ticker)}: ${q.action.replace('_', ' ')} ${q.contracts.toInt()} at ${"%.0f".format(q.price * 100)}c. " +
                "Anything already filled is read from the exchange first and kept. A resting offer's place in the queue is lost.") })
    }
}

/** One line per order: what, where it stands, and its P&L. Tap for the full record. */
@Composable
private fun OrderRow(q: QuoteRow, cancelling: Boolean = false, onOpen: () -> Unit) {
    val action = actionLabel(q.action)
    val state = when {
        cancelling -> stringResource(R.string.cancelling)
        q.resolvedAt != null -> "Settled ${q.result?.uppercase() ?: ""}".trim()
        q.cancelledAt != null -> "Withdrawn" + (q.filledContracts?.takeIf { it > 0 }?.let { ", %.0f filled".format(it) } ?: "")
        (q.filledContracts ?: 0.0) >= q.contracts -> "Filled"
        (q.filledContracts ?: 0.0) > 0 -> "%.0f of %.0f filled".format(q.filledContracts, q.contracts)
        else -> "Resting" + restingDetail(q, java.time.Instant.now())
    }
    Surface(onClick = onOpen, shape = Radius.banner, color = Elevation.section(),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (q.mode == Mode.LIVE) LocalDesk.current.live.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().then(if (cancelling) Modifier.alpha(0.6f) else Modifier)) {
        Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Tickers.describe(q.ticker), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("$action ${q.contracts.toInt()} at ${"%.1f".format(q.price * 100)}c · $state", style = MaterialTheme.typography.labelSmall,
                    color = if (cancelling) LocalDesk.current.warn else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            q.pnl?.let { p ->
                Text(money(p), style = Figures, color = if (q.hypothetical == true) MaterialTheme.colorScheme.onSurfaceVariant else moneyColor(p) ?: MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(Space.s))
            }
            Text(if (q.mode == Mode.LIVE) "LIVE" else "PAPER", style = MaterialTheme.typography.labelSmall,
                color = if (q.mode == Mode.LIVE) LocalDesk.current.live else LocalDesk.current.paper)
        }
    }
}

/** " 3h 12m · 45 ahead · closes in 18h" for a resting order: how long it has waited, its queue, its clock. */
internal fun restingDetail(q: QuoteRow, now: java.time.Instant): String {
    fun dur(d: java.time.Duration) = when {
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 48 -> "${d.toHours()}h ${d.toMinutes() % 60}m"
        else -> "${d.toDays()}d"
    }
    return buildString {
        append(" ").append(dur(java.time.Duration.between(q.placedAt, now).coerceAtLeast(java.time.Duration.ZERO)))
        q.queueAhead?.let { append(" · %.0f ahead".format(it)) }
        q.closeTime?.let { c -> val left = java.time.Duration.between(now, c); if (!left.isNegative) append(" · closes in ").append(dur(left)) }
    }
}

private fun actionLabel(a: String) = when (a) { "sell_yes" -> "Sell YES"; "buy_yes" -> "Buy YES"; "buy_no" -> "Buy NO"; else -> a.replace('_', ' ') }

@Composable
private fun OrderCard(q: QuoteRow, cancelling: Boolean = false) {
    Section(Tickers.describe(q.ticker), modifier = if (cancelling) Modifier.alpha(0.6f) else Modifier, trailing = { ModePill(q.mode, q.mode == Mode.LIVE) }) {
        if (cancelling) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cancelling), style = MaterialTheme.typography.labelSmall, color = LocalDesk.current.warn)
        }
        val action = actionLabel(q.action)
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
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(q.ticker + (q.orderId?.let { " · order $it" } ?: ""), style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
