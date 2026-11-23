package io.androllm.core.memory.personal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.personalContextDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "personal_context_prefs"
)

/**
 * On-device store for [PersonalContextSettings] (AndroLLM fork F1). The text
 * is typed by the user in Settings → Memory → Personal context and never
 * leaves the device except as part of the prompt of a chat the user starts
 * (cloud mode sends the prompt to the selected provider, like any message).
 * Empty by default.
 */
@Singleton
class PersonalContextStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TEXT = stringPreferencesKey("text")
        val REPLY_LANGUAGE = stringPreferencesKey("reply_language")
    }

    private val dataStore: DataStore<Preferences> = context.personalContextDataStore

    val settings: Flow<PersonalContextSettings> = dataStore.data.map { prefs ->
        PersonalContextSettings(
            text = prefs[Keys.TEXT].orEmpty(),
            replyLanguage = ReplyLanguage.fromCode(prefs[Keys.REPLY_LANGUAGE])
        )
    }

    suspend fun current(): PersonalContextSettings = settings.first()

    suspend fun setText(text: String) {
        val clean = text.trim().take(PersonalContextSettings.MAX_TEXT_CHARS)
        dataStore.edit { it[Keys.TEXT] = clean }
    }

    suspend fun setReplyLanguage(lang: ReplyLanguage) {
        dataStore.edit { it[Keys.REPLY_LANGUAGE] = lang.code }
    }
}
