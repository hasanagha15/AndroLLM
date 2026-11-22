package io.androllm.core.jarvislink.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvislink.processing.SeenIdStore
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret Jarvis link settings. The password is never part of this object. */
data class JarvisLinkSettings(
    val enabled: Boolean = false,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val username: String = DEFAULT_USER,
    val hasPassword: Boolean = false
) {
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535 && username.isNotBlank() && hasPassword

    companion object {
        /** pi-brain over Tailscale (the phone is not on the home LAN when away). */
        const val DEFAULT_HOST = "100.64.0.10"
        const val DEFAULT_PORT = 1883
        const val DEFAULT_USER = "androllm-phone"
    }
}

/**
 * Persists the link settings. Host/port/user/enable live in private
 * SharedPreferences; the broker password is encrypted with an AES-256/GCM key
 * that never leaves the Android Keystore (alias [KEY_ALIAS]) and only the
 * ciphertext is stored. Off by default.
 */
@Singleton
class JarvisLinkSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) : SeenIdStore {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<JarvisLinkSettings> = _settings.asStateFlow()

    fun current(): JarvisLinkSettings = _settings.value

    fun update(host: String, port: Int, username: String) {
        prefs.edit()
            .putString(K_HOST, host.trim())
            .putInt(K_PORT, port)
            .putString(K_USER, username.trim())
            .apply()
        _settings.value = read()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply()
        _settings.value = read()
    }

    /** Stores [password] encrypted; blank clears it. The value is never logged. */
    fun setPassword(password: String) {
        val edit = prefs.edit()
        if (password.isEmpty()) edit.remove(K_PASSWORD) else edit.putString(K_PASSWORD, encrypt(password))
        edit.apply()
        _settings.value = read()
    }

    /** Decrypted password for the MQTT CONNECT, or null. Callers must not log it. */
    fun password(): ByteArray? {
        val ct = prefs.getString(K_PASSWORD, null) ?: return null
        return runCatching { decrypt(ct) }.getOrNull()
    }

    /** Stable per-install MQTT client id (persistent session key). */
    fun clientId(): String {
        prefs.getString(K_CLIENT_ID, null)?.let { return it }
        val id = "androllm-phone-" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(K_CLIENT_ID, id).apply()
        return id
    }

    // ── SeenIdStore (dedupe persistence; ids are not secret) ──────────────
    override fun load(): List<String> =
        prefs.getString(K_SEEN, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    override fun save(ids: List<String>) {
        prefs.edit().putString(K_SEEN, ids.joinToString("\n")).apply()
    }

    private fun read() = JarvisLinkSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        host = prefs.getString(K_HOST, null) ?: JarvisLinkSettings.DEFAULT_HOST,
        port = prefs.getInt(K_PORT, JarvisLinkSettings.DEFAULT_PORT),
        username = prefs.getString(K_USER, null) ?: JarvisLinkSettings.DEFAULT_USER,
        hasPassword = prefs.contains(K_PASSWORD)
    )

    // ── Android Keystore AES/GCM ────────────────────────────────────────────
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        return cipher.doFinal(all, IV_LEN, all.size - IV_LEN)
    }

    private companion object {
        const val PREFS = "jarvis_link"
        const val K_ENABLED = "enabled"
        const val K_HOST = "host"
        const val K_PORT = "port"
        const val K_USER = "username"
        const val K_PASSWORD = "password_ct"
        const val K_CLIENT_ID = "client_id"
        const val K_SEEN = "seen_task_ids"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "androllm_jarvislink_mqtt"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
