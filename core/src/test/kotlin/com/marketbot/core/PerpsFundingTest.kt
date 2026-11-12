package com.marketbot.core

import com.marketbot.core.margin.MarginClient
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.research.PerpsFunding
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The perps research recorder: public data only, no duplicates, and nothing that can trade. */
class PerpsFundingTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<RecordedRequest>()
    private val now = Instant.parse("2026-11-12T00:00:00Z")

    @AfterTest fun stop() = server.shutdown()

    private fun market(t: String, cls: String, bid: String, ask: String, vol: String) = """
        {"ticker":"$t","asset_class":"$cls","status":"active","bid":"$bid","ask":"$ask",
         "bid_size_fp":"100.00","ask_size_fp":"120.00","price":"$ask",
         "reference_price":{"price":"7.3967","ts_ms":1},"liquidation_mark_price":{"price":"7.4"},
         "open_interest_notional_value_dollars":"16671357","volume_24h_notional_value_dollars":"$vol",
         "leverage_estimate":"6.3858","contract_size":"0.000100","tick_size":"0.0001"}"""

    private fun serve(fundingByTicker: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val url = request.requestUrl!!
                val path = url.encodedPath
                fun ok(b: String) = MockResponse().setResponseCode(200).setBody(b)
                return when {
                    path.endsWith("/margin/exchange/status") -> ok("""{"exchange_active":true,"trading_active":true}""")
                    path.endsWith("/margin/markets") -> ok("""{"markets":[
                        ${market("KXBTCPERP", "Crypto", "7.3746", "7.3750", "438368508")},
                        ${market("KXGOLDPERP", "Metals", "4.0000", "4.0009", "33777115")},
                        {"ticker":"KXDEADPERP","asset_class":"Equities","status":"active","bid":"0.0000","ask":"0.0000",
                         "volume_24h_notional_value_dollars":"999999999"}]}""")
                    path.endsWith("/margin/funding_rates/estimate") -> ok("""{"market_ticker":"${url.queryParameter("ticker")}",
                        "funding_rate":0.0001099285726115,"premium_index":"0.0000083818466506","mark_price":"7.3974",
                        "next_funding_time":"2026-11-12T05:00:00Z"}""")
                    path.endsWith("/margin/funding_rates/historical") ->
                        ok(fundingByTicker[url.queryParameter("ticker")] ?: """{"funding_rates":[]}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
    }

    private fun client() = MarginClient(http = JsonHttp(server.url("").toString().trimEnd('/')))

    private val btcHistory = """{"funding_rates":[
        {"market_ticker":"KXBTCPERP","funding_time":"2026-11-11T21:00:00Z","funding_rate":0,"mark_price":"7.4587"},
        {"market_ticker":"KXBTCPERP","funding_time":"2026-11-11T13:00:00Z","funding_rate":0.0002422984889155,"mark_price":"7.3822"}]}"""

    @Test fun `a pass records the busiest two-sided markets, the premium and the finalized funding`() = runTest {
        serve(mapOf("KXBTCPERP" to btcHistory))
        val p = PerpsFunding.read(client(), now)
        assertTrue(p.tradingOpen)
        assertEquals(3, p.listed)
        assertEquals(listOf("KXBTCPERP", "KXGOLDPERP"), p.samples.map { it.ticker },
            "a market with no two-sided book is not sampled however much notional it claims")
        val btc = p.samples.first()
        assertEquals(7.3746, btc.bid!!, 1e-9)
        assertEquals(7.3967, btc.referencePrice!!, 1e-9)
        assertEquals(0.0000083818466506, btc.premium!!, 1e-15)
        assertEquals(Instant.parse("2026-11-12T05:00:00Z"), btc.nextFunding)
        assertEquals(6.3858, btc.leverage!!, 1e-9)
        assertEquals(2, p.funding.size)
        assertEquals(Instant.parse("2026-11-11T13:00:00Z"), p.funding.first().at, "oldest first, so a sink appends in order")
        assertEquals(0.0002422984889155, p.funding.first().rate, 1e-15)
        assertEquals(7.3822, p.funding.first().markPrice!!, 1e-9)
    }

    @Test fun `funding already recorded is not collected twice`() = runTest {
        serve(mapOf("KXBTCPERP" to btcHistory))
        val first = PerpsFunding.read(client(), now)
        val marks = PerpsFunding.watermarks(first.funding)
        assertEquals(Instant.parse("2026-11-11T21:00:00Z"), marks["KXBTCPERP"])
        val second = PerpsFunding.read(client(), now, seen = marks)
        assertTrue(second.funding.none { it.ticker == "KXBTCPERP" }, "nothing new past the watermark")
        assertEquals(2, second.samples.size, "samples still accrue: they are a time series, not a backfill")
    }

    @Test fun `a market that fails loses only itself, and a dead listing loses the pass nothing`() = runTest {
        serve(emptyMap())        // every history call returns an empty list
        val p = PerpsFunding.read(client(), now)
        assertEquals(2, p.samples.size)
        assertTrue(p.funding.isEmpty())
        assertEquals(PerpsFunding.SAMPLE_HEADER.split(",").size, p.samples.first().csv().split(",").size,
            "the CSV row matches its header")
    }

    @Test fun `the recorder only ever reads public market data`() = runTest {
        serve(mapOf("KXBTCPERP" to btcHistory))
        PerpsFunding.read(client(), now)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.method == "GET" }, "a recorder never writes: " + seen.map { it.method }.distinct())
        assertTrue(seen.none { it.getHeader("KALSHI-ACCESS-KEY") != null || it.getHeader("KALSHI-ACCESS-SIGNATURE") != null },
            "no credentials are sent: perps market data is public")
        assertTrue(seen.none { it.requestUrl!!.encodedPath.contains("orders") || it.requestUrl!!.encodedPath.contains("portfolio") },
            "never an order or portfolio path")
    }

    @Test fun `no live code path can place a perps order`() {
        val src = File("src/main/kotlin/com/marketbot/core/margin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(src.isNotEmpty(), "run from the core module")
        for (f in src) {
            val t = f.readText()
            assertNull(Regex("""http\.(post|put|delete)""").find(t)?.value,
                "${f.name} must not write to the perps exchange: the app does not trade perps")
        }
    }
}
