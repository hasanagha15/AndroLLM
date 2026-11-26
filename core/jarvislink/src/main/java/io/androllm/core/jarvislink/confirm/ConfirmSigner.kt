package io.androllm.core.jarvislink.confirm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Signs AppFunction approvals. The private key never leaves the phone. */
interface ConfirmSigner {
    /** DER SubjectPublicKeyInfo of the signing key (public; read by the Acer over adb only). */
    fun publicKeySpki(): ByteArray

    /** DER ECDSA P-256 / SHA-256 signature of [message]. */
    fun sign(message: ByteArray): ByteArray
}

/**
 * Gate V B1: a NON-EXPORTABLE EC P-256 key in the Android Keystore (StrongBox when the device has it).
 * No user authentication is bound to the key: the owner's tap on the tap-only card is the gate, and the key is
 * only ever used by [AppFunctionConfirmHandler] after `awaitDecision` returned true. Nothing derived from the
 * Jarvis link key is involved, so pi-brain (which holds the link key) cannot produce an approval.
 */
@Singleton
class KeystoreConfirmSigner @Inject constructor() : ConfirmSigner {

    @Synchronized
    private fun privateKey(): PrivateKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        generate(strongBox = true)
        return (ks.apply { load(null) }.getKey(ALIAS, null) as PrivateKey)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            if (!strongBox) throw e
            Timber.i("AppFunction confirm key: no StrongBox, using TEE Keystore")
            generate(strongBox = false)
        }
    }

    override fun publicKeySpki(): ByteArray {
        privateKey()
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getCertificate(ALIAS).publicKey.encoded
    }

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey())
            update(message)
            sign()
        }

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "androllm_appfunction_confirm_v1"
    }
}
