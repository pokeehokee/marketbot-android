package com.marketbot.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marketbot.app.data.Crypter
import com.marketbot.app.data.EventEntity
import com.marketbot.app.data.MarketbotDb
import com.marketbot.app.data.Vault
import com.marketbot.app.data.roomStores
import com.marketbot.core.provenance.Sourced
import com.marketbot.core.store.EquitySnapshot
import com.marketbot.core.strategy.Mode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Retention, throttling, account options and the records that say when the app was not running. */
@RunWith(RobolectricTestRunner::class)
class SystemTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = MarketbotDb.inMemory(ctx)
    @After fun close() = db.close()

    @Test fun `equity is written at most once per five minutes per venue and mode`() = runTest {
        val eq = roomStores(db).equity
        val t0 = Instant.parse("2026-10-28T12:00:00Z")
        for (s in 0L..600L step 5) eq.record(EquitySnapshot(t0.plusSeconds(s), "kalshi", Mode.PAPER, Sourced.kalshi(1.0, "x"), null, null))
        eq.record(EquitySnapshot(t0, "polymarket", Mode.PAPER, Sourced.polymarket(1.0, "x"), null, null))
        assertEquals(3, eq.history("kalshi", Mode.PAPER, Instant.EPOCH).size, "0, 300 and 600 s")
        assertEquals(1, eq.history("polymarket", Mode.PAPER, Instant.EPOCH).size)
    }

    @Test fun `routine pruning keeps warnings, errors and trades`() = runTest {
        val dao = db.events()
        listOf("DEBUG", "INFO", "WARN", "ERROR", "TRADE").forEach { dao.insert(EventEntity(0, 1_000, null, it, "k", "m", "{}")) }
        dao.insert(EventEntity(0, 9_000, null, "INFO", "k", "recent", "{}"))
        dao.pruneRoutine(5_000)
        assertEquals(setOf("WARN", "ERROR", "TRADE", "INFO"), dao.recent(10, null).map { it.level }.toSet())
        assertEquals(4, dao.recent(10, null).size)
    }

    @Test fun `demo and subaccount reach the engine credentials`() {
        val v = Vault(ctx.getSharedPreferences("vault-sys", Context.MODE_PRIVATE), object : Crypter {
            override fun encrypt(plain: ByteArray) = plain; override fun decrypt(blob: ByteArray) = blob
        })
        assertEquals(false, v.credentials().kalshiDemo); assertEquals(0, v.credentials().kalshiSubaccount)
        v.setKalshiDemo(true); v.setKalshiSubaccount(4)
        assertEquals(true, v.credentials().kalshiDemo); assertEquals(4, v.credentials().kalshiSubaccount)
        assertFailsWith<IllegalArgumentException> { v.setKalshiSubaccount(99) }
    }

    @Test fun `a heartbeat left behind is reported once as a gap`() {
        Health.clearBeat(ctx)
        assertNull(Health.gapOnStart(ctx, now = 1_000_000))
        Health.beat(ctx, at = 1_000_000)
        assertNull(Health.gapOnStart(ctx, now = 1_000_000 + 60_000), "a one-minute restart is not a gap")
        Health.beat(ctx, at = 1_000_000)
        val gap = Health.gapOnStart(ctx, now = 1_000_000 + 45 * 60_000)
        assertNotNull(gap); assertEquals(45, java.time.Duration.between(gap.first, gap.second).toMinutes())
        assertNull(Health.gapOnStart(ctx, now = 1_000_000 + 90 * 60_000), "reported once")
    }

    @Test fun `maker-specific battery advice exists for the phones that kill background apps`() {
        listOf("samsung", "Xiaomi", "OnePlus", "HUAWEI", "vivo").forEach { assertNotNull(Health.oemAdvice(it), it) }
        assertNull(Health.oemAdvice("Google"))
        assertTrue(Health.oemAdvice("samsung")!!.contains("Never sleeping"))
    }
}
