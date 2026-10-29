package com.marketbot.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun shot(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>, name: String) {
    rule.waitForIdle()
    val v = rule.activity.window.decorView
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
    File(File("build/screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun skipSetup() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("ui", Context.MODE_PRIVATE)
    .edit().putBoolean("setup_v2", true).commit()

/** A tablet in landscape: navigation rail, strategy list beside its detail. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-night-xhdpi", fontScale = 1.0f)
class TabletLayoutTest {
    init { skipSetup() }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun `wide screens get a rail and two panes`() {
        rule.onAllNodesWithText("Strategies")[0].performClick()
        rule.onAllNodesWithText("Weather longshot").fetchSemanticsNodes().let { check(it.size >= 2) { "list and detail both show it" } }
        rule.onNodeWithText("Parameters").assertExists()
        shot(rule, "9_tablet_strategies")
    }
}

/** The largest standard font size: nothing may clip or overlap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi", fontScale = 2.0f)
class LargeFontTest {
    init { skipSetup() }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun `home and a strategy at twice the font size`() {
        shot(rule, "10_large_font_home")
        rule.onNodeWithContentDescription("Strategies").performClick()   // labels give way to icons at this size
        rule.onNodeWithText("Weather longshot").performClick()
        shot(rule, "11_large_font_strategy")
    }
}

/** A Galaxy Z Flip's cover screen: the glance layout, nothing more. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h370dp-night-xhdpi", fontScale = 1.0f)
class CoverScreenTest {
    init { skipSetup() }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun `the cover screen shows the engine, today and the kill switch only`() {
        rule.onNodeWithText("Live today").assertExists()
        rule.onNodeWithContentDescription("Kill switch: cancel every order and stop live trading").assertExists()
        check(rule.onAllNodesWithText("Strategies").fetchSemanticsNodes().isEmpty()) { "no navigation on the cover screen" }
        shot(rule, "12_cover_screen")
    }
}
