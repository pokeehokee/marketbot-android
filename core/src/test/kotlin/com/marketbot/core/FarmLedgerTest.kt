package com.marketbot.core

import com.marketbot.core.provenance.Source
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.rewards.FarmLedger
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.Outcomes
import com.marketbot.core.strategy.Periods
import com.marketbot.core.strategy.realisedSince
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reward farm's money in the shared ledger (gap 1 of the 2026-10-29 performance audit). */
class FarmLedgerTest {
    private val id = "polymarket_reward_farm"
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    @Test fun `paper fill P&L is booked per UTC day as it moves, and the first pass books nothing`() = runTest {
        val st = inMemoryStores()
        // First pass after an upgrade: history before it stays out of today.
        assertEquals(-2.5, FarmLedger.bookPaperFills(st.ledger, id, -2.5, 4.0, null, t0))
        assertTrue(st.ledger.all(id, Mode.PAPER).isEmpty())
        var booked = FarmLedger.bookPaperFills(st.ledger, id, -2.0, 3.0, -2.5, t0)
        booked = FarmLedger.bookPaperFills(st.ledger, id, -2.25, 2.0, booked, t0.plusSeconds(600))
        assertEquals(-2.25, booked)
        val today = st.ledger.all(id, Mode.PAPER).single()
        assertEquals(0.25, today.pnl!!, 1e-9); assertEquals(5.0, today.contracts); assertEquals(5.0, today.filledContracts)
        assertEquals(false, today.hypothetical); assertEquals(Source.PAPER_MODEL, today.pnlSource)
        assertEquals(t0.plusSeconds(600), today.resolvedAt)
        // Nothing moved: no write.
        FarmLedger.bookPaperFills(st.ledger, id, -2.25, 0.0, -2.25, t0.plusSeconds(900))
        assertEquals(t0.plusSeconds(600), st.ledger.all(id, Mode.PAPER).single().resolvedAt)
        // Next UTC day: a new row.
        FarmLedger.bookPaperFills(st.ledger, id, -3.0, 1.0, -2.25, t0.plusSeconds(86_400))
        assertEquals(listOf(0.25, -0.75), st.ledger.all(id, Mode.PAPER).sortedBy { it.placedAt }.map { it.pnl!! })
    }

    @Test fun `live rewards come from the earnings payload per day and market, and re-reading is idempotent`() = runTest {
        val st = inMemoryStores()
        val rows = Json.parseToJsonElement("""[
            {"date":"2026-10-28","marketSlug":"a","reward":0.40,"status":"REWARD_STATUS_PAID"},
            {"date":"2026-10-28T00:00:00Z","marketSlug":"a","reward":0.10,"status":"REWARD_STATUS_PENDING"},
            {"date":"2026-10-29","marketSlug":"b","reward":1.25},
            {"date":"2026-10-29","marketSlug":"c"},
            {"marketSlug":"d","reward":2.0}]""").let { it as kotlinx.serialization.json.JsonArray }.map { it as JsonObject }
        assertEquals(2, FarmLedger.bookEarnings(st.ledger, id, rows))
        assertEquals(2, FarmLedger.bookEarnings(st.ledger, id, rows))
        val booked = st.ledger.all(id, Mode.LIVE).associateBy { it.ticker }
        assertEquals(setOf("a", "b"), booked.keys)
        assertEquals(0.50, booked.getValue("a").pnl!!, 1e-9)
        assertEquals("paid,pending", booked.getValue("a").result)
        assertEquals(Source.POLYMARKET_API, booked.getValue("a").pnlSource)
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), booked.getValue("a").resolvedAt)
        assertEquals(1.75, realisedSince(st.ledger.all(id, Mode.LIVE), null), 1e-9)
    }

    @Test fun `estimates are shown but never summed, and no farm row is a binary outcome`() = runTest {
        val st = inMemoryStores()
        FarmLedger.upsert(st.ledger, id, Mode.PAPER, FarmLedger.REWARD_EST, "2026-10-30", t0, 3.0, 0.0, Source.DERIVED, "estimate", add = false)
        FarmLedger.upsert(st.ledger, id, Mode.PAPER, FarmLedger.REWARD_EST, "2026-10-30", t0.plusSeconds(60), 3.5, 0.0, Source.DERIVED, "estimate", add = false)
        FarmLedger.bookPaperFills(st.ledger, id, -0.4, 2.0, 0.0, t0)
        val rows = st.ledger.all(id, Mode.PAPER)
        assertEquals(2, rows.size)
        assertEquals(3.5, rows.single { it.action == FarmLedger.REWARD_EST }.pnl)
        assertEquals(true, rows.single { it.action == FarmLedger.REWARD_EST }.hypothetical)
        assertEquals(-0.4, realisedSince(rows, null), 1e-9)
        assertFalse(rows.any { Outcomes.binary(it) })
        assertNull(rows.firstOrNull { it.open })
        val day = Periods.table(rows, Periods.Unit.DAY, 1, ZoneOffset.UTC, t0).single()
        assertEquals(1, day.settled); assertEquals(-0.4, day.pnl, 1e-9); assertEquals(1, day.losses)
    }
}

