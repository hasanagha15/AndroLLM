package io.androllm.core.jarvislink.confirm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.security.MessageDigest
import java.util.Base64

/**
 * Gate V B1: exports ONLY the PUBLIC half of the AppFunction confirmation key, to the adb shell.
 * Manifest: `readPermission = android.permission.DUMP` (signature|privileged|development: the shell holds it,
 * ordinary apps cannot get it). The Acer pulls it with
 * `adb shell content query --uri content://<applicationId>.afckey/pub` (fetch_confirm_pubkey.py).
 * Read-only: insert/update/delete are refused.
 */
class AppFunctionKeyProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.lastPathSegment != "pub") return null
        val spki = KeystoreConfirmSigner().publicKeySpki()
        val fp = MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }
        return MatrixCursor(arrayOf("spki", "sha256")).apply {
            addRow(arrayOf<Any>(Base64.getEncoder().encodeToString(spki), fp))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
