package com.marketbot.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.marketbot.core.provenance.Source
import com.marketbot.core.store.QuoteRow
import com.marketbot.core.strategy.Mode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Home and a strategy with a recorded history: seeded before the app opens, as after a few weeks of paper trading. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi", fontScale = 1.0f)
class RecordScreensTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun shot(sc: ActivityScenario<MainActivity>, name: String) {
        rule.waitForIdle()
        sc.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(File("build/screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) =
        runCatching { rule.waitUntil(30_000, cond) }.getOrElse { throw AssertionError("Timed out waiting for $what", it) }

    @Test fun `home with a record, and a strategy's orders`() {
        val app = ApplicationProvider.getApplicationContext<MarketbotApp>()
        app.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("setup_v2", true).commit()
        kotlinx.coroutines.runBlocking {
            val l = app.engine.stores.ledger
            val t0 = Instant.now().minusSeconds(20 * 86_400L)
            val r = kotlin.random.Random(7)
            for (i in 0 until 40) {
                val placed = t0.plusSeconds(i * 43_200L)
                val loss = r.nextInt(12) == 0
                l.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.PAPER, venue = "kalshi",
                    ticker = "KXHIGHNY-26OCT${10 + i / 2}-B7${i % 10}.5", action = "sell_yes", price = 0.07, contracts = 5.0,
                    placedAt = placed, closeTime = placed.plusSeconds(30 * 3600), filledContracts = 5.0, resolvedAt = placed.plusSeconds(36 * 3600),
                    result = if (loss) "yes" else "no", pnl = if (loss) -4.65 else 0.35, pnlSource = Source.PAPER_MODEL, hypothetical = false))
            }
            l.insert(QuoteRow(strategyId = "kalshi_weather_longshot", mode = Mode.PAPER, venue = "kalshi",
                ticker = "KXHIGHTDAL-26NOV01-B90.5", action = "sell_yes", price = 0.18, contracts = 5.0, placedAt = Instant.now(),
                closeTime = Instant.now().plusSeconds(30 * 3600)))
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitFor("the portfolio to count 40 paper positions") { rule.onAllNodesWithText("Paper P&L (40)").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Paper P&L (40)").performClick()
            shot(sc, "6_home_record")
            rule.onNodeWithText("Strategies").performClick()
            rule.onNodeWithText("Weather longshot").performClick()
            rule.onAllNodesWithText("Paper")[1].performScrollTo().performClick()   // the record's filter chip, not the mode control
            shot(sc, "7_strategy_record")
            rule.onNodeWithText("Orders").performClick()
            waitFor("the orders list") { rule.onAllNodesWithText("Dallas high", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            shot(sc, "8_orders")
        }
    }
}
