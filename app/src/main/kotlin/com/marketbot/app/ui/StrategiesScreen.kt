package com.marketbot.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marketbot.core.strategy.Mode

@Composable
fun StrategiesScreen(vm: MainViewModel, onGoLive: (String) -> Unit = {}, onOpen: (String) -> Unit) {
    val status by vm.status.collectAsState()
    val running by vm.engineRunning.collectAsState()
    vm.modes.collectAsState().value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Strategies", style = MaterialTheme.typography.headlineSmall)
            Text("Each starts in paper. Live needs its checklist, a preflight and your confirmation.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.strategies, key = { it.id }) { s ->
            val st = status[s.id]
            val mode = st?.mode ?: vm.modeOf(s.id)
            Surface(Modifier.fillMaxWidth().clickable { onOpen(s.id) }, shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(s.venue.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ModePill(mode, st?.armed == true, running)
                    }
                    Text(s.summary, style = MaterialTheme.typography.bodyMedium)
                    EvidenceBadge(s.evidence.level)
                    Text(s.evidence.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    ModeControl(vm, s, mode) { onGoLive(s.id) }
                }
            }
        }
    }
}
