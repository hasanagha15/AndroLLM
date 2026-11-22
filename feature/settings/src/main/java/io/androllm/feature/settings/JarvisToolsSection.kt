package io.androllm.feature.settings

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.jarvistools.settings.JarvisToolsConfig
import io.androllm.core.jarvistools.vault.JarvisSecret
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import javax.inject.Inject

/**
 * Branch C: Settings card for the Jarvis tools (tesla-bridge, WhatsApp bot,
 * Even G2). Tokens are typed/pasted by the owner here and go straight into
 * the Keystore-encrypted store, bound to the URL's origin; the UI never reads
 * a token back — it only shows "set / not set". After saving a token the
 * clipboard is cleared (the base app puts the clipboard into the model context).
 */
@HiltViewModel
class JarvisToolsSettingsViewModel @Inject constructor(
    private val config: JarvisToolsConfig
) : ViewModel() {
    var teslaUrl by mutableStateOf(config.teslaBaseUrl); private set
    var whatsappUrl by mutableStateOf(config.whatsappBaseUrl); private set
    var teslaTokenSet by mutableStateOf(config.hasToken(JarvisSecret.TESLA_API_TOKEN)); private set
    var whatsappTokenSet by mutableStateOf(config.hasToken(JarvisSecret.WHATSAPP_API_TOKEN)); private set
    var message by mutableStateOf<String?>(null); private set

    /** Returns true when everything was saved. */
    fun save(secret: JarvisSecret, url: String, token: String): Boolean {
        val currentUrl = if (secret == JarvisSecret.TESLA_API_TOKEN) teslaUrl else whatsappUrl
        if (url.trim().trimEnd('/') != currentUrl) {
            val err = if (secret == JarvisSecret.TESLA_API_TOKEN) config.setTeslaBaseUrl(url) else config.setWhatsappBaseUrl(url)
            if (err != null) {
                message = "URL not saved: $err"
                return false
            }
        }
        if (token.isNotBlank()) {
            config.setToken(secret, token)?.let { message = it; refresh(); return false }
        }
        message = null
        refresh()
        return true
    }

    fun clearToken(secret: JarvisSecret) { config.clearToken(secret); refresh() }

    fun note(text: String) { message = text }

    private fun refresh() {
        teslaUrl = config.teslaBaseUrl
        whatsappUrl = config.whatsappBaseUrl
        teslaTokenSet = config.hasToken(JarvisSecret.TESLA_API_TOKEN)
        whatsappTokenSet = config.hasToken(JarvisSecret.WHATSAPP_API_TOKEN)
    }
}

@Composable
fun JarvisToolsSettingsCard(viewModel: JarvisToolsSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val clearClipboard: (saved: Boolean) -> Unit = { saved ->
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
        }
        if (saved) viewModel.note("Token saved. Clipboard cleared.")
    }
    CloudGlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Jarvis tools", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
            Text(
                "Tesla (tesla-bridge), WhatsApp bot, Even G2. Tokens are stored encrypted (Android Keystore) and bound " +
                    "to the URL — changing a URL requires entering its token again. Paste a token and press Save right away " +
                    "(the clipboard is then cleared); do not chat with the assistant while a token is in the clipboard. " +
                    "Every command / message / glasses text asks for confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            EndpointBlock(
                title = "Tesla bridge (https only)",
                url = viewModel.teslaUrl,
                tokenSet = viewModel.teslaTokenSet,
                tokenLabel = "PHONE_TOKEN",
                onSave = { url, token ->
                    val ok = viewModel.save(JarvisSecret.TESLA_API_TOKEN, url, token)
                    if (token.isNotBlank()) clearClipboard(ok)
                    ok
                },
                onClearToken = { viewModel.clearToken(JarvisSecret.TESLA_API_TOKEN) }
            )
            EndpointBlock(
                title = "WhatsApp bot (over Tailscale)",
                url = viewModel.whatsappUrl,
                tokenSet = viewModel.whatsappTokenSet,
                tokenLabel = "JARVIS_API_TOKEN",
                onSave = { url, token ->
                    val ok = viewModel.save(JarvisSecret.WHATSAPP_API_TOKEN, url, token)
                    if (token.isNotBlank()) clearClipboard(ok)
                    ok
                },
                onClearToken = { viewModel.clearToken(JarvisSecret.WHATSAPP_API_TOKEN) }
            )
            viewModel.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Token saved")) MaterialTheme.ledger.deskInk else Color(0xFFE57373))
            }
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
    onSave: (url: String, token: String) -> Boolean,
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
                if (onSave(urlInput, tokenInput)) tokenInput = ""
            }) { Text("Save") }
            if (tokenSet) TextButton(onClick = onClearToken) { Text("Remove token") }
        }
    }
}
