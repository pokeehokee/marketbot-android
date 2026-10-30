package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.Mode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MMM d HH:mm:ss z")
/** Phone-local time with its zone, so nobody has to guess which clock a time is on. */
fun Instant.short(): String = timeFmt.format(atZone(ZoneId.systemDefault()))
private val etFmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a 'ET'")
/** Market close times in Eastern (how the contracts are written), with the phone's time beside it when different. */
fun Instant.closeLabel(): String {
    val et = etFmt.format(atZone(com.marketbot.core.util.Tickers.ET))
    val local = ZoneId.systemDefault()
    return if (local.rules.getOffset(this) == com.marketbot.core.util.Tickers.ET.rules.getOffset(this)) et
    else "$et (${DateTimeFormatter.ofPattern("h:mm a z").format(atZone(local))})"
}

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
    Text(label, style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1, softWrap = false,
        modifier = modifier.clip(Radius.chip).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** The wall clock for staleness, ticking every 30 s from the app root; screens never read Instant.now() per row. */
val LocalNow = androidx.compose.runtime.compositionLocalOf { Instant.now() }

/**
 * How old a reading may be before it is shown as old. Screens set it from what
 * they show: a strategy's own figures go stale after a few of its passes, a
 * balance after a quarter of an hour. Null never marks anything stale.
 */
val LocalStaleAfter = androidx.compose.runtime.compositionLocalOf<java.time.Duration?> { java.time.Duration.ofMinutes(15) }

/** True when a reading taken [at] is older than [after] at [now]. */
fun isStale(at: Instant?, now: Instant, after: java.time.Duration?): Boolean =
    at != null && after != null && java.time.Duration.between(at, now) > after

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** Three of a strategy's periods (from its last and next pass), never under two minutes; 15 minutes when the period is unknown. */
fun staleAfter(lastRunAt: Instant?, nextRunAt: Instant?): java.time.Duration {
    val period = if (lastRunAt != null && nextRunAt != null && nextRunAt.isAfter(lastRunAt)) java.time.Duration.between(lastRunAt, nextRunAt) else null
    return period?.multipliedBy(3)?.coerceAtLeast(java.time.Duration.ofMinutes(2)) ?: java.time.Duration.ofMinutes(15)
}

/** A label/value line with the value's source chip. Tapping shows its origin. An old reading is dimmed and says when it was read. */
@Composable
fun ValueRow(label: String, value: String, source: Source?, origin: String? = null, note: String? = null, at: Instant? = null, valueColor: Color? = null) {
    var show by remember { mutableStateOf(false) }
    val stale = isStale(at, LocalNow.current, LocalStaleAfter.current)
    Row(Modifier.fillMaxWidth().clickable(enabled = origin != null) { show = true }.padding(vertical = 7.dp)
        .then(if (stale) Modifier.semantics { stateDescription = "old reading" } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        // Both share the row: a long value wraps onto a second line instead of crushing the label.
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, modifier = if (value.length > 20) Modifier.weight(1.4f) else Modifier) {
            Text(value, style = Figures, color = (valueColor ?: MaterialTheme.colorScheme.onSurface).let { if (stale) it.copy(alpha = 0.45f) else it },
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            if (stale) Text(stringResource(R.string.as_of, hhmm.format(at!!.atZone(ZoneId.systemDefault()))), style = MaterialTheme.typography.labelSmall,
                color = LocalDesk.current.warn)
        }
        if (source != null) { Spacer(Modifier.width(8.dp)); SourceChip(source) }
    }
    if (show && origin != null) AlertDialog(onDismissRequest = { show = false }, confirmButton = { TextButton({ show = false }) { Text(stringResource(R.string.close)) } },
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, style = Figures)
                source?.let { Text(stringResource(R.string.source, (it.label).toString()), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.from, (origin).toString()), style = MaterialTheme.typography.bodySmall)
                note?.let { Text(stringResource(R.string.why, (it).toString()), style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.derived) }
                at?.let { Text(stringResource(R.string.read_at, (it.short()).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        })
}

@Composable
fun <T> SourcedRow(label: String, v: Sourced<T>, fmt: (T) -> String = { it.toString() }, valueColor: Color? = null) =
    ValueRow(label, fmt(v.value), v.source, v.origin, v.note, v.at, valueColor)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = Radius.card, color = MaterialTheme.colorScheme.surfaceContainer,
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
fun ModePill(mode: Mode, armed: Boolean, engineRunning: Boolean = true) {
    val d = LocalDesk.current
    val (text, c) = when {
        mode != Mode.OFF && !engineRunning -> "${mode.name} · PAUSED, ENGINE OFF" to d.muted
        mode == Mode.LIVE && armed -> "LIVE" to d.live
        mode == Mode.LIVE -> "LIVE (not armed)" to d.warn
        mode == Mode.PAPER -> "PAPER" to d.paper
        else -> "OFF" to d.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(Radius.pill).background(c.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
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
        EvidenceLevel.UNPROVEN, EvidenceLevel.NO_EDGE -> d.loss
    }
    Text(level.label, style = MaterialTheme.typography.labelSmall, color = c,
        modifier = Modifier.clip(Radius.chip).background(c.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * The honest mode control: Off, Paper and Live all shown. Live opens the go-live
 * checklist instead of switching; leaving Live asks first and says what happens
 * to orders resting at the exchange.
 */
@Composable
fun ModeControl(vm: MainViewModel, s: com.marketbot.core.strategy.Strategy, current: Mode, onGoLive: () -> Unit) {
    var leaving by remember { mutableStateOf<Mode?>(null) }
    ModeSelector(current, s.supportedModes) { m ->
        when {
            m == current -> {}
            m == Mode.LIVE -> onGoLive()
            current == Mode.LIVE -> leaving = m
            else -> vm.setMode(s, m)
        }
    }
    leaving?.let { m ->
        AlertDialog(onDismissRequest = { leaving = null },
            confirmButton = { TextButton({ vm.setMode(s, m); leaving = null }) { Text(if (m == Mode.OFF) "Turn off" else "Switch to paper") } },
            dismissButton = { TextButton({ leaving = null }) { Text(stringResource(R.string.stay_live)) } },
            title = { Text(stringResource(R.string.stop_live_trading, (s.name).toString())) },
            text = { Text(stringResource(R.string.live_is_disarmed_and_has_to_be_armed_aga, (s.onLeaveLive).toString())) })
    }
}

/** Off / Paper / Live selector. Use [ModeControl] on screens; this is only the segmented control. */
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


/** A placeholder line while a value loads. */
@Composable
fun SkeletonLine(widthFraction: Float = 1f) {
    if (reducedMotion(androidx.compose.ui.platform.LocalContext.current)) {
        Box(Modifier.padding(vertical = 7.dp).fillMaxWidth(widthFraction).height(14.dp).clip(Radius.chip)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)))
        return
    }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(initialValue = 0.25f, targetValue = 0.55f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    Box(Modifier.padding(vertical = 7.dp).fillMaxWidth(widthFraction).height(14.dp)
        .clip(Radius.chip).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a * 0.25f)))
}

/** Bumps each time the screen comes back to the foreground, so checks of system settings re-run after the user changes one. */
@Composable
fun rememberResumeCount(): Int {
    var n by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { n++; onPauseOrDispose { } }
    return n
}
