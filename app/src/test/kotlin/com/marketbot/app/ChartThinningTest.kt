package com.marketbot.app

import com.marketbot.app.ui.thin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartThinningTest {
    @Test fun `a long series is thinned to a drawable size but keeps its ends and extremes`() {
        val pts = (0 until 20_000).map { it.toLong() to if (it == 12_345) -50.0 else if (it == 777) 90.0 else (it % 7).toDouble() }
        val t = thin(pts, 400)
        assertTrue("got ${t.size}", t.size <= 401)
        assertEquals(pts.first(), t.first()); assertEquals(pts.last(), t.last())
        assertTrue(t.any { it.second == -50.0 }); assertTrue(t.any { it.second == 90.0 })
        assertEquals(t.sortedBy { it.first }, t)
    }

    @Test fun `a short series is untouched`() {
        val pts = (0 until 50).map { it.toLong() to it.toDouble() }
        assertEquals(pts, thin(pts, 400))
    }
}
