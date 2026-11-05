package com.marketbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Settings > Claude sync: a private GitHub repository the app sends its snapshot and
 * order export to, and reads Claude's proposed changes from. Nothing applies until
 * approved here: a tap for a paper setting, the screen lock for turning a strategy
 * on or off. The app refuses anything live or riskier whatever the file says.
 */
@Composable
fun ClaudeSyncSection(vm: MainViewModel) {
    val tick by vm.syncTick.collectAsState()
    val pending = loaded(tick) { vm.syncPending() }
    val status = loaded(tick) { vm.syncStatus() }
    val confirm = com.marketbot.app.rememberConfirm()
    var repo by remember { mutableStateOf(vm.syncRepo().orEmpty()) }
    var token by remember { mutableStateOf("") }
    val names = vm.strategies.associate { it.id to it.name }
    Section("Claude sync") {
        Text("Sends a daily snapshot (settings, modes, records) and the order export to a private GitHub repository, and shows " +
            "changes Claude proposes there. Nothing changes until you approve it. Proposals can never turn on live trading, raise a " +
            "limit or touch keys.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("Repository (owner/name)") }, singleLine = true)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(if (vm.hasSyncToken()) "Token (saved; paste to replace)" else "GitHub token for that repository only") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton({ vm.saveSync(repo, token); token = "" }) { Text("Save") }
            Button({ vm.syncNow() }, enabled = vm.syncRepo() != null && vm.hasSyncToken()) { Text("Sync now") }
        }
        status?.let { Text(it.detail + " (" + it.at.take(16).replace('T', ' ') + " UTC)", style = MaterialTheme.typography.labelSmall,
            color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else LocalDesk.current.loss) }
        val list = pending.orEmpty()
        if (list.isNotEmpty()) Text("Proposed changes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        list.forEach { p ->
            val pr = p.proposal
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(names[pr.strategy] ?: pr.strategy, style = MaterialTheme.typography.bodyMedium)
                val what = pr.set.entries.joinToString { "${it.key} → ${it.value}" } + (pr.mode?.let { (if (pr.set.isEmpty()) "" else "; ") + "mode → $it" } ?: "")
                Text(what, style = Figures)
                if (pr.reason.isNotBlank()) Text(pr.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.refused != null) Text("The app will not apply this: ${p.refused}", style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.loss)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.refused == null) Button({
                        if (p.strongConfirm) confirm("Apply Claude's change") { vm.approveProposal(pr.id) } else vm.approveProposal(pr.id)
                    }) { Text(if (p.strongConfirm) "Approve (screen lock)" else "Approve") }
                    TextButton({ vm.rejectProposal(pr.id) }) { Text(if (p.refused == null) "Reject" else "Dismiss") }
                }
            }
        }
    }
}
