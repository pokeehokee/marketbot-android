package com.marketbot.app.ui

import androidx.compose.ui.res.stringResource
import com.marketbot.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.marketbot.core.strategy.Param
import com.marketbot.core.strategy.Strategy
import com.marketbot.core.strategy.StrategyConfig
import com.marketbot.core.strategy.problems
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Whether the strategy has unsaved parameter edits. */
fun MainViewModel.dirty(s: Strategy): Boolean = drafts[s.id]?.let { it != savedConfigs.value[s.id] } == true

@Composable
fun ParametersForm(vm: MainViewModel, s: Strategy, onLeave: () -> Unit) {
    val saved by vm.savedConfigs.collectAsState()
    LaunchedEffect(s.id) { vm.loadConfig(s) }
    val base = saved[s.id] ?: return Column(Modifier.padding(16.dp)) { repeat(6) { SkeletonLine() } }
    val values = vm.drafts[s.id] ?: base
    fun set(k: String, v: String) { vm.drafts[s.id] = values + (k to v) }
    val cfg = StrategyConfig(s.params, values)
    val problems = s.problems(cfg)
    val effects = if (problems.isEmpty()) runCatching { s.effects(cfg) }.getOrDefault(emptyList()) else emptyList()
    var saveErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var askLeave by remember { mutableStateOf(false) }
    val dirty = vm.dirty(s)
    BackHandler(dirty) { askLeave = true }

    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // A tap on empty space closes the keyboard; scrolling the list does too.
    LazyColumn(Modifier.imePadding().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(R.string.defaults_are_the_measured_values_from_th),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Section(stringResource(R.string.what_these_settings_mean)) {
                if (problems.isNotEmpty()) problems.forEach { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
                else if (effects.isEmpty()) Text(stringResource(R.string.no_money_limits_to_show_for_this_strateg), style = MaterialTheme.typography.bodySmall)
                else effects.forEach { Text(stringResource(R.string.s_2, (it).toString()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        items(s.params, key = { it.key }) { p ->
            val v = values[p.key] ?: p.default
            ParamRow(p, v, changed = v != p.default, problem = p.problem(v), onChange = { set(p.key, it) }, onReset = { set(p.key, p.default) })
        }
        item {
            saveErrors.forEach { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.saveDraft(s) { saveErrors = it } }, enabled = dirty && problems.isEmpty()) { Text(stringResource(R.string.save)) }
                OutlinedButton({ vm.drafts.remove(s.id); saveErrors = emptyList() }, enabled = dirty) { Text(stringResource(R.string.discard)) }
                TextButton({ vm.drafts[s.id] = s.params.associate { it.key to it.default } }) { Text(stringResource(R.string.all_defaults)) }
            }
        }
    }
    if (askLeave) AlertDialog(onDismissRequest = { askLeave = false },
        confirmButton = { TextButton({ vm.drafts.remove(s.id); askLeave = false; onLeave() }) { Text(stringResource(R.string.discard_changes)) } },
        dismissButton = { TextButton({ askLeave = false }) { Text(stringResource(R.string.keep_editing)) } },
        title = { Text(stringResource(R.string.unsaved_changes)) }, text = { Text(stringResource(R.string.leave_without_saving_the_changed_setting)) })
}

private fun fmt(v: Double, decimals: Int) = "%.${decimals}f".format(Locale.ROOT, v)

/** A round step for a range: about 1/100th of it, at the setting's precision. */
private fun step(min: Double, max: Double, decimals: Int): Double {
    val raw = (max - min) / 100
    val p = 10.0.pow(kotlin.math.floor(log10(raw)))
    val s = listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
    return maxOf(s, 10.0.pow(-decimals))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamRow(p: Param, v: String, changed: Boolean, problem: String?, onChange: (String) -> Unit, onReset: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (changed) { androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)); Spacer(Modifier.width(6.dp)) }
            Text(p.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (changed) IconButton(onReset) { Icon(Icons.Outlined.Restore, "Reset ${p.label} to ${p.default}") }
        }
        when (p) {
            is Param.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (v.toBooleanStrictOrNull() == true) "On" else "Off", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(v.toBooleanStrictOrNull() ?: p.defaultValue, { onChange(it.toString()) })
            }
            is Param.Num -> NumberControl(v.toDoubleOrNull(), p.min, p.max, step(p.min, p.max, p.decimals), p.decimals, p.unit, onChange)
            is Param.Int_ -> NumberControl(v.toDoubleOrNull(), p.min.toDouble(), p.max.toDouble(), maxOf(1.0, step(p.min.toDouble(), p.max.toDouble(), 0)), 0, p.unit, onChange)
            is Param.Text -> when (p.kind) {
                Param.TextKind.HOURS -> {
                    val on = v.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (0..23).forEach { h -> FilterChip(h in on, { onChange((if (h in on) on - h else on + h).sorted().joinToString(",")) }, { Text(stringResource(R.string.s_02d).format(h)) }) }
                    }
                }
                Param.TextKind.KINDS -> {
                    val on = v.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("HIGH" to "Daily highs", "LOW" to "Daily lows").forEach { (k, l) ->
                            FilterChip(k in on, { onChange((if (k in on) on - k else on + k).sorted().joinToString(",")) }, { Text(stringResource(R.string.pause, (l).toString())) }) }
                    }
                }
                Param.TextKind.ZONE -> {
                    val zones = listOf("" to "Phone's zone", "America/New_York" to "Eastern", "America/Chicago" to "Central",
                        "America/Denver" to "Mountain", "America/Los_Angeles" to "Pacific")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { zones.forEach { (z, l) -> FilterChip(v.trim() == z, { onChange(z) }, { Text(l) }) } }
                    if (zones.none { it.first == v.trim() }) OutlinedTextField(v, onChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.time_zone)) },
                        keyboardOptions = doneKeys, keyboardActions = closeKeyboard())
                }
                Param.TextKind.BANDS -> BandsControl(v, onChange)
                Param.TextKind.FREE -> OutlinedTextField(v, onChange, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = doneKeys, keyboardActions = closeKeyboard())
            }
        }
        problem?.let { Text(it, color = LocalDesk.current.loss, style = MaterialTheme.typography.bodySmall) }
        Text(p.help + "  Default: ${p.default.ifEmpty { "none" }}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A slider for the range plus - and + for exact steps, with the value and unit shown. */
@Composable
private fun NumberControl(value: Double?, min: Double, max: Double, step: Double, decimals: Int, unit: String, onChange: (String) -> Unit) {
    val v = (value ?: min).coerceIn(min, max)
    fun emit(x: Double) = onChange(fmt(((x / step).roundToInt() * step).coerceIn(min, max), decimals))
    val label = (if (unit == "$") "$" else "") + fmt(v, decimals) + (if (unit.isNotEmpty() && unit != "$") " $unit" else "")
    // Tap the value to type it exactly: a slider cannot hit 0.0725 by thumb.
    var typing by remember { mutableStateOf<String?>(null) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun commit() { typing?.replace(',', '.')?.toDoubleOrNull()?.let { onChange(fmt(it.coerceIn(min, max), decimals)) }; typing = null; focus.clearFocus() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ emit(v - step) }, enabled = v > min) { Icon(Icons.Outlined.Remove, "Decrease") }
        val t = typing
        if (t == null) Text(label, style = Figures, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Type an exact value") { typing = fmt(v, decimals) }.padding(vertical = 10.dp))
        else {
            val req = remember { androidx.compose.ui.focus.FocusRequester() }
            OutlinedTextField(t, { typing = it }, Modifier.weight(1f).focusRequester(req),
                singleLine = true, textStyle = Figures,
                suffix = { if (unit.isNotEmpty() && unit != "$") Text(unit) }, prefix = { if (unit == "$") Text("$") },
                supportingText = { Text("${fmt(min, decimals)} to ${fmt(max, decimals)}") },
                isError = t.replace(',', '.').toDoubleOrNull()?.let { it < min || it > max } ?: true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }))
            LaunchedEffect(Unit) { req.requestFocus() }
        }
        IconButton({ emit(v + step) }, enabled = v < max) { Icon(Icons.Outlined.Add, "Increase") }
    }
    // A slider only where its range is usable by thumb; huge ranges use the steppers alone.
    if ((max - min) / step <= 1000) Slider(v.toFloat(), { emit(it.toDouble()) }, valueRange = min.toFloat()..max.toFloat())
}

