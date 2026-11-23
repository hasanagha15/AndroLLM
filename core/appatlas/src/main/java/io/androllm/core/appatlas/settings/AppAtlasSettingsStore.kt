package io.androllm.core.appatlas.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppAtlasSettings(
    /** Master switch: when off the atlas tools answer "atlas disabled" and nothing is indexed. */
    val enabled: Boolean = true,
    /** Record replayable element paths (ids / app UI strings only) after successful UI tasks. */
    val recordPaths: Boolean = true,
    /** Packages the owner excluded: never indexed, never opened by atlas tools. */
    val exclusions: Set<String> = emptySet()
)

/**
 * On-device settings of the atlas (SharedPreferences `app_atlas_settings`,
 * excluded from backup in backup_rules.xml — the exclusion list names
 * installed apps). Also keeps the PackageManager change token.
 */
@Singleton
class AppAtlasSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _settings = MutableStateFlow(AppAtlasSettings())
    val settings: StateFlow<AppAtlasSettings> = _settings.asStateFlow()

    @Volatile private var loaded = false

    fun current(): AppAtlasSettings {
        if (!loaded) load()
        return _settings.value
    }

    private fun load() {
        runCatching {
            _settings.value = AppAtlasSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                recordPaths = prefs.getBoolean(KEY_RECORD, true),
                exclusions = SensitiveAppGuard.parsePackages(prefs.getString(KEY_EXCLUSIONS, ""))
            )
        }
        loaded = true
    }

    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setRecordPaths(v: Boolean) = update { it.copy(recordPaths = v) }

    /** Parses a comma/space separated package list; invalid entries are dropped. Returns the saved set. */
    fun setExclusions(raw: String): Set<String> {
        val parsed = SensitiveAppGuard.parsePackages(raw)
        update { it.copy(exclusions = parsed) }
        return parsed
    }

    private fun update(block: (AppAtlasSettings) -> AppAtlasSettings) {
        val next = block(current())
        _settings.value = next
        runCatching {
            prefs.edit()
                .putBoolean(KEY_ENABLED, next.enabled)
                .putBoolean(KEY_RECORD, next.recordPaths)
                .putString(KEY_EXCLUSIONS, next.exclusions.joinToString(", "))
                .apply()
        }
    }

    var changeSequence: Int
        get() = runCatching { prefs.getInt(KEY_SEQ, -1) }.getOrDefault(-1)
        set(v) { runCatching { prefs.edit().putInt(KEY_SEQ, v).apply() } }

    var lastSyncAt: Long
        get() = runCatching { prefs.getLong(KEY_LAST_SYNC, 0L) }.getOrDefault(0L)
        set(v) { runCatching { prefs.edit().putLong(KEY_LAST_SYNC, v).apply() } }

    var lastSweepAt: Long
        get() = runCatching { prefs.getLong(KEY_LAST_SWEEP, 0L) }.getOrDefault(0L)
        set(v) { runCatching { prefs.edit().putLong(KEY_LAST_SWEEP, v).apply() } }

    companion object {
        const val PREFS_NAME = "app_atlas_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RECORD = "record_paths"
        private const val KEY_EXCLUSIONS = "exclusions"
        private const val KEY_SEQ = "change_sequence"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_SWEEP = "last_sweep_at"
    }
}
