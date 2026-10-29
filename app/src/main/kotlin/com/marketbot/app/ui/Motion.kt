package com.marketbot.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.marketbot.core.strategy.money
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * A money figure that rolls to its new value: the old one leaves upward on a gain
 * and downward on a loss, so the direction is felt before the number is read.
 * Still under reduced motion. [announce] makes a screen reader say changes.
 */
@Composable
fun MoneyTicker(v: Double, style: TextStyle, color: Color, modifier: Modifier = Modifier, announce: Boolean = false) {
    val cents = (v * 100).roundToLong()
    val still = reducedMotion(LocalContext.current)
    val m = if (announce) modifier.semantics { liveRegion = LiveRegionMode.Polite } else modifier
    if (still) { Text(money(v), style = style, color = color, modifier = m); return }
    AnimatedContent(cents, m, transitionSpec = {
        val up = targetState > initialState
        (slideInVertically(tween(220)) { h -> if (up) h else -h } + fadeIn(tween(220))) togetherWith
            (slideOutVertically(tween(220)) { h -> if (up) -h else h } + fadeOut(tween(160)))
    }, label = "money") { c -> Text(money(c / 100.0), style = style, color = color) }
}

/**
 * A button that acts only when held for [holdMs]: faster than tap-then-confirm in a
 * hurry, and still hard to set off by a stray touch. A light tick on press, a firm
 * one when it fires; released early, it springs back and says how to use it.
 * Screen readers get [a11yAction] (a double-tap opens the usual confirmation).
 */
@Composable
fun HoldToConfirm(text: String, color: Color, onColor: Color, enabled: Boolean, a11yLabel: String,
                  onConfirm: () -> Unit, a11yAction: () -> Unit, modifier: Modifier = Modifier, holdMs: Int = 600) {
    val haptic = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Box(Modifier.clip(Radius.pill).background(if (enabled) color else color.copy(alpha = 0.38f))
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.28f), size = Size(size.width * progress.value, size.height)) }
            .semantics(mergeDescendants = true) {
                role = Role.Button; contentDescription = a11yLabel
                onClick(a11yLabel) { if (enabled) a11yAction(); enabled }
                customActions = listOf(CustomAccessibilityAction(a11yLabel) { if (enabled) a11yAction(); enabled })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    hint = false
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val run = scope.launch {
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) { run.cancel(); hint = true; scope.launch { progress.animateTo(0f, spring()) } }
                })
            }
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(text, color = onColor, style = MaterialTheme.typography.labelLarge)
        }
        if (hint) Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.hold_to_confirm), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