/** Ask bands as two-handle sliders, one per band. */
@Composable
private fun BandsControl(v: String, onChange: (String) -> Unit) {
    val bands = v.split(',', ';').mapNotNull { b -> b.split('-').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    fun emit(l: List<Pair<Double, Double>>) = onChange(l.joinToString(", ") { "${fmt(it.first, 2)}-${fmt(it.second, 2)}" })
    bands.forEachIndexed { i, (lo, hi) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.c_to_c, ((lo * 100).roundToInt()).toString(), ((hi * 100).roundToInt()).toString()), style = Figures, modifier = Modifier.weight(1f))
            IconButton({ emit(bands.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Outlined.Close, "Remove this band") }
        }
        RangeSlider(lo.toFloat()..hi.toFloat(), { r ->
            emit(bands.mapIndexed { j, b -> if (j == i) ((r.start * 100).roundToInt() / 100.0) to ((r.endInclusive * 100).roundToInt() / 100.0) else b })
        }, valueRange = 0f..0.5f, steps = 49)
    }
    TextButton({ emit(bands + (0.20 to 0.25)) }) { Text(stringResource(R.string.add_a_skipped_band)) }
    if (bands.isEmpty()) Text(stringResource(R.string.no_bands_skipped), style = MaterialTheme.typography.bodySmall)
}

private val doneKeys = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)

@Composable
private fun closeKeyboard(): androidx.compose.foundation.text.KeyboardActions {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    return androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() })
}
