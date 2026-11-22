package io.androllm.core.jarvislink.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvislink.mqtt.LinkState
import io.androllm.core.jarvislink.settings.JarvisLinkSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/** Live link state, written by [JarvisLinkService], read by Settings. */
@Singleton
class JarvisLinkStatus @Inject constructor() {
    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    internal fun set(value: LinkState) { _state.value = value }
}

/**
 * Starts/stops [JarvisLinkService] to match the settings: the foreground
 * service runs ONLY while the link is enabled and fully configured.
 */
@Singleton
class JarvisLinkController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: JarvisLinkSettingsStore
) {
    fun sync() {
        val s = settingsStore.current()
        if (s.enabled && s.isComplete) start() else stop()
    }

    /** Restarts the connection with fresh settings (after host/user/password edits). */
    fun restart() {
        stop()
        sync()
    }

    private fun start() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, JarvisLinkService::class.java))
        }.onFailure { Timber.w(it, "JarvisLink: could not start service") }
    }

    private fun stop() {
        context.stopService(Intent(context, JarvisLinkService::class.java))
    }
}
