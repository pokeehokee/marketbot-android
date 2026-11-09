package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marketbot.core.strategy.Mode

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StrategiesScreen(vm: MainViewModel, onGoLive: (String) -> Unit = {}, onOpen: (String) -> Unit) {
    val status by vm.status.collectAsState()
    val running by vm.engineRunning.collectAsState()
    vm.modes.collectAsState().value
    val settledRows by vm.settled.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.strategies), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.each_starts_in_paper_live_needs_its_chec), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.strategies.filter { it.baseId == null }, key = { it.id }) { s ->
            val st = status[s.id]
            val mode = st?.mode ?: vm.modeOf(s.id)
            Surface(Modifier.fillMaxWidth().clickable { onOpen(s.id) }, shape = Radius.card,
                color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(s.venue.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ModePill(mode, st?.armed == true, running)
                    }
                    Text(s.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RecordBadge(vm.recordHealth(s, settledRows))
                        EvidenceBadge(s.evidence.level)
                    }
                    MethodRecordLine(vm.methodRecord(s, settledRows), showEmpty = true)
                    val book = if (mode == Mode.LIVE) Mode.LIVE else Mode.PAPER
                    Sparkline(androidx.compose.runtime.remember(settledRows, book) { vm.strategyCurve(s.id, book) })
                    VariantTable(vm.variantRows(s, settledRows).takeIf { it.size > 1 })
                    // Mode changes live on the strategy's own screen, one deliberate tap away from the list.
                }
            }
        }
    }
}

/** A strategy and its paper variants side by side: settled, win rate, per contract, P&L. */
@Composable
fun VariantTable(rows: List<Pair<com.marketbot.core.strategy.Strategy, com.marketbot.core.strategy.MethodRecord?>>?) {
    if (rows == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.paper_side_by_side), style = MaterialTheme.typography.labelSmall, color = muted)
        Row { listOf("", "Settled", "Won", "Per contract", "P&L").forEachIndexed { i, h ->
            Text(h, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(if (i == 0) 1.3f else 1f)) } }
        rows.forEach { (s, r) ->
            val label = if (s.baseId == null) "Main" else "Variant " + s.id.substringAfterLast('@').uppercase()
            val won = r?.takeIf { it.settled > 0 }?.let { "%.0f%%".format(100.0 * (it.settled - it.losses) / it.settled) } ?: "-"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f))
                Text(r?.settled?.toString() ?: "0", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(won, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(r?.perContract?.let { com.marketbot.core.strategy.cents(it) } ?: "-", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(r?.let { com.marketbot.core.strategy.money(it.pnl) } ?: "-", style = MaterialTheme.typography.bodySmall, color = r?.let { moneyColor(it.pnl) } ?: muted, modifier = Modifier.weight(1f))
            }
        }
    }
}
