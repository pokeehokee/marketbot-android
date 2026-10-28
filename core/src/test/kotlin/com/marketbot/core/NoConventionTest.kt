package com.marketbot.core

import com.marketbot.core.btc.BtcIndex
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.PolymarketSigner
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.rewards.RewardFarm
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.StrategyContext
import com.marketbot.core.util.JSON
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live NO-side quoting waits for evidence of how Polymarket prices a short. */
class NoConventionTest {
    private val server = MockWebServer()
    /** What the preview endpoint does with a BUY_SHORT at a given wire price. */
    var preview: (Double) -> MockResponse = { MockResponse().setResponseCode(400).setBody("""{"code":"bad"}""") }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/markets/s1/book" -> MockResponse().setBody("""{"marketData":{"bids":[{"px":{"value":"0.38"},"qty":"50"}],"offers":[{"px":{"value":"0.40"},"qty":"50"}],"state":"MARKET_STATE_OPEN"}}""")
                "/v1/order/preview" -> {
                    val b = JSON.parseToJsonElement(request.body.readUtf8()) as JsonObject
                    preview(((b["request"] as JsonObject)["price"] as JsonObject)["value"].s!!.toDouble())
                }
                else -> MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
    }
    @AfterTest fun close() = server.shutdown()

    private fun ctx(): StrategyContext {
        val base = server.url("").toString().trimEnd('/')
        val pm = PolymarketClient(PolymarketSigner("kid", golden("signatures").o()["ed25519"].o()["seed_b64"].s!!),
            gateway = JsonHttp(base, sleep = {}), api = JsonHttp(base, sleep = {}))
        val f = RewardFarm()
        return StrategyContext(f.id, Mode.LIVE, StrategyConfig(f.params, mapOf("short_quotes" to "true")), inMemoryStores(),
            MutableClock(Instant.parse("2026-10-28T12:00:00Z")), com.marketbot.core.kalshi.KalshiClient(null), pm, BtcIndex(emptyMap(), { emptyList() }), true)
    }

    @Test fun `a YES-price preview costing 1 - ask confirms the convention`() = runTest {
        preview = { px -> MockResponse().setBody("""{"preview":{"cost":${"%.4f".format(1 - px)}}}""") }
        val (v, why) = RewardFarm().noConvention(ctx(), listOf("s1"))
        assertEquals(RewardFarm.NoConvention.YES_PRICE, v, why)
    }

    @Test fun `a preview that costs the ask means NO-priced shorts, and quoting stays off`() = runTest {
        preview = { px -> MockResponse().setBody("""{"preview":{"cost":${"%.4f".format(px)}}}""") }
        assertEquals(RewardFarm.NoConvention.NO_PRICE, RewardFarm().noConvention(ctx(), listOf("s1")).first)
    }

    @Test fun `no cost field and both forms accepted is not evidence`() = runTest {
        preview = { MockResponse().setBody("""{"preview":{}}""") }
        val (v, why) = RewardFarm().noConvention(ctx(), listOf("s1"))
        assertEquals(RewardFarm.NoConvention.UNVERIFIED, v); assertTrue("Not conclusive" in why)
    }

    @Test fun `only the YES-price form accepted confirms it`() = runTest {
        preview = { px -> if (kotlin.math.abs(px - 0.40) < 1e-9) MockResponse().setBody("{}") else MockResponse().setResponseCode(400).setBody("{}") }
        assertEquals(RewardFarm.NoConvention.YES_PRICE, RewardFarm().noConvention(ctx(), listOf("s1")).first)
    }
}
