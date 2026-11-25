package io.androllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.AtlasStatus
import io.androllm.core.appatlas.playbook.PlaybookFileImporter
import io.androllm.core.appatlas.settings.AppAtlasSettings
import io.androllm.core.appatlas.settings.AppAtlasSettingsStore
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Branch L: Settings card for the App Atlas — status, re-index, learned-path
 * recording and the exclusion list. Everything shown here stays on the device.
 */
@HiltViewModel
class AppAtlasSettingsViewModel @Inject constructor(
    private val repository: AppAtlasRepository,
    private val store: AppAtlasSettingsStore,
    private val playbookImporter: PlaybookFileImporter
) : ViewModel() {
    val status: StateFlow<AtlasStatus> = repository.status
    val settings: StateFlow<AppAtlasSettings> = store.settings

    init {
        store.current()
        viewModelScope.launch { repository.entries() } // fills the status counters
    }

    fun reindex() = repository.requestSync(AppAtlasRepository.Mode.FULL)
    fun setEnabled(v: Boolean) {
        store.setEnabled(v)
        if (v) repository.requestSync(AppAtlasRepository.Mode.INCREMENTAL)
    }
    fun setRecordPaths(v: Boolean) = store.setRecordPaths(v)
    fun clearPaths() { viewModelScope.launch { repository.clearPaths() } }

    /** Branch N: imports a playbook bundle (JSON) chosen by the owner; [onDone] gets the report line. */
    fun importPlaybooks(uri: android.net.Uri, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone("Playbooks: " + playbookImporter.import(uri).summary()) }
    }

    /** Saves the exclusion list and drops excluded apps from the atlas. Returns the number saved. */
    fun saveExclusions(raw: String): Int {
        val saved = store.setExclusions(raw)
        // Gate B2: rebuild everything after a list change (cheap enough, done in the background).
        repository.requestSync(AppAtlasRepository.Mode.FULL)
        return saved.size
    }
}

@Composable
fun AppAtlasSettingsCard(viewModel: AppAtlasSettingsViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var exclusions by remember(settings.exclusions) { mutableStateOf(settings.exclusions.joinToString(", ")) }
    var note by remember { mutableStateOf<String?>(null) }
    val pickBundle = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importPlaybooks(uri) { note = it } }

    CloudGlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App Atlas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
            Text(
                "An on-device map of how to open screens of your apps directly (deep links, shortcuts, in-app search, " +
                    "UI ids), refreshed when apps install or update. Banking/payment, Tesla and WhatsApp are indexed as " +
                    "metadata only — the assistant may only open them. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            val last = if (status.lastSyncAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.lastSyncAt)) else "never"
            Text(
                "${status.appsIndexed} apps indexed (${status.sensitiveApps} protected) · ${status.learnedPaths} learned paths · " +
                    "updated $last" + if (status.running) " · indexing…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            status.lastReport?.let { Text("Last run: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.ledger.deskInk) }

            ToggleRow("Use the App Atlas", settings.enabled) { viewModel.setEnabled(it) }
            ToggleRow("Record learned paths (view ids / app labels only)", settings.recordPaths) { viewModel.setRecordPaths(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.reindex(); note = "Re-indexing in the background…" }, enabled = settings.enabled && !status.running) {
                    Text("Re-index now")
                }
                TextButton(onClick = { viewModel.clearPaths(); note = "Learned paths cleared." }) {
                    Text("Clear learned paths")
                }
            }
            // Branch N: playbook bundle from a file (Jarvis can also push them over the link).
            TextButton(onClick = { pickBundle.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("Import playbooks (JSON)")
            }

            OutlinedTextField(
                value = exclusions,
                onValueChange = { exclusions = it.take(4_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Excluded packages (comma separated)") },
                minLines = 2
            )
            TextButton(onClick = { note = "Excluded ${viewModel.saveExclusions(exclusions)} package(s)." }) {
                Text("Save exclusions")
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.ledger.deskInk) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
