package com.marketbot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marketbot.app.data.Crypter
import com.marketbot.app.data.MarketbotDb
import com.marketbot.app.data.Vault
import com.marketbot.app.data.roomStores
import com.marketbot.core.provenance.Source
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.store.LogEvent
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.store.inMemoryStores
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The database must hand back exactly what was stored, sources included, and behave like the reference stores. */
@RunWith(RobolectricTestRunner::class)
class RoomStoresTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = MarketbotDb.inMemory(ctx)
    private val room = roomStores(db)
    @After fun close() = db.close()

    private val row = QuoteRow(strategyId = "s", mode = Mode.LIVE, venue = "kalshi", ticker = "KXHIGHNY-26OCT28-B70.5", action = "sell_yes",
        price = 0.07, contracts = 5.0, placedAt = Instant.parse("2026-10-27T20:00:00.123Z"), closeTime = Instant.parse("2026-10-29T05:00:00Z"),
        orderId = "o1", clientOrderId = "c1", marketKind = "HIGH", queueAhead = 10.0, extra = buildJsonObject { put("yes_bid", 0.06) })

    @Test fun `a ledger row survives a round trip with its sources`() = runTest {
        val id = room.ledger.insert(row)
        val settled = row.copy(id = id, filledContracts = 5.0, fillSource = "Kalshi API order#fill_count_fp", resolvedAt = Instant.parse("2026-10-29T13:00:00Z"),
            result = "no", pnl = 0.35, pnlSource = Source.KALSHI_API, pnlOrigin = "GET /portfolio/settlements[...]", hypothetical = false)
        room.ledger.update(settled)
        assertEquals(settled, room.ledger.byId(id))
        assertTrue(room.ledger.open("s", Mode.LIVE).isEmpty())
        assertEquals(1, room.ledger.all("s", Mode.LIVE).size)
        assertTrue(room.ledger.all("s", Mode.PAPER).isEmpty(), "paper and live never mix")
    }

    @Test fun `room ledger answers the same queries as the reference in-memory ledger`() = runTest {
        val mem = inMemoryStores().ledger
        for (l in listOf(room.ledger, mem)) {
            l.insert(row); l.insert(row.copy(mode = Mode.PAPER)); l.insert(row.copy(ticker = "B", placedAt = Instant.parse("2026-10-28T00:00:00Z")))
            val first = l.all("s", Mode.LIVE).first(); l.update(first.copy(resolvedAt = Instant.EPOCH))
        }
        assertEquals(mem.open("s", Mode.LIVE).map { it.copy(id = 0) }, room.ledger.open("s", Mode.LIVE).map { it.copy(id = 0) })
        assertEquals(mem.all("s", null, Instant.parse("2026-10-27T23:00:00Z")).map { it.ticker }, room.ledger.all("s", null, Instant.parse("2026-10-27T23:00:00Z")).map { it.ticker })
    }

    @Test fun `log lines keep the source of every value they report`() = runTest {
        val v = Sourced.kalshi(403.12, "GET /portfolio/balance#balance_dollars", Instant.parse("2026-10-27T20:00:00Z"))
        room.events.log(LogEvent(Instant.parse("2026-10-27T20:00:01Z"), "s", LogEvent.Level.INFO, "budget", "Cash", mapOf("cash" to v)))
        val back = room.events.recent(10, "s").single().values.getValue("cash")
        assertEquals(Source.KALSHI_API, back.source); assertEquals(v.origin, back.origin); assertEquals("403.12", back.value)
    }

    @Test fun `equity snapshots keep a missing mark as missing`() = runTest {
        room.equity.record(EquitySnapshot(Instant.parse("2026-10-27T20:00:00Z"), "kalshi", Mode.LIVE, Sourced.kalshi(10.0, "x"), null, null))
        val s = room.equity.history("kalshi", Mode.LIVE, Instant.EPOCH).single()
        assertEquals(Source.KALSHI_API, s.cash.source); assertNull(s.positionsValue); assertNull(s.total)
    }

    @Test fun `audit keeps the latest reading of each value with its origin`() = runTest {
        room.audit.record("kalshi.cash", Sourced.kalshi(1.0, "a"))
        room.audit.record("kalshi.cash", Sourced.derived(2.0, "b", "why"))
        val a = room.audit.latest().getValue("kalshi.cash")
        assertEquals("2.0", a.value); assertEquals(Source.DERIVED, a.source); assertEquals("why", a.note)
    }

    @Test fun `the vault never stores a secret in plain text`() {
        val prefs = ctx.getSharedPreferences("vault-test", Context.MODE_PRIVATE)
        val xor = object : Crypter {
            override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun decrypt(blob: ByteArray) = encrypt(blob)
        }
        val v = Vault(prefs, xor)
        v.saveKalshi("key-1234", "-----BEGIN RSA PRIVATE KEY-----\nSECRET\n-----END RSA PRIVATE KEY-----")
        assertEquals("key-1234", v.credentials().kalshiKeyId)
        assertEquals("1234", v.kalshiHint())
        assertFalse(prefs.all.values.any { it.toString().contains("SECRET") || it.toString().contains("key-1234") })
        v.saveKalshi(null, null)
        assertNull(v.credentials().kalshiPem)
    }

    @Test fun `room ledger keeps every invariant under random histories`() = runTest {
        for (seed in 1..8) {
            val d = MarketbotDb.inMemory(ctx)
            try {
                val l = roomStores(d).ledger
                val ids = com.marketbot.core.store.LedgerChecks.randomHistory(l, seed, n = 250)
                assertEquals(emptyList(), com.marketbot.core.store.LedgerChecks.violations(l, ids), "seed $seed")
            } finally { d.close() }
        }
    }
}
