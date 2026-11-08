package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marketbot.core.strategy.money
import java.time.Instant
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A time series: (epoch ms, value). */
typealias Series = List<Pair<Long, Double>>

private val dayFmt = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())

/**
 * A plain line chart: one series, a zero line when the series crosses it, the
 * range labelled at the edges. No library: a few hundred points draw directly.
 * Screen readers get the first, last, low and high values.
 */
@Composable
fun LineChart(series: Series, modifier: Modifier = Modifier, height: Dp = 140.dp, color: Color? = null, label: String = "",
              format: (Double) -> String = { money(it) }) {
    if (series.size < 2) {
        Text(stringResource(R.string.not_enough_points_for_a_chart_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // Sorted and thinned once per series, not on every frame: a phone draws ~400 px, not 20,000 points.
    val pts = androidx.compose.runtime.remember(series) { thin(series.sortedBy { it.first }, 400) }
    val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    val t0 = pts.first().first; val t1 = pts.last().first
    val tSpan = (t1 - t0).coerceAtLeast(1)
    val up = pts.last().second >= pts.first().second
    val c = color ?: if (up) LocalDesk.current.gain else LocalDesk.current.loss
    val grid = MaterialTheme.colorScheme.outlineVariant
    val desc = "$label chart from ${format(pts.first().second)} to ${format(pts.last().second)}, low ${format(lo)}, high ${format(hi)}"
    Column(modifier.semantics { contentDescription = desc }) {
        Row(Modifier.fillMaxWidth()) {
            Text(format(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(format(pts.last().second), style = Figures, color = c)
        }
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 6.dp)) {
            fun x(t: Long) = (t - t0).toFloat() / tSpan * size.width
            fun y(v: Double) = size.height - ((v - lo) / span).toFloat() * size.height
            if (lo < 0 && hi > 0) drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
            val line = Path().apply { pts.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
            val fill = Path().apply { addPath(line); lineTo(x(t1), size.height); lineTo(x(t0), size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(line, c, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(format(lo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.date_range, (dayFmt.format(Instant.ofEpochMilli(t0))).toString(), (dayFmt.format(Instant.ofEpochMilli(t1))).toString()), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * At most about [max] points: each bucket keeps its first, lowest and highest
 * point, so spikes survive; the last point is always kept. Input must be sorted.
 */
fun thin(pts: Series, max: Int): Series {
    if (pts.size <= max) return pts
    val per = (pts.size + max / 3 - 1) / (max / 3)
    val out = ArrayList<Pair<Long, Double>>(max + 1)
    for (b in pts.indices step per) {
        val chunk = pts.subList(b, minOf(b + per, pts.size))
        val lo = chunk.minBy { it.second }; val hi = chunk.maxBy { it.second }
        listOf(chunk.first(), lo, hi).distinct().sortedBy { it.first }.forEach { out += it }
    }
    if (out.last() != pts.last()) out += pts.last()
    return out
}

/** The largest fall of a series from an earlier high, and when that high was: (drop, peak time, trough time), or null. */
fun maxDrawdown(pts: Series): Triple<Double, Long, Long>? {
    var peak = Double.NEGATIVE_INFINITY; var peakAt = 0L; var best: Triple<Double, Long, Long>? = null
    for ((t, v) in pts.sortedBy { it.first }) {
        if (v > peak) { peak = v; peakAt = t }
        val dd = peak - v
        if (dd > 0 && (best == null || dd > best.first)) best = Triple(dd, peakAt, t)
    }
    return best
}

/** Running sum of settled P&L: the shape of a strategy's record. */
fun cumulative(points: Series): Series {
    var sum = 0.0
    return points.sortedBy { it.first }.map { (t, v) -> sum += v; t to sum }
}

/**
 * The record against the evidence on one axis: the expected band (and point), the
 * record's interval and point. Reads at a glance whether the record sits inside
 * what the evidence predicted.
 */
@Composable
fun VersusBar(expected: Double, expLow: Double?, expHigh: Double?, live: Double, liveLow: Double, liveHigh: Double, modifier: Modifier = Modifier) {
    val lo = minOf(expLow ?: expected, liveLow, 0.0); val hi = maxOf(expHigh ?: expected, liveHigh, 0.0)
    val span = (hi - lo).takeIf { it > 1e-12 } ?: 1.0
    val exp = LocalDesk.current.setting; val rec = if (live >= 0) LocalDesk.current.gain else LocalDesk.current.loss
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(44.dp)) {
        fun x(v: Double) = ((v - lo) / span).toFloat() * size.width
        val yE = size.height * 0.3f; val yL = size.height * 0.75f
        drawLine(axis, Offset(x(0.0), 0f), Offset(x(0.0), size.height), strokeWidth = 1.dp.toPx())
        if (expLow != null && expHigh != null) drawLine(exp.copy(alpha = 0.45f), Offset(x(expLow), yE), Offset(x(expHigh), yE), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(exp, 5.dp.toPx(), Offset(x(expected), yE))
        drawLine(rec.copy(alpha = 0.45f), Offset(x(liveLow), yL), Offset(x(liveHigh), yL), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(rec, 5.dp.toPx(), Offset(x(live), yL))
    }
}

private val hourFmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:00").withZone(ZoneId.systemDefault())

/**
 * One point per hour, each drawn as a dot on the line. Touch or drag to read any hour's value
 * (the header then shows that hour instead of the latest).
 */
@Composable
fun HourlyChart(pts: Series, modifier: Modifier = Modifier, height: Dp = 160.dp, label: String = "", format: (Double) -> String = { money(it) }) {
    if (pts.size < 2) {
        Text(stringResource(R.string.not_enough_points_for_a_chart_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var sel by androidx.compose.runtime.remember(pts) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    val t0 = pts.first().first; val t1 = pts.last().first
    val tSpan = (t1 - t0).coerceAtLeast(1)
    val change = pts.last().second - pts.first().second
    val c = if (change >= 0) LocalDesk.current.gain else LocalDesk.current.loss
    val guide = MaterialTheme.colorScheme.outline
    val desc = "$label: ${pts.size} hourly points from ${format(pts.first().second)} to ${format(pts.last().second)}, low ${format(lo)}, high ${format(hi)}"
    val shown = sel?.let { pts[it] } ?: pts.last()
    Column(modifier.semantics { contentDescription = desc }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(format(shown.second), style = Figures.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (sel == null) stringResource(R.string.hourly_now) else hourFmt.format(Instant.ofEpochMilli(shown.first)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text((if (change >= 0) "+" else "") + format(change), style = Figures, color = c)
        }
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 8.dp)
            .hourScrub(pts) { i -> sel = i }) {
            fun x(t: Long) = (t - t0).toFloat() / tSpan * size.width
            fun y(v: Double) = size.height - ((v - lo) / span).toFloat() * size.height
            val line = Path().apply { pts.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
            val fill = Path().apply { addPath(line); lineTo(x(t1), size.height); lineTo(x(t0), size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.18f), Color.Transparent)))
            drawPath(line, c, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            val r = 1.6.dp.toPx()
            pts.forEach { (t, v) -> drawCircle(c, r, Offset(x(t), y(v))) }
            sel?.let { i ->
                val (t, v) = pts[i]
                drawLine(guide, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1.dp.toPx())
                drawCircle(c, 4.dp.toPx(), Offset(x(t), y(v)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chart_low_high, format(lo), format(hi)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.date_range, dayFmt.format(Instant.ofEpochMilli(t0)), dayFmt.format(Instant.ofEpochMilli(t1))),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Touch and drag report the nearest point's index; lifting the finger clears it. */
private fun Modifier.hourScrub(pts: Series, onSel: (Int?) -> Unit): Modifier = this.pointerInput(pts) {
    val t0 = pts.first().first; val span = (pts.last().first - t0).coerceAtLeast(1)
    fun idx(px: Float): Int {
        val t = t0 + (px.coerceIn(0f, size.width.toFloat()) / size.width * span).toLong()
        return pts.indices.minBy { kotlin.math.abs(pts[it].first - t) }
    }
    awaitEachGesture {
        val down = awaitFirstDown()
        onSel(idx(down.position.x))
        do {
            val e = awaitPointerEvent()
            e.changes.firstOrNull()?.let { if (it.pressed) { onSel(idx(it.position.x)); it.consume() } }
        } while (e.changes.any { it.pressed })
        onSel(null)
    }
}
