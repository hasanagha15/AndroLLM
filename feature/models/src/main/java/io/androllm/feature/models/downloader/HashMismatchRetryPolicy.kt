package io.androllm.feature.models.downloader

import android.content.Context

/**
 * Caps the "hash mismatch → truncate → full re-download" loop (AndroLLM fork).
 *
 * Before: a SHA-256 mismatch on complete bytes truncated the file and returned
 * `Result.retry()` with no limit. When the file on Hugging Face was replaced
 * upstream (stale catalog sha256), every pass downloaded gigabytes again and
 * failed again, forever.
 *
 * Now: the n-th mismatch for the same (model id, expected sha256) is counted in
 * a small persistent store. Up to [MAX_DOWNLOADS_WITH_MISMATCH] complete
 * downloads may end in a mismatch (i.e. one automatic re-download); the next
 * mismatch at the cap becomes a permanent failure with a clear message. The
 * counter is cleared on a verified download and on the permanent failure, so a
 * later manual "Download" by the user starts fresh.
 */
object HashMismatchRetryPolicy {

    /** Complete downloads allowed to end in a hash mismatch before giving up. */
    const val MAX_DOWNLOADS_WITH_MISMATCH = 2

    sealed interface Decision {
        /** Truncate and let WorkManager re-download; [mismatches] so far. */
        data class Retry(val mismatches: Int) : Decision

        /** Stop: permanent failure after [mismatches] mismatching downloads. */
        data class GiveUp(val mismatches: Int) : Decision
    }

    /** Persistent counter storage (SharedPreferences on device, a map in tests). */
    interface CounterStore {
        fun get(key: String): Int
        fun set(key: String, value: Int)
        fun clear(key: String)
    }

    fun key(modelId: String, expectedSha256: String?): String =
        "$modelId|${expectedSha256?.lowercase() ?: "no-sha"}"

    /** Records one more mismatch and decides whether to retry or give up. */
    fun onMismatch(store: CounterStore, key: String): Decision {
        val mismatches = store.get(key).coerceAtLeast(0) + 1
        return if (mismatches >= MAX_DOWNLOADS_WITH_MISMATCH) {
            store.clear(key)
            Decision.GiveUp(mismatches)
        } else {
            store.set(key, mismatches)
            Decision.Retry(mismatches)
        }
    }

    /** A verified download resets the counter. */
    fun onVerified(store: CounterStore, key: String) = store.clear(key)

    /** User-facing message for the permanent failure. */
    fun giveUpMessage(
        modelName: String,
        mismatches: Int,
        expectedSha256: String?,
        actualSha256: String?,
    ): String = buildString {
        append("$modelName: SHA-256 checksum did not match after $mismatches complete downloads ")
        append("(expected ${expectedSha256?.take(16) ?: "?"}…, got ${actualSha256?.take(16) ?: "?"}…). ")
        append("The file on the server no longer matches the catalog (it was probably re-uploaded), ")
        append("so downloading again will not help. Refresh the model catalog, then start the download ")
        append("again from the catalog (not from Retry on an old entry), or pick another model. ")
        append("The partial file was truncated to free space; automatic retries are stopped.")
    }

    /**
     * Pre-download check: null when the download may start; otherwise the
     * permanent-failure message because the server's LFS sha256 (from the
     * preflight X-Linked-ETag) differs from the catalog's sha256.
     */
    fun serverMismatchMessage(modelName: String, catalogSha256: String?, serverSha256: String?): String? {
        val catalog = catalogSha256?.trim()?.takeIf { it.matches(SHA256) } ?: return null
        val server = serverSha256?.trim()?.trim('"')?.takeIf { it.matches(SHA256) } ?: return null
        if (catalog.equals(server, ignoreCase = true)) return null
        return "$modelName: the file on the server (sha256 ${server.take(16)}…) no longer matches the catalog " +
            "(sha256 ${catalog.take(16)}…), so nothing was downloaded. Refresh the model catalog and start " +
            "the download again from the catalog, or pick another model."
    }

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** SharedPreferences-backed store; survives process death between WorkManager attempts. */
    class PrefsStore(context: Context) : CounterStore {
        private val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun get(key: String): Int = prefs.getInt(key, 0)
        override fun set(key: String, value: Int) {
            // commit(): runs on the worker thread; a process kill right after the
            // first attempt must not lose the count (Gate D, N5).
            prefs.edit().putInt(key, value).commit()
        }

        override fun clear(key: String) {
            prefs.edit().remove(key).commit()
        }
    }

    private const val PREFS_NAME = "model_download_hash_mismatch"
}
