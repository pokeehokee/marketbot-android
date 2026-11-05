package com.marketbot.core

import com.marketbot.core.engine.Engine
import com.marketbot.core.polymarket.PolymarketClient
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategies.longshot.WeatherLongshot
import com.marketbot.core.strategy.Mode
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.sync.GitHubMailbox
import com.marketbot.core.sync.Proposal
import com.marketbot.core.sync.ProposalPolicy
import com.marketbot.core.sync.SyncService
import com.marketbot.core.util.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Claude sync: what a proposal may change, the GitHub mailbox, and a full sync / approve / reject cycle. */
class ClaudeSyncTest {
    private val weather = WeatherLongshot("kalshi_weather_longshot@b", WeatherLongshot.ID)
    private val cfg = StrategyConfig(weather.params)

    private fun check(p: Proposal, mode: Mode = Mode.PAPER) = ProposalPolicy.check(p, weather, cfg, mode)

    @Test fun `paper settings can be proposed, and live, riskier or unknown changes are refused`() {
        val ok = assertIs<ProposalPolicy.Verdict.Ok>(check(Proposal("1", weather.id, mapOf("max_mid_high" to "0.15"))))
        assertEquals(mapOf("max_mid_high" to "0.15"), ok.changes); assertFalse(ok.needsStrongConfirm)
        // turning a paper strategy on takes the screen lock
        assertTrue(assertIs<ProposalPolicy.Verdict.Ok>(check(Proposal("2", weather.id, mode = "PAPER"), Mode.OFF)).needsStrongConfirm)
        val refused = listOf(
            Proposal("3", weather.id, mode = "LIVE") to Mode.PAPER,                                  // never live
            Proposal("4", weather.id, mapOf("max_mid_high" to "0.15")) to Mode.LIVE,                 // not on a live strategy
            Proposal("5", weather.id, mapOf("lot_cap" to "10")) to Mode.PAPER,                       // raising the lot
            Proposal("6", weather.id, mapOf("daily_loss_limit" to "50")) to Mode.PAPER,              // raising the brake
            Proposal("7", weather.id, mapOf("collateral_fraction" to "0.95")) to Mode.PAPER,         // more money at risk
            Proposal("8", weather.id, mapOf("max_mid_high" to "0.9")) to Mode.PAPER,                 // outside the setting's range
            Proposal("9", weather.id, mapOf("no_such_key" to "1")) to Mode.PAPER,
            Proposal("10", "not_a_strategy", mapOf("max_mid_high" to "0.15")) to Mode.PAPER,
            Proposal("11", weather.id) to Mode.PAPER,                                                // nothing to change
        )
        for ((p, m) in refused) {
            val s = if (p.strategy == weather.id) weather else null
            assertIs<ProposalPolicy.Verdict.Refused>(ProposalPolicy.check(p, s, s?.let { cfg }, m), p.toString())
        }
        // lowering a risk setting is allowed
        assertIs<ProposalPolicy.Verdict.Ok>(check(Proposal("12", weather.id, mapOf("lot_cap" to "3", "daily_loss_limit" to "5"))))
    }

