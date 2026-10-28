package com.marketbot.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real activity: the onboarding notice, Home, the strategy list and
 * a strategy's go-live screen. Screenshots go to app/build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** Draws the activity's window into a bitmap (Robolectric native graphics). */
    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `onboarding, home, strategies and go-live render`() {
        rule.onNodeWithText("Welcome to Marketbot").assertExists()
        shot("0_setup_welcome")
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("I understand this can lose money").performClick()
        rule.onNodeWithText("Next").performClick()
        shot("0_setup_keep_running")
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Not yet: I'll look around first").performClick()
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Kill switch").assertExists()
        shot("1_home")
        rule.onNodeWithText("Strategies", useUnmergedTree = true).let { rule.onNodeWithText("Strategies").performClick() }
        rule.onNodeWithText("Weather longshot").assertExists()
        shot("2_strategies")
        rule.onNodeWithText("Weather longshot").performClick()
        rule.onNodeWithText("Evidence".uppercase()).assertExists()
        shot("3_strategy_overview")
        rule.onNodeWithText("Go live").performClick()
        rule.waitUntil(5000) { runCatching { rule.onNodeWithText("Kalshi API key saved").assertExists(); true }.getOrDefault(false) }
        shot("4_go_live")
        rule.onNodeWithText("Parameters").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("What these settings mean".uppercase()).fetchSemanticsNodes().isNotEmpty() }
        shot("5_strategy_parameters")
    }

    @Test fun `home with a record, and a strategy's orders`() {
        val app = ApplicationProvider.getApplicationContext<MarketbotApp>()
        ctxPrefs()
        kotlinx.coroutines.runBlocking {
            val l = app.engine.stores.ledger
            val t0 = java.time.Instant.now().minusSeconds(20 * 86_400L)
            val r = kotlin.random.Random(7)
            for (i in 0 until 40) {
                val placed = t0.plusSeconds(i * 43_200L)
                val loss = r.nextInt(12) == 0
                l.insert(com.marketbot.core.store.QuoteRow(strategyId = "kalshi_weather_longshot", mode = com.marketbot.core.strategy.Mode.PAPER,
                    venue = "kalshi", ticker = "KXHIGHNY-26OCT${10 + i / 2}-B7${i % 10}.5", action = "sell_yes", price = 0.07, contracts = 5.0,
                    placedAt = placed, closeTime = placed.plusSeconds(30 * 3600), filledContracts = 5.0, resolvedAt = placed.plusSeconds(36 * 3600),
                    result = if (loss) "yes" else "no", pnl = if (loss) -4.65 else 0.35, pnlSource = com.marketbot.core.provenance.Source.PAPER_MODEL, hypothetical = false))
            }
            l.insert(com.marketbot.core.store.QuoteRow(strategyId = "kalshi_weather_longshot", mode = com.marketbot.core.strategy.Mode.PAPER, venue = "kalshi",
                ticker = "KXHIGHTDAL-26NOV01-B90.5", action = "sell_yes", price = 0.18, contracts = 5.0, placedAt = java.time.Instant.now(),
                closeTime = java.time.Instant.now().plusSeconds(30 * 3600)))
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Paper P&L (40)").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Paper P&L (40)").performClick()
        shot("6_home_record")
        rule.onNodeWithText("Strategies").performClick()
        rule.onNodeWithText("Weather longshot").performClick()
        rule.onAllNodesWithText("Paper")[1].performScrollTo().performClick()   // the record's filter chip, not the mode control
        shot("7_strategy_record")
        rule.onNodeWithText("Orders").performClick()
        // Room delivers on its own thread, which Compose's idling does not track.
        rule.waitUntil(10_000) { rule.onAllNodesWithText("DALLAS HIGH", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("8_orders")
    }

    private fun ctxPrefs() {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("setup_v2", true).apply()
        rule.activityRule.scenario.recreate()
    }

    @Test fun `launcher icon renders`() {
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        for (id in listOf(R.drawable.ic_launcher_bg, R.drawable.ic_launcher_fg)) c.getDrawable(id)!!.apply { setBounds(0, 0, 432, 432); draw(cv) }
        File(File("build/screens").apply { mkdirs() }, "icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("unused") private val c: Context get() = ApplicationProvider.getApplicationContext()
}
