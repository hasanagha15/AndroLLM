package io.androllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.accessibility.controller.SensitiveAppStore
import io.androllm.core.memory.personal.PersonalContextSettings
import io.androllm.core.memory.personal.PersonalContextStore
import io.androllm.core.memory.personal.ReplyLanguage
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** F1: "Personal context" — user-typed facts injected into the system prompt, stored on device. */
@HiltViewModel
class PersonalContextViewModel @Inject constructor(
    private val store: PersonalContextStore,
    private val sensitiveApps: SensitiveAppStore
) : ViewModel() {
    /** Extra banking/payment packages the assistant must never tap/type in or read (on device). */
    val paymentApps: StateFlow<String?> = sensitiveApps.extras
        .map { it.joinToString(", ") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun savePaymentApps(raw: String) {
        viewModelScope.launch { sensitiveApps.save(raw) }
    }

    val settings: StateFlow<PersonalContextSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun saveText(text: String) {
        viewModelScope.launch {
            store.setText(text)
            _saved.value = true
        }
    }

    fun setReplyLanguage(lang: ReplyLanguage) {
        viewModelScope.launch { store.setReplyLanguage(lang) }
    }

    fun clearSaved() {
        _saved.value = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalContextCard(viewModel: PersonalContextViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val storedPaymentApps by viewModel.paymentApps.collectAsStateWithLifecycle()
    var paymentApps by remember { mutableStateOf("") }
    var paymentAppsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(storedPaymentApps) {
        val s = storedPaymentApps ?: return@LaunchedEffect
        // First load fills the field; after "Save apps" it shows the normalized list.
        paymentApps = s
        paymentAppsLoaded = true
    }
    LaunchedEffect(settings) {
        val s = settings
        if (s != null && !loaded) {
            text = s.text
            loaded = true
        }
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Personal context",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskPaper)
            )
            Text(
                text = "Facts the assistant should always know (country, currency, banks, utilities, " +
                    "preferences). Stored only on this device and added to every chat prompt — " +
                    "in cloud mode it is sent to your provider with the prompt.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(PersonalContextSettings.MAX_TEXT_CHARS)
                    viewModel.clearSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("e.g. I live in … Currency: … My bank app: …") },
                supportingText = { Text("${text.length} / ${PersonalContextSettings.MAX_TEXT_CHARS}") }
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { viewModel.saveText(text) }) { Text("Save") }
                if (saved) {
                    Text(
                        text = "Saved",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
            }
            Text(
                text = "Reply language",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = settings?.replyLanguage ?: ReplyLanguage.AUTO
                ReplyLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = lang == current,
                        onClick = { viewModel.setReplyLanguage(lang) },
                        label = { Text(lang.label) }
                    )
                }
            }
            Text(
                text = "Protected banking / payment apps",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper)
            )
            Text(
                text = "The assistant may open these apps but never tap, type or read the screen inside " +
                    "them. ${SensitiveAppGuard.PAYMENT_PACKAGES.size} banking/wallet apps are built in; add your " +
                    "bank's package name if it is missing (comma-separated).",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
            OutlinedTextField(
                value = paymentApps,
                onValueChange = { paymentApps = it.take(1000) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                placeholder = { Text("com.example.bank, com.example.wallet") }
            )
            Button(
                onClick = { viewModel.savePaymentApps(paymentApps) },
                enabled = paymentAppsLoaded
            ) { Text("Save apps") }
        }
    }
}
