package com.marketbot.app.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Narration
import com.marketbot.core.strategy.Narration.Tone
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.money
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/*
 * The board: the top of Home, in three layers of words.
 *   1. one headline sentence;
 *   2. under every number, one line saying what it means (the Explain switch turns these off);
 *   3. tap any tile for a sheet with the whole story.
 * Every sentence comes from core's Narration, written from the ledger. Numbers are monospace and
 * words are not, so the eye keeps the two apart. Real money and practice are never summed.
 */

/** The Explain switch, kept with the other appearance choices. On by default. */
object Explain {
    var on by mutableStateOf(true)
    fun load(ctx: Context) { on = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("explain", true) }
    fun set(ctx: Context, value: Boolean) {
        on = value
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("explain", value).apply()
    }
}

/** Numbers on the board: monospace, tabular, so they read as figures and never as words. */
private val BoardFig = Figures.copy(fontFamily = FontFamily.Monospace)

/** The two names strategies give their daily loss brake, which means the same thing in both. */
private val BRAKE_KEYS = listOf("daily_loss_limit", "daily_stop")
private fun brakeKey(s: Strategy): String? = BRAKE_KEYS.firstOrNull { k -> s.params.any { it.key == k } }

@Composable
private fun toneColor(t: Tone): Color {
    val d = LocalDesk.current
    return when (t) {
        Tone.GOOD -> d.gain; Tone.BAD -> d.loss; Tone.WARN -> d.warn
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface; Tone.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Which tile's sheet is open. */
private enum class Sheet { WEEK, ACCOUNT, BRAKE, ODDS }

/** Everything the board shows, computed once per change of the ledger, modes or settings. */
private data class BoardData(
    val hasLive: Boolean,
    val book: Mode,
    val bookRows: List<QuoteRow>,
    val liveWeek: Double?,
    val practiceWeek: Double,
    val brake: Narration.Brake?,
    val brakeRows: List<QuoteRow>,
    val odds: Narration.Odds,
    val rows: List<StratLine>,
)

private data class StratLine(val s: Strategy, val mode: Mode, val week: Double?, val word: Narration.Line, val ratio: Double?, val failing: Boolean)

private fun weekOf(rows: List<QuoteRow>, now: Instant) =
    Narration.graded(rows).filter { !it.resolvedAt!!.isBefore(now.minus(Duration.ofDays(7))) }.sumOf { it.pnl!! }

private fun dayStart(cfg: StrategyConfig?, now: Instant): Instant {
    val zone = cfg?.let { c -> runCatching { com.marketbot.core.strategy.brakeZone(c.text("brake_time_zone")) }.getOrNull() } ?: ZoneId.systemDefault()
    return com.marketbot.core.strategy.startOfLocalDay(now, zone)
}

@Composable
fun Board(vm: MainViewModel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Explain.load(ctx) }
    val settled by vm.settled.collectAsState()
    val modesTick by vm.modes.collectAsState()
    val status by vm.status.collectAsState()
    val acc by vm.accounts.collectAsState()
    val now = LocalNow.current
    val listed = vm.listed()
    val liveIds = listed.filter { vm.modeOf(it.id) == Mode.LIVE }.map { it.id }
    // Only live strategies' settings are read: the brake tile is about real money.
    val configs = loaded(modesTick.size, liveIds) { listed.filter { it.id in liveIds }.associate { it.id to vm.config(it) } }.orEmpty()

    val data = remember(settled, modesTick, configs, now.epochSecond / 60, status.keys) {
        val live = settled.filter { it.mode == Mode.LIVE }
        val paper = settled.filter { it.mode == Mode.PAPER }
        val hasLive = liveIds.isNotEmpty() || Narration.graded(live).isNotEmpty()
        val book = if (hasLive) Mode.LIVE else Mode.PAPER
        val bookRows = if (hasLive) live else paper
        // The live strategy closest to its own daily limit: there is no account-wide brake to show.
        val brakes = listed.filter { it.id in liveIds }.mapNotNull { s ->
            val key = brakeKey(s) ?: return@mapNotNull null
            val cfg = configs[s.id] ?: return@mapNotNull null
            val rows = live.filter { it.strategyId == s.id }
            Triple(Narration.brake(s.name, rows, cfg.num(key), dayStart(cfg, now)), rows, dayStart(cfg, now))
        }
        val worst = brakes.maxWithOrNull(compareBy({ it.first.fraction }, { it.first.lossesToday }))
        BoardData(
            hasLive, book, bookRows,
            if (hasLive) weekOf(live, now) else null, weekOf(paper, now),
            worst?.first, worst?.let { w -> Narration.graded(w.second).filter { !it.resolvedAt!!.isBefore(w.third) } }.orEmpty(),
            Narration.odds(bookRows),
            listed.map { s ->
                val m = vm.modeOf(s.id)
                val mine = settled.filter { it.strategyId == s.id && it.mode == (if (m == Mode.OFF) Mode.PAPER else m) }
                val o = Narration.odds(mine)
                StratLine(s, m, if (m == Mode.OFF) null else weekOf(mine, now), Narration.vsOddsWord(o), o.ratio, status[s.id]?.lastError != null)
            },
        )
    }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val explain = Explain.on

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Narration.headline(data.liveWeek, data.practiceWeek, data.brake), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            FilterChip(explain, { Explain.set(ctx, !explain) }, { Text("Explain") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, Modifier.size(16.dp)) },
                modifier = Modifier.semantics { stateDescription = if (explain) "explanations shown" else "explanations hidden" })
        }

        val real = data.book == Mode.LIVE
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Tile(if (real) "Real money · 7 days" else "Practice · 7 days", Modifier.weight(1f), { sheet = Sheet.WEEK }) {
                val w = if (real) data.liveWeek ?: 0.0 else data.practiceWeek
                Row(verticalAlignment = Alignment.Bottom) {
                    Text((if (w >= 0) "+" else "") + "%.2f".format(java.util.Locale.ROOT, w), style = BoardFig.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                        color = moneyColor(w) ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    val curve = remember(data.bookRows, now.epochSecond / 600) {
                        cumulative(Narration.graded(data.bookRows).filter { !it.resolvedAt!!.isBefore(now.minus(Duration.ofDays(7))) }
                            .map { it.resolvedAt!!.toEpochMilli() to it.pnl!! })
                    }
                    if (curve.size >= 2) Sparkline(curve, Modifier.width(52.dp), height = 22.dp)
                }
                Caption(explain, Narration.weekLine(data.bookRows, now, ZoneId.systemDefault()),
                    "${Narration.graded(data.bookRows).count { !it.resolvedAt!!.isBefore(now.minus(Duration.ofDays(7))) }} settled")
            }
            Tile("Account", Modifier.weight(1f), { sheet = Sheet.ACCOUNT }) {
                val k = acc.kalshi
                val eq = k?.let { it.equity ?: it.cash }
                Text(eq?.let { "%.2f".format(java.util.Locale.ROOT, it.value) } ?: "—", style = BoardFig.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                Caption(explain,
                    when { k == null -> "No Kalshi account connected."; k.portfolioValue != null -> "Cash plus open bets, as Kalshi values them."
                        else -> "Cash only: Kalshi has not reported open bets." },
                    k?.cash?.let { "cash ${money(it.value)}" } ?: "—")
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val b = data.brake
            Tile(if (b != null) "Daily limit · ${b.strategy}" else "Daily limit", Modifier.weight(1f), { sheet = Sheet.BRAKE }) {
                if (b == null) {
                    Text("—", style = BoardFig.copy(fontSize = 22.sp))
                    Caption(explain, "Nothing is trading real money, so nothing real can be lost today.", "no live strategy")
                } else {
                    Text("%.2f/%.0f".format(java.util.Locale.ROOT, b.lostToday, b.limit), style = BoardFig.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Meter(b.fraction.toFloat(), if (b.reached) LocalDesk.current.loss else LocalDesk.current.warn,
                        "${money(b.lostToday)} of a ${money(b.limit)} daily limit used")
                    Caption(explain, Narration.brakeLine(b), "%.0f%%".format(java.util.Locale.ROOT, b.fraction * 100))
                }
            }
            Tile(if (real) "Bets won" else "Practice bets won", Modifier.weight(1f), { sheet = Sheet.ODDS }) {
                val o = data.odds
                Text("${o.settled - o.losses}/${o.settled}", style = BoardFig.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Meter(if (o.settled > 0) (o.settled - o.losses).toFloat() / o.settled else 0f, LocalDesk.current.gain,
                    "${o.settled - o.losses} of ${o.settled} won")
                // The first sentence only: the full one, with its counts and caveat, is in the sheet.
                Caption(explain, Narration.vsOddsSentence(o).let { if (o.comparable) it.substringBefore(". ") + "." else it },
                    "${o.losses} lost · %.1f exp".format(java.util.Locale.ROOT, o.implied))
            }
        }

        DayStrip(data.bookRows, real, explain, now)
        StrategyTable(data.rows, explain, onOpen)
    }

    sheet?.let { which -> ExplainSheet(which, data, acc, now) { sheet = null } }
}

@Composable
private fun Tile(label: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxHeight().heightIn(min = 96.dp).clip(Radius.banner).clickable(onClickLabel = "Explain $label", onClick = onClick),
        shape = Radius.banner, color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            content()
        }
    }
}

