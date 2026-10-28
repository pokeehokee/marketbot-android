package com.marketbot.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What went wrong and what to do about it, for a person. The exchange's own
 * message stays one tap away under "Details".
 */
fun plainError(raw: String?): String {
    val m = raw ?: return "Something went wrong."
    val l = m.lowercase()
    val venue = when { "polymarket" in l || "/v1/" in l -> "Polymarket"; "kalshi" in l || "/trade-api/" in l || "/portfolio/" in l -> "Kalshi"; else -> "The exchange" }
    return when {
        "clock is off" in l -> m.substringAfter("(").substringBeforeLast(")").replaceFirstChar { it.uppercase() } + "."
        l.startsWith("401") || l.startsWith("403") || "unauthorized" in l || "authrejected" in l ->
            if (venue == "Kalshi") "Kalshi rejected the key. Check the key id matches the .pem file, and that a demo key is used with demo and a real key with the real exchange."
            else "$venue rejected the key. Check the key id and secret under Accounts."
        l.startsWith("429") || "rate" in l && "limit" in l -> "$venue asked us to slow down. The app waits and retries by itself."
        "unknownhost" in l || "unable to resolve" in l || "failed to connect" in l || "connectexception" in l ->
            "No connection to $venue. Check Wi-Fi or mobile data; the app retries when it is back."
        "timeout" in l || "timed out" in l -> "$venue did not answer in time. The app retries."
        Regex("^5\\d\\d ").containsMatchIn(l) -> "$venue had a server error. The app retries."
        l.startsWith("404") -> "$venue doesn't know that market or order any more; it may have closed."
        "key not configured" in l -> "No $venue key saved. Add one under Accounts, or use paper mode."
        else -> m
    }
}

/** An error with its fix in words, the raw message behind Details, and a Retry. */
@Composable
fun ErrorCard(raw: String, onRetry: (() -> Unit)? = null) {
    var details by remember { mutableStateOf(false) }
    Surface(color = LocalDesk.current.loss.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = LocalDesk.current.loss)
                Spacer(Modifier.width(10.dp))
                Text(plainError(raw), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            if (details) Text(raw, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row {
                if (plainError(raw) != raw) TextButton({ details = !details }) { Text(if (details) "Hide details" else "Details") }
                onRetry?.let { TextButton(it) { Text("Retry") } }
            }
        }
    }
}
