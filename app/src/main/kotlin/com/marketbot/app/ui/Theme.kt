package com.marketbot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.marketbot.core.provenance.Source

/**
 * A trading-desk palette: a dark slate ground, one green for money and
 * "on", amber for calculated values and warnings, red for losses and the kill
 * switch, violet for paper. Source chips reuse these so a value's origin is
 * readable at a glance.
 */
@Immutable
data class Desk(
    val api: Color, val derived: Color, val paper: Color, val setting: Color,
    val gain: Color, val loss: Color, val warn: Color, val live: Color, val muted: Color,
)

private val DarkDesk = Desk(
    api = Color(0xFF3CC08A), derived = Color(0xFFE2A93B), paper = Color(0xFFA493FF), setting = Color(0xFF8A9BA3),
    gain = Color(0xFF3CC08A), loss = Color(0xFFF0645A), warn = Color(0xFFE2A93B), live = Color(0xFFF0645A), muted = Color(0xFF8A9BA3),
)
private val LightDesk = Desk(
    api = Color(0xFF14824F), derived = Color(0xFF9A6200), paper = Color(0xFF5B45D6), setting = Color(0xFF5A6A72),
    gain = Color(0xFF14824F), loss = Color(0xFFC23A30), warn = Color(0xFF9A6200), live = Color(0xFFC23A30), muted = Color(0xFF5A6A72),
)

val LocalDesk = staticCompositionLocalOf { DarkDesk }

private val dark = darkColorScheme(
    primary = Color(0xFF3CC08A), onPrimary = Color(0xFF00210F), primaryContainer = Color(0xFF123526), onPrimaryContainer = Color(0xFFB7F2D2),
    secondary = Color(0xFF9FB4BD), background = Color(0xFF0E1214), onBackground = Color(0xFFE3EAED),
    surface = Color(0xFF0E1214), onSurface = Color(0xFFE3EAED), surfaceVariant = Color(0xFF1B2327), onSurfaceVariant = Color(0xFFA9B7BD),
    surfaceContainer = Color(0xFF161C1F), surfaceContainerHigh = Color(0xFF1C2427), surfaceContainerHighest = Color(0xFF232C30),
    surfaceContainerLow = Color(0xFF12171A), outline = Color(0xFF3A474D), outlineVariant = Color(0xFF263034),
    error = Color(0xFFF0645A), onError = Color(0xFF2D0503), errorContainer = Color(0xFF4A1612), onErrorContainer = Color(0xFFFFDAD5),
)
private val light = lightColorScheme(
    primary = Color(0xFF14824F), onPrimary = Color.White, primaryContainer = Color(0xFFCDEFDD), onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4F636B), background = Color(0xFFF5F7F7), onBackground = Color(0xFF151C1F),
    surface = Color(0xFFF5F7F7), onSurface = Color(0xFF151C1F), surfaceVariant = Color(0xFFE2E8EA), onSurfaceVariant = Color(0xFF465459),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEFF3F4), surfaceContainerHighest = Color(0xFFE6ECEE),
    surfaceContainerLow = Color(0xFFFAFBFB), outline = Color(0xFF9AA8AE), outlineVariant = Color(0xFFD6DEE1),
    error = Color(0xFFC23A30), errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF410002),
)

/** Numbers in the body font with tabular figures (and a slashed zero), so columns of money line up and 0 never reads as O. */
val Figures = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.sp,
    fontFeatureSettings = "tnum, zero")

/** Tickers, order ids and other strings a person may copy: monospace so similar characters stay apart. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)

/**
 * The spacing scale. Every gap on screen is one of these, so screens line up with
 * each other and a density change is one edit.
 */
object Space {
    val xxs = 2.dp; val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
}

/** Corner radii: small for tags, larger the more a surface is a container. */
object Radius {
    val chip = RoundedCornerShape(4.dp)
    val banner = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * Surfaces by how close they are to money. Tonal only (no shadows, which read as
 * mud on a dark ground); a LIVE surface adds a thin loss-tinted border instead.
 */
object Elevation {
    @Composable fun section() = MaterialTheme.colorScheme.surfaceContainer
    @Composable fun raised() = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable fun sunken() = MaterialTheme.colorScheme.surfaceContainerLow
}

private val type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
    )
}

/** Appearance choices, kept in preferences and observed by the theme. */
object Appearance {
    enum class Mode { SYSTEM, LIGHT, DARK }
    var mode by androidx.compose.runtime.mutableStateOf(Mode.SYSTEM)
    var dynamic by androidx.compose.runtime.mutableStateOf(false)

    fun load(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        mode = runCatching { Mode.valueOf(p.getString("theme_mode", "SYSTEM")!!) }.getOrDefault(Mode.SYSTEM)
        dynamic = p.getBoolean("dynamic_color", false)
    }

    fun save(ctx: android.content.Context) = ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit()
        .putString("theme_mode", mode.name).putBoolean("dynamic_color", dynamic).apply()
}

/**
 * Dark, light or the system's choice; Material You wallpaper colours when asked
 * for (Android 12+). The source-tag and money colours never follow the
 * wallpaper: a KALSHI tag is the same green on every phone, so it reads the same.
 */
@Composable
fun MarketbotTheme(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { Appearance.load(ctx) }
    val darkMode = when (Appearance.mode) { Appearance.Mode.DARK -> true; Appearance.Mode.LIGHT -> false; else -> isSystemInDarkTheme() }
    val scheme = if (Appearance.dynamic && android.os.Build.VERSION.SDK_INT >= 31)
        (if (darkMode) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx))
    else if (darkMode) dark else light
    // One clock for every staleness check, ticking twice a minute.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(java.time.Instant.now()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = java.time.Instant.now() } }
    androidx.compose.runtime.CompositionLocalProvider(LocalDesk provides if (darkMode) DarkDesk else LightDesk, LocalNow provides now) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** True when the user turned animations off in Android's accessibility settings. */
fun reducedMotion(ctx: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun sourceColor(s: Source): Color {
    val d = LocalDesk.current
    return when (s) {
        Source.KALSHI_API, Source.POLYMARKET_API, Source.VENUE_API -> d.api
        Source.DERIVED -> d.derived
        Source.PAPER_MODEL -> d.paper
        Source.CONFIG -> d.setting
    }
}