/** Layer 2: the plain-English line when Explain is on, the terse figure when it is off. */
@Composable
private fun Caption(explain: Boolean, words: String, terse: String) {
    if (explain) Text(words, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Text(terse, style = BoardFig.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Meter(fraction: Float, color: Color, description: String) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .semantics { contentDescription = description }) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
private fun DayStrip(rows: List<QuoteRow>, real: Boolean, explain: Boolean, now: Instant) {
    val zone = ZoneId.systemDefault()
    val days = remember(rows, now.epochSecond / 600) { Narration.daily(rows, now, zone, 14) }
    val worst = days.mapNotNull { it.second }.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
    val up = days.count { (it.second ?: 0.0) > 0 }; val down = days.count { (it.second ?: 0.0) < 0 }
    val d = LocalDesk.current
    Surface(Modifier.fillMaxWidth(), shape = Radius.banner, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row {
                Text(if (real) "Last 14 days" else "Last 14 days, practice", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("$up up · $down down", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().semantics { contentDescription = "Fourteen days: $up up, $down down" },
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEachIndexed { i, (_, v) ->
                    // Strength by size, so a big red day reads louder than a small one.
                    val c = when { v == null -> MaterialTheme.colorScheme.surfaceVariant
                        v >= 0 -> d.gain.copy(alpha = (0.35f + 0.65f * (v / worst).toFloat()).coerceIn(0.35f, 1f))
                        else -> d.loss.copy(alpha = (0.35f + 0.65f * (-v / worst).toFloat()).coerceIn(0.35f, 1f)) }
                    Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(4.dp)).background(c)
                        .then(if (i == days.lastIndex) Modifier.padding(0.dp) else Modifier))
                }
            }
            if (explain) Text(Narration.redDaysLine(rows, now, zone), style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StrategyTable(rows: List<StratLine>, explain: Boolean, onOpen: (String) -> Unit) {
    val d = LocalDesk.current
    Surface(Modifier.fillMaxWidth(), shape = Radius.banner, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.padding(horizontal = Space.m, vertical = Space.s)) {
                Text("STRATEGY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("7D", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Space.s).width(64.dp))
                Text("VS THE ODDS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            }
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open ${r.s.name}") { onOpen(r.s.id) }
                    .padding(horizontal = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        val (tag, bg, fg) = when (r.mode) {
                            Mode.LIVE -> Triple("LIVE", d.gain.copy(alpha = 0.16f), d.gain)
                            Mode.PAPER -> Triple("PRAC", d.paper.copy(alpha = 0.16f), d.paper)
                            Mode.OFF -> Triple("OFF", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(tag, style = Mono.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = fg,
                            modifier = Modifier.clip(Radius.chip).background(bg).padding(horizontal = 5.dp, vertical = 2.dp))
                        Text(r.s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (r.mode == Mode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                    Text(r.week?.let { (if (it >= 0) "+" else "") + "%.2f".format(java.util.Locale.ROOT, it) } ?: "—", style = BoardFig.copy(fontSize = 14.sp),
                        color = if (r.mode == Mode.LIVE) moneyColor(r.week) ?: MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.s).width(64.dp))
                    val (text, color) = when {
                        r.failing -> "last pass failed" to d.loss
                        r.mode == Mode.OFF -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
                        explain -> r.word.text to toneColor(r.word.tone)
                        else -> (r.ratio?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "—") to toneColor(r.word.tone)
                    }
                    Text(text, style = if (explain || r.failing) MaterialTheme.typography.bodySmall else BoardFig.copy(fontSize = 12.sp),
                        color = color, modifier = Modifier.width(96.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Layer 3: what a tile is, what made today's figure, and what happens next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplainSheet(which: Sheet, data: BoardData, acc: AccountReading, now: Instant, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            when (which) {
                Sheet.WEEK -> {
                    SheetTitle(if (data.book == Mode.LIVE) "Real money, last 7 days" else "Practice, last 7 days")
                    SheetPart("WHAT IT IS", if (data.book == Mode.LIVE)
                        "Profit and loss on bets that have finished, as the exchange reported it. Bets still open are not counted until they settle."
                        else "What the practice strategies would have made on bets that have finished. No real money is involved.")
                    SheetPart("BY DAY", null) {
                        Narration.daily(data.bookRows, now, ZoneId.systemDefault(), 7).reversed().forEach { (day, v) ->
                            SheetRow(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH), v?.let { money(it) } ?: "nothing settled", v) }
                    }
                }
                Sheet.ACCOUNT -> {
                    SheetTitle("Account")
                    SheetPart("WHAT IT IS", "What Kalshi says the account is worth: cash, plus open bets at their current price. Practice money is never included.")
                    acc.kalshi?.let { k -> SheetPart("FROM KALSHI", null) {
                        SheetRow("Cash", money(k.cash.value), null)
                        k.portfolioValue?.let { SheetRow("Open bets", money(it.value), null) }
                        k.byExchangeIndex[2]?.let { SheetRow("Crypto and commodities shard", money(it.value), null) }
                    } } ?: SheetPart("FROM KALSHI", "No Kalshi key is saved, so there is nothing to read.")
                }
                Sheet.BRAKE -> {
                    val b = data.brake
                    SheetTitle(if (b != null) "Daily limit · ${b.strategy}" else "Daily limit")
                    SheetPart("WHAT IT IS", "Once a strategy's real losses today reach its limit, it places no new bets until midnight. Bets already placed are left to finish. Each strategy has its own limit; this shows the one closest to it.")
                    if (b == null) SheetPart("TODAY", "Nothing is trading real money.")
                    else {
                        SheetPart("TODAY", null) {
                            data.brakeRows.sortedBy { it.resolvedAt }.forEach { q ->
                                SheetRow("${q.resolvedAt!!.short()} · ${com.marketbot.core.util.Tickers.describe(q.ticker)}", money(q.pnl!!), q.pnl) }
                            if (data.brakeRows.isEmpty()) Text("Nothing has settled today.", style = MaterialTheme.typography.bodyMedium)
                            SheetRow("Counted against the limit", money(-b.lostToday) + " of " + money(b.limit), -b.lostToday, bold = true)
                        }
                        SheetPart("WHAT HAPPENS NEXT", Narration.brakeLine(b))
                    }
                }
                Sheet.ODDS -> {
                    val o = data.odds
                    SheetTitle(if (data.book == Mode.LIVE) "Bets won" else "Practice bets won")
                    SheetPart("WHAT IT IS", "A bet is lost when the market settles against it. The prices themselves say how many losses to expect: winning more often than that is the whole edge.")
                    SheetPart("SO FAR", Narration.vsOddsSentence(o))
                    SheetPart("WHY 30 LOSSES", "A bet that wins 95 times in 100 is judged by its rare losses, not its many wins. Until 30 losses have happened, a good-looking record can still be luck.")
                }
            }
            TextButton(onClose, Modifier.align(Alignment.End)) { Text("Got it") }
        }
    }
}

@Composable private fun SheetTitle(t: String) = Text(t, style = MaterialTheme.typography.titleLarge)

@Composable
private fun SheetPart(label: String, body: String?, content: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        content()
    }
}

@Composable
private fun SheetRow(label: String, value: String, signed: Double?, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.weight(1f))
        Text(value, style = BoardFig.copy(fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            color = signed?.let { moneyColor(it) } ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Layer 1 on a strategy's own screen: its Bottom line, from its own book. */
@Composable
fun BottomLine(vm: MainViewModel, s: Strategy) {
    val settled by vm.settled.collectAsState()
    vm.modes.collectAsState().value
    val mode = vm.modeOf(s.id)
    val now = LocalNow.current
    val book = if (mode == Mode.OFF) Mode.PAPER else mode
    val rows = remember(settled, mode) { settled.filter { it.strategyId == s.id && it.mode == book } }
    val line = remember(rows, mode, now.epochSecond / 600) { Narration.bottomLine(mode, rows, now) }
    val cost = remember(rows) { Narration.lossCostLine(rows) }
    // Green only for news that has earned it; "no record yet" or "too early" is neither good nor bad.
    val accent = when (line.tone) { Tone.BAD -> LocalDesk.current.loss; Tone.WARN -> LocalDesk.current.warn; Tone.GOOD -> LocalDesk.current.gain
        else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(Modifier.fillMaxWidth(), shape = Radius.banner, color = accent.copy(alpha = 0.10f), border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("BOTTOM LINE", style = MaterialTheme.typography.labelSmall, color = accent)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
            if (Explain.on && cost != null) Text(cost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
