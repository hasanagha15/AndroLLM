package io.androllm.feature.settings

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.lang.VoiceLanguage
import io.androllm.core.voice.lang.VoiceLanguagePolicy
import io.androllm.core.voice.stt.WhisperModel
import io.androllm.core.voice.stt.WhisperModelManager
import io.androllm.core.voice.stt.WhisperModels
import io.androllm.core.voice.tts.TtsVoiceManager
import io.androllm.core.voice.tts.TtsVoices
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state of the voice-language card (AndroLLM fork, branch a-voice-ru). */
data class VoiceLanguageUiState(
    val language: VoiceLanguage = VoiceLanguage.DEFAULT,
    val sttModel: WhisperModel? = null,
    val sttModelInstalled: Boolean = false,
    val sttProgress: Float? = null,
    val ttsAvailable: Boolean = true,
    val ttsInstalled: Boolean = false,
    val ttsProgress: Float? = null,
    val message: String? = null
)

@HiltViewModel
class VoiceLanguageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: VoiceSettingsStore,
    private val whisperModels: WhisperModelManager,
    private val ttsVoices: TtsVoiceManager
) : ViewModel() {

    private data class Transient(
        val sttProgress: Float? = null,
        val ttsProgress: Float? = null,
        val message: String? = null,
        val tick: Int = 0
    )

    private val transient = MutableStateFlow(Transient())

    val state: StateFlow<VoiceLanguageUiState> =
        combine(settingsStore.settings, transient) { s, t ->
            val lang = VoiceLanguage.fromCode(s.sttLanguage)
            val selected = WhisperModels.byId(s.whisperModel)
            // EN: offer the small base.en unless a compatible model is already installed.
            val model = selected?.takeIf {
                VoiceLanguagePolicy.isCompatible(it, lang) &&
                    (lang != VoiceLanguage.EN || whisperModels.isInstalled(it))
            } ?: VoiceLanguagePolicy.recommendedModel(lang, ramGb())
            VoiceLanguageUiState(
                language = lang,
                sttModel = model,
                sttModelInstalled = whisperModels.isInstalled(model),
                sttProgress = t.sttProgress,
                ttsAvailable = lang != VoiceLanguage.EN,
                ttsInstalled = ttsVoices.isInstalled(TtsVoices.DEFAULT_RU),
                ttsProgress = t.ttsProgress,
                message = t.message
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceLanguageUiState())

    fun setLanguage(lang: VoiceLanguage) {
        viewModelScope.launch {
            settingsStore.update { s ->
                val current = WhisperModels.byId(s.whisperModel)
                val keep = current != null && VoiceLanguagePolicy.isCompatible(current, lang)
                s.copy(
                    sttLanguage = lang.code,
                    whisperModel = if (keep) s.whisperModel else VoiceLanguagePolicy.recommendedModel(lang, ramGb()).id
                )
            }
        }
    }

    fun downloadSttModel() {
        val model = state.value.sttModel ?: return
        if (transient.value.sttProgress != null) return
        viewModelScope.launch {
            transient.update { it.copy(sttProgress = 0f, message = null) }
            val result = runCatching {
                whisperModels.download(model) { done, total ->
                    transient.update { it.copy(sttProgress = if (total > 0) done.toFloat() / total else 0f) }
                }
            }
            if (result.isSuccess) settingsStore.update { it.copy(whisperModel = model.id) }
            transient.update {
                it.copy(
                    sttProgress = null,
                    message = result.exceptionOrNull()?.let { e -> "Модель речи: ошибка — ${e.message}" },
                    tick = it.tick + 1
                )
            }
        }
    }

    fun downloadTtsVoice() {
        if (transient.value.ttsProgress != null) return
        viewModelScope.launch {
            transient.update { it.copy(ttsProgress = 0f, message = null) }
            val result = runCatching {
                ttsVoices.download(TtsVoices.DEFAULT_RU) { done, total ->
                    transient.update { it.copy(ttsProgress = if (total > 0) done.toFloat() / total else 0f) }
                }
            }
            transient.update {
                it.copy(
                    ttsProgress = null,
                    message = result.exceptionOrNull()?.let { e -> "Голос: ошибка — ${e.message}" },
                    tick = it.tick + 1
                )
            }
        }
    }

    private fun ramGb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 8
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L * 1024L)).coerceAtLeast(1L)
    }
}

/**
 * Voice language picker (ru / en / az / auto) + on-demand downloads of the
 * multilingual whisper model and the Russian Piper voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLanguageSection(viewModel: VoiceLanguageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Язык голоса / Voice language", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.ledger.deskPaper)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VoiceLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = state.language == lang,
                        onClick = { viewModel.setLanguage(lang) },
                        label = { Text(lang.code.uppercase()) }
                    )
                }
            }
            Text(state.language.label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.ledger.deskInk)
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            val model = state.sttModel
            DownloadRow(
                title = "Распознавание: ${model?.displayName ?: "—"} (${model?.sizeLabel ?: ""})",
                installed = state.sttModelInstalled,
                progress = state.sttProgress,
                onDownload = viewModel::downloadSttModel
            )
            if (state.ttsAvailable) {
                DownloadRow(
                    title = "Голос: ${TtsVoices.DEFAULT_RU.displayName} " +
                        "(${TtsVoices.DEFAULT_RU.archiveBytes / (1024 * 1024)} MB)",
                    installed = state.ttsInstalled,
                    progress = state.ttsProgress,
                    onDownload = viewModel::downloadTtsVoice
                )
                if (state.language == VoiceLanguage.AZ) {
                    Text("Азербайджанского офлайн-голоса нет: латиница озвучивается английским голосом.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.ledger.deskInk)
                }
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DownloadRow(title: String, installed: Boolean, progress: Float?, onDownload: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskPaper,
                modifier = Modifier.weight(1f))
            when {
                installed -> Text("Установлено", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.lampAmber)
                progress != null -> Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.lampAmber)
                else -> TextButton(onClick = onDownload) {
                    Text("Скачать", color = MaterialTheme.ledger.lampAmber)
                }
            }
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
