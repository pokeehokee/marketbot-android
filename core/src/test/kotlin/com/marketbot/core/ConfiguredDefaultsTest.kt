package com.marketbot.core

import com.marketbot.core.strategies.deepitm.KalshiBtcDeepItm
import com.marketbot.core.strategies.longshot.KalshiCommodityLongshot
import com.marketbot.core.strategies.longshot.PolymarketTemperatureLongshot
import com.marketbot.core.strategy.EvidenceLevel
import com.marketbot.core.strategy.StrategyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the two Kalshi books are SET to, frozen (2026-11-15).
 *
 * Both of these drifted the same way: a number measured on history outlived the live record that
 * replaced it, and the app went on showing the superseded figure. An expectation is not
 * decoration -- [com.marketbot.core.strategy.Scorecard] grades real fills against it, so one set
 * below what the book really earns is a health check that can never fail.
 */
class ConfiguredDefaultsTest {

    /**
     * The ladder put 1,805 positions and 9,028 contracts through the exchange at +1.80c with 81
     * losing positions. For weeks the app still described it from a replay as "Tested: no edge"
     * at -1.22c, and asked the operator to accept that verdict before going live.
     */
    @Test fun `the BTC ladder is described by its live record, not the replay that missed it`() {
        val e = KalshiBtcDeepItm().evidence
        assertEquals(EvidenceLevel.LIVE_PROVEN, e.level, "1,805 exchange-graded positions is a live result")
        val x = assertNotNull(e.expectation)
        assertEquals(0.0180, x.perContract, 1e-9)
        assertTrue(assertNotNull(x.low) > 0.0, "the live interval excludes zero; a negative bar cannot be failed")
        assertTrue("settlement payloads" in x.basis, "the basis must name the exchange, not a replay")
    }

    /** A soft "accept there is no edge" prompt must not outlive the evidence that created it. */
    @Test fun `going live no longer asks the operator to accept a refuted replay`() {
        assertTrue(KalshiBtcDeepItm().evidence.level != EvidenceLevel.NO_EDGE)
    }

    /**
     * 37% of the commodity sample had not been quoted for hours and carries zero losses, so the
     * blended +3.88c is a population figure no resting order can realise. Fills come from
     * contested books, and fills are what a live result gets compared against.
     */
    @Test fun `the commodity book expects the contested figure, never the blended one`() {
        val x = assertNotNull(KalshiCommodityLongshot().evidence.expectation)
        assertEquals(0.0422, x.perContract, 1e-9)
        assertTrue(assertNotNull(x.low) > 0.0)
        assertTrue("CONTESTED" in x.basis, "the basis must say which population it is")
    }

    /**
     * Lot 1 funds one day's whole ~56-market pool for ~50 dollars, so the losses that decide the
     * verdict arrive as fast as the market makes them. At lot 5 the same pool wants ~266 and
     * would be collateral-bound, funding an arbitrary subset chosen by no measured rule.
     */
    @Test fun `the commodity book starts live at the smallest lot, and one market cannot outrun the brake`() {
        val s = KalshiCommodityLongshot()
        val c = StrategyConfig(s.params)
        assertEquals(1, c.int("lot_cap"))
        val worst = c.int("lot_cap") * (1 - c.num("min_cheap"))
        assertTrue(c.num("daily_loss_limit") >= 2 * worst,
            "the brake must stop the day before a second maximum loss (brake ${c.num("daily_loss_limit")}, worst $worst)")
        assertTrue(s.validate(c).isEmpty(), "the shipped defaults must validate: ${s.validate(c)}")
    }

    /** The weather lot stays where its own measurement put it; only the commodity book overrides. */
    @Test fun `lowering one book's lot does not move another's`() {
        assertEquals(5, StrategyConfig(PolymarketTemperatureLongshot().params).int("lot_cap"))
    }
}
