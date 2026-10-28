package com.marketbot.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        rule.onNodeWithText("Before you start").assertExists()
        rule.onNodeWithText("I understand").performClick()
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
        shot("5_strategy_settings")
    }

    @Suppress("unused") private val c: Context get() = ApplicationProvider.getApplicationContext()
}
