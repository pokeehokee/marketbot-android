package com.marketbot.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Mode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault())
fun Instant.short(): String = timeFmt.format(this)

/** A small label saying where a value came from. Tap any value to see the exact field or formula. */
@Composable
fun SourceChip(source: Source, modifier: Modifier = Modifier) {
    val c = sourceColor(source)
    val label = when (source) {
        Source.KALSHI_API -> "KALSHI"
        Source.POLYMARKET_API -> "POLYMARKET"
        Source.VENUE_API -> "VENUE"
        Source.DERIVED -> "CALC"
        Source.PAPER_MODEL -> "PAPER"
        Source.CONFIG -> "SETTING"
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** A label/value line with the value's source chip. Tapping shows its origin. */
@Composable
fun ValueRow(label: String, value: String, source: Source?, origin: String? = null, note: String? = null, at: Instant? = null, valueColor: Color? = null) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = origin != null) { show = true }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = Figures, color = valueColor ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (source != null) { Spacer(Modifier.width(8.dp)); SourceChip(source) }
    }
    if (show && origin != null) AlertDialog(onDismissRequest = { show = false }, confirmButton = { TextButton({ show = false }) { Text("Close") } },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, style = Figures)
                source?.let { Text("Source: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
                Text("From: $origin", style = MaterialTheme.typography.bodySmall)
                note?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.derived) }
                at?.let { Text("Read at ${it.short()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        })
}

@Composable
fun <T> SourcedRow(label: String, v: Sourced<T>, fmt: (T) -> String = { it.toString() }, valueColor: Color? = null) =
    ValueRow(label, fmt(v.value), v.source, v.origin, v.note, v.at, valueColor)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

@Composable
fun ModePill(mode: Mode, armed: Boolean) {
    val d = LocalDesk.current
    val (text, c) = when {
        mode == Mode.LIVE && armed -> "LIVE" to d.live
        mode == Mode.LIVE -> "LIVE (not armed)" to d.warn
        mode == Mode.PAPER -> "PAPER" to d.paper
        else -> "OFF" to d.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EvidenceBadge(level: EvidenceLevel) {
    val d = LocalDesk.current
    val c = when (level) {
        EvidenceLevel.LIVE_PROVEN -> d.gain
        EvidenceLevel.REPORTED -> d.warn
        EvidenceLevel.BACKTEST_ONLY -> d.warn
        EvidenceLevel.UNPROVEN -> d.loss
    }
    Text(level.label, style = MaterialTheme.typography.labelSmall, color = c,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Off / Paper / Live selector. Live is never selected directly: it opens the go-live checklist. */
@Composable
fun ModeSelector(current: Mode, supported: Set<Mode>, onSelect: (Mode) -> Unit) {
    val modes = listOf(Mode.OFF, Mode.PAPER, Mode.LIVE).filter { it in supported }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, m ->
            SegmentedButton(selected = current == m, onClick = { onSelect(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
}

@Composable
fun moneyColor(v: Double?): Color? {
    val d = LocalDesk.current
    return when { v == null -> null; v > 0.0049 -> d.gain; v < -0.0049 -> d.loss; else -> null }
}
