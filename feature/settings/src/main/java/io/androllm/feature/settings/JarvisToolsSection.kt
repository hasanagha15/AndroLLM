package io.androllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.jarvistools.secrets.JarvisSecret
import io.androllm.core.jarvistools.settings.JarvisToolsConfig
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import javax.inject.Inject

/**
 * Branch C: Settings card for the Jarvis tools (tesla-bridge, WhatsApp bot,
 * Even G2). Tokens are typed by the owner here and go straight into the
 * Keystore-encrypted store; the UI never reads a token back — it only shows
 * "set / not set".
 */
@HiltViewModel
class JarvisToolsSettingsViewModel @Inject constructor(
    private val config: JarvisToolsConfig
) : ViewModel() {
    var teslaUrl by mutableStateOf(config.teslaBaseUrl); private set
    var whatsappUrl by mutableStateOf(config.whatsappBaseUrl); private set
    var teslaTokenSet by mutableStateOf(config.hasToken(JarvisSecret.TESLA_API_TOKEN)); private set
    var whatsappTokenSet by mutableStateOf(config.hasToken(JarvisSecret.WHATSAPP_API_TOKEN)); private set

    fun saveTeslaUrl(url: String) { config.setTeslaBaseUrl(url); teslaUrl = config.teslaBaseUrl }
    fun saveWhatsappUrl(url: String) { config.setWhatsappBaseUrl(url); whatsappUrl = config.whatsappBaseUrl }

    fun saveToken(secret: JarvisSecret, value: String) {
        if (value.isBlank()) return
        config.setToken(secret, value)
        refresh()
    }

    fun clearToken(secret: JarvisSecret) { config.clearToken(secret); refresh() }

    private fun refresh() {
        teslaTokenSet = config.hasToken(JarvisSecret.TESLA_API_TOKEN)
        whatsappTokenSet = config.hasToken(JarvisSecret.WHATSAPP_API_TOKEN)
    }
}

@Composable
fun JarvisToolsSettingsCard(viewModel: JarvisToolsSettingsViewModel = hiltViewModel()) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Jarvis tools", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
            Text(
                "Tesla (tesla-bridge), WhatsApp bot, Even G2. Tokens are stored encrypted (Android Keystore). " +
                    "Every command / message / glasses text asks for confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            EndpointBlock(
                title = "Tesla bridge",
                url = viewModel.teslaUrl,
                tokenSet = viewModel.teslaTokenSet,
                tokenLabel = "API_TOKEN",
                onSaveUrl = viewModel::saveTeslaUrl,
                onSaveToken = { viewModel.saveToken(JarvisSecret.TESLA_API_TOKEN, it) },
                onClearToken = { viewModel.clearToken(JarvisSecret.TESLA_API_TOKEN) }
            )
            EndpointBlock(
                title = "WhatsApp bot (home Wi-Fi only)",
                url = viewModel.whatsappUrl,
                tokenSet = viewModel.whatsappTokenSet,
                tokenLabel = "API_TOKEN",
                onSaveUrl = viewModel::saveWhatsappUrl,
                onSaveToken = { viewModel.saveToken(JarvisSecret.WHATSAPP_API_TOKEN, it) },
                onClearToken = { viewModel.clearToken(JarvisSecret.WHATSAPP_API_TOKEN) }
            )
            Text(
                "Even G2: texts are shown as an AndroLLM notification — enable AndroLLM in Even app → Notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
        }
    }
}

@Composable
private fun EndpointBlock(
    title: String,
    url: String,
    tokenSet: Boolean,
    tokenLabel: String,
    onSaveUrl: (String) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit
) {
    var urlInput by remember(url) { mutableStateOf(url) }
    var tokenInput by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text(if (tokenSet) "$tokenLabel (set — type to replace)" else "$tokenLabel (not set)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (urlInput != url) onSaveUrl(urlInput)
                if (tokenInput.isNotBlank()) {
                    onSaveToken(tokenInput)
                    tokenInput = ""
                }
            }) { Text("Save") }
            if (tokenSet) TextButton(onClick = onClearToken) { Text("Remove token") }
        }
    }
}