    /** A fake GitHub contents API: files in a map, sha = content hash; a write must name the current sha. */
    private class FakeGitHub : Dispatcher() {
        val files = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()
        private fun sha(t: String) = t.hashCode().toString()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath.substringAfter("/contents/")
            if (request.getHeader("Authorization") != "Bearer tok") return MockResponse().setResponseCode(401)
            return when (request.method) {
                "GET" -> files[path]?.let { MockResponse().setBody("""{"sha":"${sha(it)}","content":"${Base64.getMimeEncoder().encodeToString(it.toByteArray()).replace("\r\n", "\\n")}"}""") }
                    ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    val b = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                    val old = files[path]
                    if (old != null && (b["sha"] as? JsonPrimitive)?.content != sha(old)) return MockResponse().setResponseCode(409)
                    files[path] = String(Base64.getDecoder().decode((b["content"] as JsonPrimitive).content)); writes += path
                    MockResponse().setResponseCode(if (old == null) 201 else 200).setBody("{}")
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    private val server = MockWebServer()
    @AfterTest fun stop() { runCatching { server.shutdown() } }

    @Test fun `a sync sends the snapshot and ledger, holds proposals for approval, and reports what was done`() = runTest {
        val gh = FakeGitHub(); server.dispatcher = gh; server.start()
        val base = server.url("").toString().trimEnd('/')
        FakeKalshi().use { fake ->
            val stores = inMemoryStores()
            val engine = Engine(stores, MutableClock(Instant.parse("2026-11-05T12:00:00Z")), clientFactory = { _ -> fake.client(false) to PolymarketClient(null) })
            val b = engine.strategies.first { it.id == "kalshi_weather_longshot@b" }
            engine.setMode(b, Mode.PAPER)
            gh.files[GitHubMailbox.PENDING] = """{"proposals":[
                {"id":"p1","strategy":"kalshi_weather_longshot@b","set":{"max_mid_high":"0.15"},"reason":"test the 15c cap"},
                {"id":"p2","strategy":"kalshi_weather_longshot@b","mode":"LIVE"},
                {"id":"p3","strategy":"kalshi_weather_longshot@c","mode":"PAPER"}]}"""
            val sync = SyncService(engine) { r, t -> GitHubMailbox(r, t, base) }
            val line = sync.sync("me/sync", "tok", "1.0.test")
            assertTrue("3 proposal(s) waiting" in line && "1 refused" in line, line)
            assertTrue(GitHubMailbox.SNAPSHOT in gh.files && "kalshi_weather_longshot@b" in gh.files.getValue(GitHubMailbox.SNAPSHOT))
            assertTrue(gh.files.getValue(GitHubMailbox.ORDERS).startsWith("strategy,mode,venue"))
            assertTrue(listOf("BEGIN", "PRIVATE", "token", "tok\"").none { it in gh.files.getValue(GitHubMailbox.SNAPSHOT) }, "no secrets sent")

            val pend = sync.pending().associateBy { it.proposal.id }
            assertEquals(null, pend.getValue("p1").refused); assertFalse(pend.getValue("p1").strongConfirm)
            assertTrue("live" in pend.getValue("p2").refused!!)
            assertTrue(pend.getValue("p3").strongConfirm, "turning a strategy on takes the screen lock")

            assertTrue(sync.approve("p1").startsWith("Applied"))
            assertEquals(0.15, engine.config(b).num("max_mid_high"))
            assertEquals(0.75, engine.config(b).num("collateral_fraction"), "other settings untouched")
            assertEquals("Rejected.", sync.reject("p3"))
            assertEquals(Mode.OFF, engine.mode("kalshi_weather_longshot@c"))
            assertEquals(listOf("p2"), sync.pending().map { it.proposal.id })

            // Next sync reports the outcomes and does not bring decided proposals back.
            sync.sync("me/sync", "tok", "1.0.test")
            val results = gh.files.getValue(GitHubMailbox.RESULTS)
            assertTrue("\"p1\"" in results && "applied" in results && "\"p3\"" in results && "rejected" in results, results)
            assertEquals(listOf("p2"), sync.pending().map { it.proposal.id })

            // A bad token is reported, not thrown.
            assertTrue(sync.sync("me/sync", "wrong", "1.0.test").startsWith("Sync failed"))
        }
    }

    @Test fun `a proposal is checked again when approved, against the mode then`() = runTest {
        val gh = FakeGitHub(); server.dispatcher = gh; server.start()
        val base = server.url("").toString().trimEnd('/')
        FakeKalshi().use { fake ->
            val engine = Engine(inMemoryStores(), MutableClock(Instant.parse("2026-11-05T12:00:00Z")), clientFactory = { _ -> fake.client(false) to PolymarketClient(null) })
            val main = engine.strategies.first { it.id == WeatherLongshot.ID }
            engine.setMode(main, Mode.PAPER)
            gh.files[GitHubMailbox.PENDING] = """{"proposals":[{"id":"q","strategy":"${WeatherLongshot.ID}","set":{"max_mid_high":"0.15"}}]}"""
            val sync = SyncService(engine) { r, t -> GitHubMailbox(r, t, base) }
            sync.sync("me/sync", "tok", "t")
            engine.stores.state.put("strategy.${main.id}.mode", Mode.LIVE.name)   // went live in between
            assertTrue(sync.approve("q").startsWith("Not applied"))
            assertEquals(0.25, engine.config(main).num("max_mid_high"))
        }
    }
}
