package io.androllm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.androllm.core.jarvislink.mqtt.LinkState
import io.androllm.core.jarvislink.service.JarvisLinkController
import io.androllm.core.jarvislink.service.JarvisLinkStatus
import io.androllm.core.jarvislink.settings.JarvisLinkSettings
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Branch b-mqtt: settings for the Jarvis reverse channel (MQTT). */
@HiltViewModel
class JarvisLinkViewModel @Inject constructor(
    private val store: JarvisLinkSettingsStore,
    private val controller: JarvisLinkController,
    status: JarvisLinkStatus
) : ViewModel() {
    val settings: StateFlow<JarvisLinkSettings> = store.settings
    val state: StateFlow<LinkState> = status.state

    init {
        // Re-attach the service when Settings opens with the link enabled.
        controller.sync()
    }

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        controller.sync()
    }

    /** Saves the connection fields. Blank [password] / [signingKeyHex] keep the stored ones. */
    fun save(host: String, port: String, username: String, password: String, signingKeyHex: String) {
        store.update(host, port.toIntOrNull() ?: JarvisLinkSettings.DEFAULT_PORT, username)
        if (password.isNotEmpty()) store.setPassword(password)
        if (signingKeyHex.isNotBlank() && !store.setSigningKeyHex(signingKeyHex)) {
            _message.value = "Signing key must be 64 hex characters — not saved"
        } else {
            _message.value = if (store.current().hostIsTailscale) null
            else "Host must be a Tailscale address (100.x) or *.ts.net name — the link will not connect to LAN/public hosts"
        }
        controller.restart()
    }
}

/**
 * "Jarvis link" card: lets Jarvis (pi-brain) send tasks to the phone over MQTT.
 * Off by default. Remote tasks use the same confirmations as typed requests.
 */
@Composable
fun JarvisLinkSection(viewModel: JarvisLinkViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.state.collectAsState()
    var host by remember(settings.host) { mutableStateOf(settings.host) }
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var user by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    var signingKey by remember { mutableStateOf("") }
    val message by viewModel.message.collectAsState()

    CloudGlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.SettingsRemote, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jarvis link (MQTT)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                    Text(
                        "Receive signed tasks from Jarvis over Tailscale. Anything beyond read-only tools asks you first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = settings.isComplete || settings.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }
            Text(
                "Status: " + when (val s = state) {
                    LinkState.Off -> if (settings.isComplete) "off" else "off — enter Tailscale host, user, password and signing key"
                    LinkState.Connecting -> "connecting…"
                    LinkState.Connected -> "connected"
                    is LinkState.Retrying -> "reconnecting in ${s.inMs / 1000}s"
                    is LinkState.AuthFailed -> "broker rejected credentials"
                    is LinkState.WaitingForRoute -> "waiting for Tailscale (${s.reason})"
                    is LinkState.Refused -> "not connecting: ${s.reason}"
                    is LinkState.ConfigError -> s.reason
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Broker host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (settings.hasPassword) "Password (saved — leave blank to keep)" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = signingKey,
                onValueChange = { signingKey = it.trim() },
                label = { Text(if (settings.hasSigningKey) "Task signing key (saved — leave blank to keep)" else "Task signing key (64 hex)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (message != null) {
                Text(message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                viewModel.save(host, port, user, password, signingKey)
                password = ""
                signingKey = ""
            }) { Text("Save & reconnect") }
        }
    }
}
