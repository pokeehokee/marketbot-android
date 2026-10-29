package com.marketbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.marketbot.app.R
import kotlinx.coroutines.launch

/**
 * Guards that work at the exchange or outside the phone: Kalshi's fill limit, the
 * order comparison with the exchanges, and the outside heartbeat.
 */
@Composable
fun SafetySection(vm: MainViewModel) {
    val busy by vm.inFlight.collectAsState()
    val runs by vm.orderReconRuns.collectAsState()
    val confirm = com.marketbot.app.rememberConfirm()
    val focus = LocalFocusManager.current

    Section(stringResource(R.string.fill_limit_title)) {
        Text(stringResource(R.string.fill_limit_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val current = loaded(busy.contains("fill_limit")) { vm.fillLimit() }
        var typed by remember(current) { mutableStateOf(current?.toString() ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
            OutlinedTextField(typed, { typed = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.contracts_per_15s)) }, textStyle = Figures,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            OutlinedButton({ typed.toIntOrNull()?.let { vm.setFillLimit(it) } }, enabled = typed.toIntOrNull() != null && typed.toIntOrNull() != current) {
                Text(stringResource(R.string.save)) }
        }
        val st = loaded(busy.size) { vm.fillLimitState() }
        st?.first?.forEach { (shard, at) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = Space.xs)) {
                Text(stringResource(R.string.fill_limit_tripped, shard, at.short()), style = MaterialTheme.typography.bodySmall,
                    color = LocalDesk.current.loss, modifier = Modifier.weight(1f))
                TextButton({ confirm("Reset Kalshi's fill limit on shard $shard") { vm.resetFillLimit(shard) } }, enabled = "fill_reset:$shard" !in busy) {
                    Text(stringResource(R.string.reset)) }
            }
        }
        st?.second?.let { Text(stringResource(R.string.fill_limit_unavailable, it), style = MaterialTheme.typography.labelSmall, color = LocalDesk.current.warn) }
    }

    Section(stringResource(R.string.order_check_title)) {
        Text(stringResource(R.string.order_check_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val last = loaded(runs) { vm.lastOrderRecon() }
        if (last == null) Text(stringResource(R.string.order_check_never), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.xs))
        else Column(Modifier.padding(top = Space.xs)) {
            Text(last.summary, style = MaterialTheme.typography.bodySmall, color = if (last.clean) MaterialTheme.colorScheme.onSurface else LocalDesk.current.warn)
            (last.unknown + last.corrected).take(6).forEach { Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            runCatching { java.time.Instant.parse(last.at) }.getOrNull()?.let {
                Text(it.short(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedButton({ vm.reconcileOrdersNow() }, enabled = "order_recon" !in busy && (vm.hasKalshi() || vm.hasPolymarket())) {
            Text(stringResource(if ("order_recon" in busy) R.string.running else R.string.check_now)) }
    }

    Section(stringResource(R.string.heartbeat_title)) {
        Text(stringResource(R.string.heartbeat_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var url by remember { mutableStateOf(vm.heartbeatUrl() ?: "") }
        OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth().padding(top = Space.s), singleLine = true,
            label = { Text(stringResource(R.string.heartbeat_url)) }, placeholder = { Text("https://hc-ping.com/…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.saveHeartbeatUrl(url) }))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button({ vm.saveHeartbeatUrl(url) }) { Text(stringResource(R.string.save)) }
            OutlinedButton({ vm.testHeartbeat() }, enabled = "heartbeat" !in busy) { Text(stringResource(R.string.send_test_ping)) }
        }
        com.marketbot.app.Heartbeat.lastResult?.let { Text(stringResource(R.string.last_ping, it), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Record the next stretch of API traffic (no keys or signatures: headers are never kept) and share it. */
@Composable
fun ApiRecording(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick) { kotlinx.coroutines.delay(5_000); tick++ }
    val j = com.marketbot.core.net.ApiJournal
    Column(Modifier.padding(top = Space.s)) {
        Text(if (j.recording) stringResource(R.string.recording_api, j.size(), j.until!!.short()) else stringResource(R.string.record_api_help),
            style = MaterialTheme.typography.bodySmall, color = if (j.recording) LocalDesk.current.warn else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (j.recording) OutlinedButton({ j.stop(); tick++ }) { Text(stringResource(R.string.stop_recording)) }
            else {
                OutlinedButton({ j.start(15); tick++ }) { Text(stringResource(R.string.record_15)) }
                OutlinedButton({ j.start(60); tick++ }) { Text(stringResource(R.string.record_60)) }
            }
            if (j.size() > 0) TextButton({
                scope.launch {
                    val uri = vm.apiRecordingUri()
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("application/json").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share API recording"))
                }
            }) { Text(stringResource(R.string.share)) }
        }
    }
}

/** The background benchmark: how much of each minute the phone let the engine run. */
@Composable
fun BackgroundBenchmark() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick) { kotlinx.coroutines.delay(30_000); tick++ }
    val r = remember(tick) { com.marketbot.app.Benchmark.result(ctx) }
    Column(Modifier.padding(top = Space.s)) {
        Text(stringResource(R.string.benchmark_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r != null && r.minutes > 0) {
            Text(stringResource(R.string.benchmark_result, "%.0f%%".format(r.share * 100), r.alive, r.minutes, r.longestGapMin) +
                if (r.running) " " + stringResource(R.string.benchmark_running, java.time.Instant.ofEpochMilli(r.endsAt).short()) else "",
                style = MaterialTheme.typography.bodyMedium, color = if (r.longestGapMin > 15) LocalDesk.current.warn else MaterialTheme.colorScheme.onSurface)
            r.gaps.takeLast(5).forEach { (at, len) -> Text("${java.time.Instant.ofEpochMilli(at).short()}: silent $len min", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (r?.running == true) OutlinedButton({ com.marketbot.app.Benchmark.stop(ctx); tick++ }) { Text(stringResource(R.string.stop_recording)) }
            else {
                OutlinedButton({ com.marketbot.app.Benchmark.start(ctx, 1); tick++ }) { Text(stringResource(R.string.benchmark_1h)) }
                OutlinedButton({ com.marketbot.app.Benchmark.start(ctx, 8); tick++ }) { Text(stringResource(R.string.benchmark_8h)) }
            }
        }
    }
}
