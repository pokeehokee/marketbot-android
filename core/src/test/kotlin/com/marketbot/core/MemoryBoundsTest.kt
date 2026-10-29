package com.marketbot.core

import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.net.SignedStream
import com.marketbot.core.polymarket.PmTrade
import com.marketbot.core.polymarket.PmTradeStream
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategies.deepitm.KalshiBtcLadder
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build 116 died of OutOfMemoryError after ~13 hours on a phone. Everything a
 * long-running engine keeps between passes must be bounded; these pin it.
 */
class MemoryBoundsTest {
    private val signer by lazy { KalshiSigner("kid", golden("signatures").o()["rsa_pss"].o()["pkcs1_pem"].s!!) }
    private fun print(slug: String, i: Int) = PmTrade(slug, 0.5, i.toDouble(), "ORDER_SIDE_BUY")

    @Test fun `trade prints are kept only for subscribed markets and capped per market`() {
        val s = PmTradeStream(signer, "ws://127.0.0.1:1/never", maxPerMarket = 100)
        s.setMarkets(setOf("a", "b"))
        repeat(10_000) { s.offer(print("a", it)) }
        repeat(50) { s.offer(print("b", it)) }
        repeat(10_000) { s.offer(print("not-subscribed", it)) }
        assertEquals(150, s.pending, "a busy undrained market holds at most the cap")
        val a = s.drain("a")
        assertEquals(100, a.size)
        assertEquals(9_999.0, a.last().qty, "the newest prints are the ones kept")
        s.setMarkets(setOf("c"))
        assertEquals(0, s.pending, "a market dropped from the subscription loses its waiting prints")
        s.stop()
    }

    @Test fun `a stopped stream shuts down its own reconnect thread`() {
        val st = SignedStream("ws://127.0.0.1:1/never", signer, { emptyList() }, {})
        st.stop()
        assertTrue(st.schedulerShutdown)
    }

    @Test fun `the Kalshi paper tape is read once per ticker per window, not per quote per pass`() = runBlocking {
        FakeKalshi().use { fk ->
            fk.trades = """{"trades":[{"trade_id":"t1","ticker":"KXBTCD-X","created_time":"2026-10-29T00:00:05Z","count_fp":"3.00","yes_price_dollars":"0.9500","taker_side":"no"}],"cursor":""}"""
            val ladder = KalshiBtcLadder(fk.client(signed = false))
            val placed = Instant.parse("2026-10-29T00:00:00Z")
            fun q(id: Long) = QuoteRow(id = id, strategyId = "kalshi_btc_deep_itm", mode = Mode.PAPER, venue = "kalshi", ticker = "KXBTCD-X",
                action = "buy_yes", price = 0.95, contracts = 5.0, placedAt = placed.plusSeconds(id))
            var now = placed.plusSeconds(10)
            repeat(5) { for (id in 1L..4L) ladder.paperFill(q(id), now, null); now = now.plusSeconds(2) }
            assertEquals(1, fk.requests.count { it.path!!.contains("/markets/trades") }, "4 quotes x 5 passes inside the window: one read")
            now = now.plusSeconds(20)
            ladder.paperFill(q(1), now, null)
            assertEquals(2, fk.requests.count { it.path!!.contains("/markets/trades") }, "re-read once the window passes")
            ladder.paperFill(q(1).copy(ticker = "KXBTCD-Y"), now.plusSeconds(3_600), null)
            assertEquals(1, ladder.cachedTapes, "tapes of tickers no longer asked about are dropped")
        }
    }
}
