package io.androllm.core.jarvislink.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvislink.mqtt.TailscaleRoute
import io.androllm.core.jarvislink.processing.SeenIdStore
import io.androllm.core.jarvislink.protocol.JarvisProtocol
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

/** Non-secret Jarvis link settings. Secrets are never part of this object. */
data class JarvisLinkSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val hasPassword: Boolean = false,
    val hasSigningKey: Boolean = false
) {
    val hostIsTailscale: Boolean get() = TailscaleRoute.classify(host) != TailscaleRoute.HostKind.NOT_TAILSCALE

    val isComplete: Boolean
        get() = hostIsTailscale && port in 1..65535 && username.isNotBlank() && hasPassword && hasSigningKey

    companion object {
        const val DEFAULT_PORT = 1883
    }
}

/**
 * Persists the link settings. Host/port/user/enable live in private
 * SharedPreferences; the broker password and the task signing key are
 * encrypted with an AES-256/GCM key that never leaves the Android Keystore
 * (alias [KEY_ALIAS]) — only ciphertext is stored. Off by default; no host
 * or user is preconfigured.
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

    /** Stores [password] encrypted; blank clears it. Never logged. */
    fun setPassword(password: String) {
        putSecret(K_PASSWORD, password.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
    }

    /** Stores the 64-hex-char task signing key; returns false when malformed. */
    fun setSigningKeyHex(hex: String): Boolean {
        if (hex.isBlank()) { putSecret(K_SIGNING_KEY, null); return true }
        val key = JarvisProtocol.parseKeyHex(hex) ?: return false
        putSecret(K_SIGNING_KEY, key)
        return true
    }

    /** Decrypted password, or null (missing or undecryptable, e.g. after a backup restore). */
    fun password(): ByteArray? = getSecret(K_PASSWORD)

    /** Decrypted 32-byte signing key, or null. */
    fun signingKey(): ByteArray? = getSecret(K_SIGNING_KEY)?.takeIf { it.size == JarvisProtocol.KEY_BYTES }

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

    /** commit(), not apply(): an id must be on disk before its task runs. */
    override fun save(ids: List<String>) {
        prefs.edit().putString(K_SEEN, ids.joinToString("\n")).commit()
    }

    private fun putSecret(name: String, value: ByteArray?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(name) else edit.putString(name, encrypt(value))
        edit.apply()
        _settings.value = read()
    }

    private fun getSecret(name: String): ByteArray? {
        val ct = prefs.getString(name, null) ?: return null
        return runCatching { decrypt(ct) }.getOrNull()
    }

    private fun read() = JarvisLinkSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        host = prefs.getString(K_HOST, null).orEmpty(),
        port = prefs.getInt(K_PORT, JarvisLinkSettings.DEFAULT_PORT),
        username = prefs.getString(K_USER, null).orEmpty(),
        hasPassword = prefs.contains(K_PASSWORD),
        hasSigningKey = prefs.contains(K_SIGNING_KEY)
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

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        return cipher.doFinal(all, IV_LEN, all.size - IV_LEN)
    }

    companion object {
        const val PREFS = "jarvis_link"
        const val K_ENABLED = "enabled"
        const val K_HOST = "host"
        const val K_PORT = "port"
        const val K_USER = "username"
        const val K_PASSWORD = "password_ct"
        const val K_SIGNING_KEY = "signing_key_ct"
        private const val K_CLIENT_ID = "client_id"
        private const val K_SEEN = "seen_task_ids"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "androllm_jarvislink_mqtt"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
    }
}
