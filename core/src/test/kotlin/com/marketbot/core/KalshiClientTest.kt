package com.marketbot.core

import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.net.JsonHttp
import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.util.JSON
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KalshiClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val pem = golden("signatures").o()["rsa_pss"].o()["pkcs1_pem"].s!!

    @AfterTest fun stop() = server.shutdown()

    private fun client(handler: (RecordedRequest) -> MockResponse): KalshiClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return handler(request) }
        }
        server.start()
        val base = server.url("/trade-api/v2").toString().trimEnd('/')
        return KalshiClient(KalshiSigner("kid", pem), base, http = JsonHttp(base, sleep = {}))
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    @Test fun `public listing is unsigned and follows the cursor`() = runTest {
        val k = client { r ->
            if (r.requestUrl!!.queryParameter("cursor") == null) json("""{"markets":[{"ticker":"A"}],"cursor":"c1"}""")
            else json("""{"markets":[{"ticker":"B"}],"cursor":""}""")
        }
        assertEquals(listOf("A", "B"), k.openMarkets("KXHIGHNY").map { it.ticker })
        assertTrue(requests.all { it.getHeader("KALSHI-ACCESS-KEY") == null }, "market data must not be signed")
    }

    @Test fun `portfolio calls are signed`() = runTest {
        val k = client { json("""{"balance":100,"balance_dollars":"1.00","portfolio_value":0}""") }
        k.balance()
        val r = requests.single()
        assertEquals("kid", r.getHeader("KALSHI-ACCESS-KEY"))
        assertNotNull(r.getHeader("KALSHI-ACCESS-SIGNATURE"))
        assertEquals("/trade-api/v2/portfolio/balance", r.requestUrl!!.encodedPath)
    }

    @Test fun `an order names the market shard, rests post-only and rounds an ask up`() = runTest {
        val k = client { json("""{"order_id":"oid-1","client_order_id":"x","fill_count":"0.00","remaining_count":"5.00","ts_ms":1}""", 201) }
        val placed = k.placeOrder("KXBTCD-26OCT2717-T75000", "ask", 5, 0.0501, 0.01, exchangeIndex = 2)
        assertEquals("oid-1", placed.orderId)
        val body = JSON.parseToJsonElement(requests.single().body.readUtf8()) as JsonObject
        assertEquals("2", body["exchange_index"].toString())
        assertEquals("true", body["post_only"].toString())
        assertEquals("\"ask\"", body["side"].toString())
        assertEquals("\"0.0600\"", body["price"].toString())
        assertEquals("\"5\"", body["count"].toString())
        assertEquals(placed.clientOrderId, body["client_order_id"].s)
    }

    @Test fun `a post-only cross is reported as filtered, not as an order`() = runTest {
        val k = client { json("""{"error":{"code":"post_only_cross","message":"order would cross"}}""", 400) }
        val p = k.placeOrder("T", "ask", 1, 0.05, 0.01, 0)
        assertTrue(p.filtered)
        assertNull(p.orderId)
    }

    @Test fun `an order create is never retried, and a lost response is recovered by client order id`() = runTest {
        var creates = 0
        val k = client { r ->
            when {
                r.method == "POST" -> { creates++; json("""{"error":{"code":"internal","message":"boom"}}""", 500) }
                r.requestUrl!!.encodedPath.endsWith("/portfolio/orders") -> {
                    val coid = JSON.parseToJsonElement(requests.first { it.method == "POST" }.body.clone().readUtf8()).o()["client_order_id"].s
                    json("""{"orders":[{"order_id":"recovered","client_order_id":"$coid","status":"resting"}],"cursor":""}""")
                }
                else -> json("{}")
            }
        }
        val p = k.placeOrder("T", "ask", 1, 0.05, 0.01, 0)
        assertEquals(1, creates, "a POST must never be retried")
        assertEquals("recovered", p.orderId)
    }

    @Test fun `GETs retry on 429 then succeed`() = runTest {
        var n = 0
        val k = client { if (n++ < 2) json("""{"error":{"code":"too_many_requests"}}""", 429) else json("""{"market":{"ticker":"T","status":"finalized","result":"no"}}""") }
        val m = k.market("T")
        assertEquals("no", m.result)
        assertEquals(3, requests.size)
    }

    @Test fun `cancel routes by market ticker`() = runTest {
        val k = client { json("""{"order_id":"o","reduced_by":"5.00","ts_ms":1}""") }
        assertEquals(5.0, k.cancelOrder("o", "KXBTCD-X")!!.value)
        val r = requests.single()
        assertEquals("DELETE", r.method)
        assertEquals("KXBTCD-X", r.requestUrl!!.queryParameter("market_ticker"))
    }

    @Test fun `a listing that never ends is an error, not a silent truncation`() = runTest {
        val k = client { json("""{"markets":[{"ticker":"A"}],"cursor":"again"}""") }
        assertFailsWith<com.marketbot.core.kalshi.TruncatedListing> { k.markets(emptyList(), maxPages = 3) }
    }
}
