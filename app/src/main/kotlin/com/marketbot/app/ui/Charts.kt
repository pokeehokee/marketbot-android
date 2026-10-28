package com.marketbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        Text("Not enough points for a chart yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val pts = series.sortedBy { it.first }
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
            Text("${dayFmt.format(Instant.ofEpochMilli(t0))} - ${dayFmt.format(Instant.ofEpochMilli(t1))}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
