package com.marketbot.core

import com.marketbot.core.sync.GitHubMailbox
import com.marketbot.core.sync.SyncPayload
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Why the exports are gzipped.
 *
 * The daily sync had been failing with a timeout. The four CSV exports had grown
 * to 23 MB between them, and the GitHub contents API takes base64, so each sync
 * was pushing **31 MB** in four PUTs under a 60-second per-call budget -- the
 * largest single file 11.3 MB of base64. Measured on the real exports on
 * 2026-11-20: orders 8.49 MB compresses 7.5x, books 6.23 MB 10.6x, trades
 * 8.10 MB 3.9x, perps 1.04 MB 4.6x; together 23 MB -> 3.9 MB, i.e. 31 MB of
 * base64 down to 5.2 MB.
 *
 * It is also what stops the repository growing without bound: every sync rewrites
 * each file whole, so each one becomes a new blob for ever. At 120 commits the
 * .git directory was already 59 MB, gaining ~24 MB per sync.
 */
class SyncExportSizeTest {

    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)

    @Test fun `gzip round-trips exactly, including the awkward characters a CSV carries`() {
        for (text in listOf(
            "", "a", "strategy,mode,venue\nx,LIVE,kalshi\n",
            "quoted,\"field, with comma\",\"line\nbreak\"\n", "unicode: £ € — ±0.94\n",
        )) {
            assertEquals(text, gunzip(SyncPayload.gzip(text)), "round trip failed for: $text")
        }
    }

    @Test fun `a CSV-shaped export compresses several fold`() {
        // The real exports run 3.9x to 10.6x; a synthetic ledger is the same shape.
        val csv = buildString {
            append("strategy,mode,venue,ticker,action,price,contracts,placedAt\n")
            repeat(5_000) { i ->
                append("kalshi_btc_deep_itm,LIVE,kalshi,KXBTCD-26NOV2012-T${90000 + i % 40 * 100},buy_yes,0.9")
                append(i % 7).append(",5,2026-11-20T").append("%02d".format(i % 24)).append(":00:00Z\n")
            }
        }
        val gz = SyncPayload.gzip(csv)
        assertEquals(csv, gunzip(gz))
        assertTrue(gz.size * 3 < csv.length, "expected >3x: ${csv.length} -> ${gz.size}")
    }

    @Test fun `gzip is deterministic, so a quiet day writes no new commit`() {
        // GZIPOutputStream leaves the header's MTIME field zero. If it wrote the
        // clock, an unchanged export would differ on every sync and the contents
        // API would record a commit a day for nothing.
        val text = "strategy,mode\nkalshi_btc_deep_itm,LIVE\n"
        assertTrue(SyncPayload.gzip(text).contentEquals(SyncPayload.gzip(text)),
            "gzip output differs between calls on identical input")
        Thread.sleep(1100)   // cross a whole second: an mtime in the header would show here
        assertTrue(SyncPayload.gzip(text).contentEquals(SyncPayload.gzip(text)),
            "gzip output depends on the clock")
    }

    @Test fun `every CSV export is gzipped and no JSON file is`() {
        for (p in listOf(GitHubMailbox.ORDERS, GitHubMailbox.BOOKS, GitHubMailbox.TRADES,
                         GitHubMailbox.PERPS, GitHubMailbox.PERP_FUNDING)) {
            assertTrue(p.endsWith(".csv.gz"), "$p should be a gzipped csv")
        }
        // A person reads and edits these in the GitHub UI, which a .gz would end.
        for (p in listOf(GitHubMailbox.SNAPSHOT, GitHubMailbox.PENDING,
                         GitHubMailbox.RESULTS, GitHubMailbox.BET_RESULTS)) {
            assertTrue(p.endsWith(".json"), "$p must stay readable in the browser")
        }
    }

    @Test fun `the retired list is exactly the uncompressed name of each gzipped export`() {
        val expected = listOf(GitHubMailbox.ORDERS, GitHubMailbox.BOOKS, GitHubMailbox.TRADES,
                              GitHubMailbox.PERPS, GitHubMailbox.PERP_FUNDING)
            .map { it.removeSuffix(".gz") }.sorted()
        assertEquals(expected, GitHubMailbox.RETIRED.sorted(),
            "a renamed export must take its old path onto RETIRED, or the repo keeps both copies")
        assertTrue(GitHubMailbox.RETIRED.none { it.endsWith(".gz") })
    }
}
