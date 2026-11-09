package com.marketbot.app.data

import android.content.Context
import com.marketbot.core.research.CrossVenueBooks
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** The cross-venue research rows, one CSV file per UTC day, 14 days kept. Public market data only. */
class ResearchFiles(context: Context) {
    private val dir = File(context.filesDir, "research").apply { mkdirs() }

    @Synchronized fun append(rows: List<CrossVenueBooks.Row>) {
        if (rows.isEmpty()) return
        write("books", rows.first().at.atZone(ZoneOffset.UTC).toLocalDate(), rows.joinToString("\n", postfix = "\n") { it.csv() })
    }

    @Synchronized fun appendTrades(rows: List<CrossVenueBooks.Trade>) {
        if (rows.isEmpty()) return
        write("trades", rows.first().at.atZone(ZoneOffset.UTC).toLocalDate(), rows.joinToString("\n", postfix = "\n") { it.csv() })
    }

    private fun write(prefix: String, day: LocalDate, text: String) {
        File(dir, "$prefix-$day.csv").appendText(text)
        dir.listFiles()?.filter { it.name.startsWith("$prefix-") && it.name < "$prefix-${day.minusDays(14)}.csv" }?.forEach { it.delete() }
    }

    /** The last [days] days in one CSV with its header, or null when nothing was recorded. */
    @Synchronized fun latest(days: Long = 1): String? = latest("books", CrossVenueBooks.HEADER, days)
    @Synchronized fun latestTrades(days: Long = 3): String? = latest("trades", CrossVenueBooks.TRADES_HEADER, days)

    private fun latest(prefix: String, header: String, days: Long): String? {
        val today = LocalDate.now(ZoneOffset.UTC)
        val files = (0..days).map { File(dir, "$prefix-${today.minusDays(it)}.csv") }.filter { it.exists() }.reversed()
        if (files.isEmpty()) return null
        return header + "\n" + files.joinToString("") { it.readText() }
    }
}
