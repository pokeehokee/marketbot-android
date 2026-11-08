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
        val day = rows.first().at.atZone(ZoneOffset.UTC).toLocalDate()
        val f = File(dir, "books-$day.csv")
        f.appendText(rows.joinToString("\n", postfix = "\n") { it.csv() })
        dir.listFiles()?.filter { it.name < "books-${day.minusDays(14)}.csv" }?.forEach { it.delete() }
    }

    /** The last [days] days in one CSV with its header, or null when nothing was recorded. */
    @Synchronized fun latest(days: Long = 7): String? {
        val today = LocalDate.now(ZoneOffset.UTC)
        val files = (0..days).map { File(dir, "books-${today.minusDays(it)}.csv") }.filter { it.exists() }.reversed()
        if (files.isEmpty()) return null
        return CrossVenueBooks.HEADER + "\n" + files.joinToString("") { it.readText() }
    }
}
