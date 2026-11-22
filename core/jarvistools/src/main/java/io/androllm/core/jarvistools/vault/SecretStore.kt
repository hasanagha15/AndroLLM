package io.androllm.core.jarvistools.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Names of the secrets the owner enters in Settings → Jarvis tools. Values are NEVER in code.
 * (Package is `vault`, not `secrets`: the repo .gitignore ignores every `secrets/` directory.)
 */
enum class JarvisSecret(val prefKey: String) {
    /** tesla-bridge PHONE_TOKEN (scoped: /api/status + tier-A commands only). */
    TESLA_API_TOKEN("tesla_api_token"),
    /** wa-bot JARVIS_API_TOKEN (Bearer for /api/status, /api/pending, /api/inbox, /api/reply). */
    WHATSAPP_API_TOKEN("whatsapp_api_token")
}

/**
 * Storage for the owner's tokens. Every token is bound to the ORIGIN
 * (scheme://host:port) it was entered for: reading it for another origin
 * returns null, so a changed base URL can never receive the old token.
 */
interface SecretStore {
    fun get(secret: JarvisSecret, origin: String): String?
    fun put(secret: JarvisSecret, value: String, origin: String)
    fun clear(secret: JarvisSecret)
    /** Cheap check without decrypting: a token exists AND was entered for [origin]. */
    fun has(secret: JarvisSecret, origin: String): Boolean
}

/**
 * Tokens encrypted with AES-256-GCM under a non-exportable key in the Android
 * Keystore; the origin is the GCM AAD, so decryption fails for any other
 * origin. Only ciphertext (IV + data, Base64) and the origin label land in a
 * private SharedPreferences file. If the Keystore key is gone (backup restored
 * on another device) the token reads as "not set" and must be re-entered.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context
) : SecretStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(secret: JarvisSecret, origin: String): String? {
        if (!has(secret, origin)) return null
        val stored = prefs.getString(secret.prefKey, null) ?: return null
        return runCatching { decrypt(stored, origin) }
            .onFailure { Timber.w("jarvistools: cannot decrypt ${secret.name} (${it.javaClass.simpleName}) — re-enter it") }
            .getOrNull()
    }

    override fun put(secret: JarvisSecret, value: String, origin: String) {
        val v = value.trim()
        if (v.isEmpty()) {
            clear(secret)
            return
        }
        prefs.edit()
            .putString(secret.prefKey, encrypt(v, origin))
            .putString(secret.prefKey + ORIGIN_SUFFIX, origin)
            .apply()
    }

    override fun clear(secret: JarvisSecret) {
        prefs.edit().remove(secret.prefKey).remove(secret.prefKey + ORIGIN_SUFFIX).apply()
    }

    override fun has(secret: JarvisSecret, origin: String): Boolean =
        prefs.contains(secret.prefKey) && prefs.getString(secret.prefKey + ORIGIN_SUFFIX, null) == origin

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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

    private fun encrypt(plain: String, origin: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String, origin: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val ivLen = raw[0].toInt()
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val data = raw.copyOfRange(1 + ivLen, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS = "jarvistools_secrets"
        const val ORIGIN_SUFFIX = "__origin"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "androllm_jarvistools_tokens_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
