package com.marketbot.core.contract

import com.marketbot.core.kalshi.KalshiWording
import com.marketbot.core.net.ApiException
import com.marketbot.core.strategy.Reasons
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wording rules themselves, offline. The live side is VenueLiveContractTest, run daily. */
class WordingTest {
    private fun obj(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    @Test fun `the taker's side is read from the new fields first and the deprecated one last`() {
        assertEquals("no", Vocabulary.kalshiTakerSide(obj("taker_outcome_side" to "no", "taker_side" to "yes")))
        assertEquals("yes", Vocabulary.kalshiTakerSide(obj("taker_book_side" to "bid")))
        assertEquals("no", Vocabulary.kalshiTakerSide(obj("taker_book_side" to "ask")))
        assertEquals("yes", Vocabulary.kalshiTakerSide(obj("taker_side" to "yes")), "still read while Kalshi serves it")
        assertNull(Vocabulary.kalshiTakerSide(obj()))
        assertEquals("no", Vocabulary.kalshiFillSide(obj("outcome_side" to "no", "side" to "yes")))
    }

    @Test fun `Polymarket BTC ladder slugs match with and without the prefix Polymarket added`() {
        assertTrue(Vocabulary.isPmBtcMarket("cpc-btc-above-day-2026-10-29-77250"), "seen live 2026-10-29")
        assertTrue(Vocabulary.isPmBtcMarket("btc-above-day-2026-10-29-77250"))
        assertFalse(Vocabulary.isPmBtcMarket("cpc-eth-above-day-2026-10-29-3000"))
        assertFalse(Vocabulary.isPmBtcMarket("nba-lal-bos-2026-10-29"))
    }

    @Test fun `the order refusals the app recognises by their words`() {
        assertTrue(KalshiWording.groupTripped("order group is triggered"))
        assertTrue(KalshiWording.groupTripped("order_group_limit_exceeded"))
        assertFalse(KalshiWording.groupTripped("order group is paused"), "unknown wording: the order path then looks the group up and fails safe")
        assertTrue(KalshiWording.postOnlyCross("post only order would cross"))
        assertFalse(KalshiWording.postOnlyCross("insufficient balance"))
    }

    @Test fun `a probe calls an outage a skip, and a missing endpoint or a parse error a failure`() = runBlocking {
        suspend fun v(b: suspend () -> ApiContract.Outcome) = ApiContract.probe("a", "c", "x", b).verdict
        assertEquals(ApiContract.Verdict.SKIP, v { throw IOException("timeout") })
        assertEquals(ApiContract.Verdict.SKIP, v { throw ApiException(503, null, "busy") })
        assertEquals(ApiContract.Verdict.FAIL, v { throw ApiException(404, null, "gone") })
        assertEquals(ApiContract.Verdict.FAIL, v { throw IllegalStateException("no price") })
        Unit
    }

    @Test fun `the spec check flags a missing field, a deprecated one and a new enum value`() {
        fun schema(props: Map<String, Any?>) = mapOf("properties" to props)
        val marketProps = Vocabulary.KALSHI_FIELDS.getValue("Market").associateWith { mapOf("type" to "string") }.toMutableMap<String, Any?>()
        marketProps["status"] = mapOf("enum" to Vocabulary.KALSHI_MARKET_STATUSES.toList() + "suspended")
        marketProps.remove("floor_strike")
        val trade = Vocabulary.KALSHI_FIELDS.getValue("Trade").associateWith { mapOf<String, Any?>("type" to "string") }.toMutableMap<String, Any?>()
        trade["count_fp"] = mapOf("type" to "string", "description" to "Deprecated. Use count_dollars.")
        val spec = mapOf("components" to mapOf("schemas" to mapOf("Market" to schema(marketProps), "Trade" to schema(trade))))
        val f = KalshiSpecCheck.check(spec).associateBy { "${it.area}/${it.check}" }
        assertEquals(ApiContract.Verdict.FAIL, f.getValue("Kalshi spec/Market fields").verdict)
        assertTrue("floor_strike" in f.getValue("Kalshi spec/Market fields").detail)
        assertEquals(ApiContract.Verdict.WARN, f.getValue("Kalshi spec/Trade fields").verdict)
        assertEquals(ApiContract.Verdict.WARN, f.getValue("Kalshi spec/Market.status values").verdict)
        assertTrue("suspended" in f.getValue("Kalshi spec/Market.status values").detail)
        assertEquals(ApiContract.Verdict.FAIL, f.getValue("Kalshi spec/Settlement fields").verdict, "a schema gone from the spec")
    }

    /**
     * The app's own words: a strategy's pass summary is turned into a trading state by
     * matching its text (Reasons.ofSummary). Renaming a summary in a strategy would quietly
     * drop the reason from the home screen. Every summary a strategy can return must map
     * to a reason code, or be one of the summaries that mean "working normally".
     */
    @Test fun `every pass summary a strategy can return maps to a reason code or means working`() {
        val normal = setOf("quote", "tick", "off", "no bets")
        val src = File("src/main/kotlin/com/marketbot/core/strategies").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(src.isNotEmpty(), "run from the core module")
        val said = mutableSetOf<String>()
        for (f in src) {
            val t = f.readText()
            Regex("""RunReport\("([a-z_ ]+)"""").findAll(t).forEach { said += it.groupValues[1] }
            Regex("""RunReport\("(budget spent)""").findAll(t).forEach { said += it.groupValues[1] }
            if (f.name == "DeepItm.kt") {
                // guardMode's returns and the guard reassignments become the pass summary.
                Regex("""(?:return|guard =)[^"\n]*"([a-z_]+)"(?: else "([a-z_]+)")?""").findAll(t).forEach { m ->
                    m.groupValues.drop(1).filter { it.isNotEmpty() }.forEach { said += it }
                }
            }
        }
        val deepItm = setOf("killed", "total_kill", "daily_stop", "stale_index", "too_few_venues", "warming_up", "exchange_closed", "listing_failed", "listing_retry")
        assertTrue(said.containsAll(deepItm), "the scan sees the deep-ITM guards: ${deepItm - said}")
        val unmapped = said.filter { it !in normal && Reasons.ofSummary(it) == null }
        assertTrue(unmapped.isEmpty(), "pass summaries with no reason code (add them to Reasons.ofSummary): $unmapped")
    }
}

class EngineContractCheckTest {
    private val t0 = java.time.Instant.parse("2026-10-29T12:00:00Z")

    @Test fun `the daily check notifies a new finding once, and an offline run does not replace the last result`() = runBlocking {
        val stores = com.marketbot.core.store.inMemoryStores()
        val clock = com.marketbot.core.util.MutableClock(t0)
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fake = com.marketbot.core.FakeKalshi()
        try {
            val e = com.marketbot.core.engine.Engine(stores, clock, kindedNotifier = { k, t, _ -> notes += "$k: $t" },
                clientFactory = { _ -> fake.client(false) to com.marketbot.core.polymarket.PolymarketClient(null) }, fillStreamFactory = null)
            fun f(v: ApiContract.Verdict) = ApiContract.Finding("Polymarket", "daily BTC ladder event", v, "slugs changed", "BTC deep-ITM (Polymarket)")
            var next = listOf(f(ApiContract.Verdict.FAIL))
            e.contractRunner = { _, _, t -> ApiContract.Report(t.toString(), next) }
            e.contractCheckIfDue()
            assertEquals(listOf("safety: A venue changed something the app relies on"), notes.toList())
            assertNull(e.contractCheckIfDue(), "not due again within a day")
            clock.advanceSeconds(25 * 3600)
            e.contractCheckIfDue()
            assertEquals(1, notes.size, "the same finding is not notified twice")
            next = listOf(f(ApiContract.Verdict.SKIP))
            clock.advanceSeconds(25 * 3600)
            e.contractCheckIfDue()
            assertEquals(ApiContract.Verdict.FAIL, e.contractReport()!!.findings.single().verdict, "an all-skipped (offline) run keeps the last result")
            next = listOf(f(ApiContract.Verdict.PASS))
            e.contractCheck()
            assertTrue(e.contractReport()!!.failures.isEmpty())
        } finally { fake.close() }
        Unit
    }
}

class AccountProbeTest {
    @Test fun `account probes read one page and grade the payload the P&L comes from`() = runBlocking {
        val fake = com.marketbot.core.FakeKalshi()
        try {
            fake.settlements = """{"settlements":[{"ticker":"A","revenue":500,"fee_cost":"0","yes_count_fp":"0","no_count_fp":"5",
                "yes_total_cost_dollars":"0","no_total_cost_dollars":"4.65","market_result":"no"}],"cursor":"more"}"""
            val f = ApiContract.kalshiAccount(fake.client(signed = true)).associateBy { it.check }
            assertEquals(ApiContract.Verdict.PASS, f.getValue("settlement payload").verdict, f.toString())
            fake.settlements = """{"settlements":[{"ticker":"A","revenue":500,"fee_cost":"0","market_result":"no"}],"cursor":""}"""
            val g = ApiContract.kalshiAccount(fake.client(signed = true)).associateBy { it.check }
            assertEquals(ApiContract.Verdict.FAIL, g.getValue("settlement payload").verdict)
            assertTrue("yes_count_fp" in g.getValue("settlement payload").detail)
            assertEquals(ApiContract.Verdict.SKIP, ApiContract.kalshiAccount(fake.client(signed = false)).single().verdict, "no key, no account reads")
        } finally { fake.close() }
        Unit
    }
}

class BtcEventProbeTest {
    private fun run(listed: Set<String>, now: java.time.Instant): ApiContract.Finding = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val p = request.requestUrl!!.encodedPath
                val slug = p.removePrefix("/v1/events/slug/")
                return if (p.startsWith("/v1/events/slug/") && slug in listed)
                    okhttp3.mockwebserver.MockResponse().setBody("""{"event":{"slug":"$slug","markets":[{"slug":"cpc-$slug-73000","active":true,"closed":false,
                        "assetPriceTerms":{"strike":{"value":"73000"},"windowEnd":"2026-10-29T21:00:00Z"}}]}}""")
                else okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("""{"code":5,"message":"not found"}""")
            }
        }
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val pm = com.marketbot.core.polymarket.PolymarketClient(null, gateway = com.marketbot.core.net.JsonHttp(base, sleep = {}, maxRetries = 0),
                api = com.marketbot.core.net.JsonHttp(base, sleep = {}, maxRetries = 0))
            ApiContract.polymarketPublic(pm, now).single { it.check == "daily BTC ladder event" }
        } finally { server.shutdown() }
    }

    @Test fun `the next BTC event not being listed yet is not a failure, only close to its close`() {
        val evening = java.time.Instant.parse("2026-10-30T00:36:00Z")        // 19:36 CDT: tomorrow's event not listed yet
        assertEquals(ApiContract.Verdict.PASS, run(setOf("btc-above-day-2026-10-29"), evening).verdict, "the format is checked on the latest listed one")
        val nearClose = java.time.Instant.parse("2026-10-30T19:30:00Z")      // 15:30 ET, 1.5 h before
        assertEquals(ApiContract.Verdict.WARN, run(setOf("btc-above-day-2026-10-29"), nearClose).verdict)
        assertEquals(ApiContract.Verdict.FAIL, run(emptySet(), evening).verdict, "neither listed: the slug format changed")
        assertEquals(ApiContract.Verdict.PASS, run(setOf("btc-above-day-2026-10-30"), nearClose).verdict)
    }
}
