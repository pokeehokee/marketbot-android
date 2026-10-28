package com.marketbot.core

import com.marketbot.core.kalshi.KalshiFill
import com.marketbot.core.kalshi.KalshiFillStream
import com.marketbot.core.net.KalshiSigner
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamsTest {
    private val server = MockWebServer()
    @AfterTest fun stop() = server.shutdown()

    private fun fill(trade: String) = """{"type":"fill","sid":1,"msg":{"trade_id":"$trade","order_id":"o1","market_ticker":"KXHIGHNY-26OCT29-B70.5",
        "side":"yes","action":"sell","count_fp":"5.00","yes_price_dollars":"0.0700","is_taker":false,"ts_ms":1761700000000}}"""

    @Test fun `the fill channel is signed, subscribes on connect and reports each trade once`() {
        val sent = CopyOnWriteArrayList<String>()
        val subscribed = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                sent += text; subscribed.countDown()
                webSocket.send(fill("t1")); webSocket.send(fill("t1")); webSocket.send(fill("t2"))
                webSocket.send("""{"type":"subscribed","msg":{"channel":"fill","sid":1}}""")
            }
        }))
        server.start()
        val got = CopyOnWriteArrayList<KalshiFill>()
        val done = CountDownLatch(2)
        val pem = golden("signatures").o()["rsa_pss"].o()["pkcs1_pem"].s!!
        val s = KalshiFillStream(KalshiSigner("kid", pem), "ws://${server.hostName}:${server.port}/trade-api/ws/v2") { got += it; done.countDown() }
        s.start()
        assertTrue(subscribed.await(10, TimeUnit.SECONDS))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        s.stop()
        val req = server.takeRequest()
        assertEquals("kid", req.getHeader("KALSHI-ACCESS-KEY"))
        assertNotNull(req.getHeader("KALSHI-ACCESS-SIGNATURE"))
        assertEquals("""{"id":1,"cmd":"subscribe","params":{"channels":["fill"]}}""", sent.single())
        assertEquals(listOf("t1", "t2"), got.map { it.tradeId })
        assertEquals(5.0, got[0].count); assertEquals(0.07, got[0].yesPrice); assertEquals(false, got[0].isTaker)
    }

    @Test fun `non-fill messages are not fills`() {
        assertNull(KalshiFill.parse(com.marketbot.core.util.parseObject("""{"type":"subscribed","msg":{}}""")))
        assertNull(KalshiFill.parse(com.marketbot.core.util.parseObject("""{"type":"fill","msg":{"order_id":"o"}}""")))
    }
}
