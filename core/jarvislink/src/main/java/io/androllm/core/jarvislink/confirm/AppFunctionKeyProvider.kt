package io.androllm.core.jarvislink.confirm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Base64

/**
 * Gate V B1: exports ONLY the PUBLIC half of the AppFunction confirmation key, to the adb shell.
 * Manifest: `readPermission = android.permission.DUMP` (signature|privileged|development: the shell holds it,
 * ordinary apps cannot get it). The Acer pulls it with
 * `adb shell content query --uri content://<applicationId>.afckey/pub` (fetch_confirm_pubkey.py).
 * Read-only: insert/update/delete are refused.
 */
class AppFunctionKeyProvider : ContentProvider() {

    /** Gate V rev2 N3: the same @Singleton signer the handler uses (one key, one lock). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SignerEntryPoint {
        fun confirmSigner(): ConfirmSigner
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.lastPathSegment != "pub") return null
        val ctx = context?.applicationContext ?: return null
        val spki = EntryPointAccessors.fromApplication(ctx, SignerEntryPoint::class.java).confirmSigner().publicKeySpki()
        val fp = AppFunctionConfirmHandler.fingerprint(spki)
        return MatrixCursor(arrayOf("spki", "sha256")).apply {
            addRow(arrayOf<Any>(Base64.getEncoder().encodeToString(spki), fp))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