/** Gaps 2 and 3: each method's own record for the lists, and the period table split by method. */
class MethodRecordTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private fun row(id: String, mode: Mode, pnl: Double, result: String?, price: Double = 0.05, filled: Double = 5.0, hyp: Boolean = false, at: Instant = t0) =
        com.marketbot.core.store.QuoteRow(strategyId = id, mode = mode, venue = "kalshi", ticker = "T$pnl$at", action = "sell_yes", price = price,
            contracts = 5.0, placedAt = at.minusSeconds(3600), filledContracts = filled, resolvedAt = at, result = result, pnl = pnl, hypothetical = hyp)

    @Test fun `a method's record counts real settled rows only, with per contract and losses against implied`() {
        val rows = listOf(row("w", Mode.LIVE, 0.25, "no"), row("w", Mode.LIVE, 0.25, "no"), row("w", Mode.LIVE, -4.75, "yes"),
            row("w", Mode.LIVE, 0.25, "no", filled = 0.0, hyp = true))
        val r = com.marketbot.core.strategy.MethodRecord.of(Mode.LIVE, rows, t0.minusSeconds(60))!!
        assertEquals(3, r.settled); assertEquals(-4.25, r.pnl, 1e-9); assertEquals(-4.25, r.today, 1e-9)
        assertEquals(-4.25 / 15, r.perContract!!, 1e-9)
        assertEquals(1, r.losses); assertEquals(0.15, r.impliedLosses!!, 1e-9)
        assertEquals("Live -$4.25 on 3 settled, -28.33c/contract, 1 lost vs 0.2 implied", r.line())
        assertNull(com.marketbot.core.strategy.MethodRecord.of(Mode.PAPER, rows, t0))
        assertEquals(Mode.LIVE, com.marketbot.core.strategy.MethodRecord.preferred(Mode.PAPER, rows, t0)!!.mode, "no paper record: the live one")
    }

    @Test fun `the farm's day rows give a record without a loss ratio`() = runTest {
        val st = inMemoryStores()
        FarmLedger.bookPaperFills(st.ledger, "farm", 0.3, 6.0, 0.0, t0)
        val r = com.marketbot.core.strategy.MethodRecord.of(Mode.PAPER, st.ledger.all("farm", Mode.PAPER), t0)!!
        assertNull(r.impliedLosses)
        assertEquals("Paper +$0.30 on 1 settled, +5.00c/contract", r.line())
    }

    @Test fun `periods split by method sum to the pooled table`() {
        val rows = listOf(row("a", Mode.LIVE, 1.0, "no"), row("b", Mode.LIVE, -2.0, "yes"), row("b", Mode.LIVE, 0.5, "no", at = t0.minusSeconds(86_400)))
        val pooled = Periods.table(rows, Periods.Unit.DAY, 3, ZoneOffset.UTC, t0)
        val split = Periods.byStrategy(rows, Periods.Unit.DAY, 3, ZoneOffset.UTC, t0)
        assertEquals(setOf("a", "b"), split.keys)
        for ((i, p) in pooled.withIndex()) assertEquals(p.pnl, split.values.sumOf { it[i].pnl }, 1e-9)
        assertEquals(-2.0, split.getValue("b")[0].pnl); assertEquals(0.5, split.getValue("b")[1].pnl)
    }
}

/** The CSV export carries what a review off the phone needs, including the stored extras. */
class LedgerCsvTest {
    @Test fun `extras and order details are exported, quoted, after the original columns`() {
        val q = com.marketbot.core.store.QuoteRow(id = 7, strategyId = "w", mode = Mode.PAPER, venue = "kalshi", ticker = "KXHIGHNY-26OCT31-B70.5",
            action = "sell_yes", price = 0.07, contracts = 5.0, placedAt = Instant.parse("2026-10-30T10:00:00Z"),
            closeTime = Instant.parse("2026-10-31T04:00:00Z"), clientOrderId = "c1", marketKind = "HIGH", queueAhead = 12.0,
            extra = Json.parseToJsonElement("""{"quoted_mid":0.065,"markout_5":-0.5}""") as JsonObject)
        val header = com.marketbot.core.store.LedgerCsv.header().trim().split(',')
        assertEquals("hypothetical", header[17]); assertEquals("extra", header.last())
        val row = com.marketbot.core.store.LedgerCsv.row(q)
        assertTrue(row.endsWith("\"{\"\"quoted_mid\"\":0.065,\"\"markout_5\"\":-0.5}\"\n"), row)
        assertTrue("\"2026-10-31T04:00:00Z\"" in row && "\"HIGH\"" in row && "\"12.0\"" in row && "\"c1\"" in row, row)
        assertTrue(com.marketbot.core.store.LedgerCsv.row(q.copy(extra = JsonObject(emptyMap()))).endsWith(",\"\"\n"))
    }
}
