package io.androllm.core.accessibility.controller

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Legacy SharedPreferences file (F1); migrated once into the DataStore below. */
internal const val SENSITIVE_APPS_LEGACY_PREFS = "sensitive_app_guard"

private val Context.sensitiveAppsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sensitive_app_guard_prefs",
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, SENSITIVE_APPS_LEGACY_PREFS)) }
)

/**
 * On-device store for the user's extra banking/payment packages that
 * [SensitiveAppGuard] must protect (K: replaces the F1 global mutable
 * `extraPackages` var). Injected into [AccessibilityController] and the
 * settings card; the guard itself stays a pure function of (package, extras).
 *
 * The file is excluded from cloud backup and device transfer
 * (backup_rules.xml / data_extraction_rules.xml).
 */
@Singleton
class SensitiveAppStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore: DataStore<Preferences> = context.sensitiveAppsDataStore

    val extras: Flow<Set<String>> = dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> SensitiveAppGuard.parsePackages(prefs[KEY_EXTRA_PACKAGES]) }

    /** The user's extra packages; empty when unset or unreadable. */
    suspend fun current(): Set<String> = runCatching { extras.first() }.getOrDefault(emptySet())

    /** Parses [raw], stores the valid package names and returns them. */
    suspend fun save(raw: String): Set<String> {
        val parsed = SensitiveAppGuard.parsePackages(raw)
        dataStore.edit { it[KEY_EXTRA_PACKAGES] = parsed.joinToString(", ") }
        return parsed
    }

    companion object {
        /** Same key as the F1 SharedPreferences entry, so the migration keeps it. */
        val KEY_EXTRA_PACKAGES = stringPreferencesKey("extra_payment_packages")
    }
}
