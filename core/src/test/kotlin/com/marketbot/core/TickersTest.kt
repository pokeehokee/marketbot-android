package com.marketbot.core

import com.marketbot.core.util.Tickers
import kotlin.test.Test
import kotlin.test.assertEquals

class TickersTest {
    @Test fun `tickers read as words`() {
        assertEquals("NYC high 70-71°F, Wed Oct 28", Tickers.describe("KXHIGHNY-26OCT28-B70.5"))
        assertEquals("Dallas high 90-91°F, Wed Oct 28", Tickers.describe("KXHIGHTDAL-26OCT28-B90.5"))
        assertEquals("NYC low tail at 50°F, Thu Oct 29", Tickers.describe("KXLOWTNYC-26OCT29-T50"))
        assertEquals("BTC above \$74,000 at 5 PM ET, Oct 27", Tickers.describe("KXBTCD-26OCT2717-T74000"))
        assertEquals("BTC above \$74,250.5 at 12 AM ET, Oct 28", Tickers.describe("KXBTCD-26OCT2800-T74250.5"))
        assertEquals("some-polymarket-slug", Tickers.describe("some-polymarket-slug"))
        assertEquals("KXBTCD-garbage", Tickers.describe("KXBTCD-garbage"))
    }
}
