package io.androllm.app.jarvislink

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteAgentRunner
import io.androllm.core.jarvislink.processing.RemoteTurnExecutor
import io.androllm.core.models.MessageOrigin
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.androllm.feature.voice.chat.ChatManager
import javax.inject.Singleton

/**
 * Branch b-mqtt hook. A Jarvis task runs as ONE agent turn of the existing
 * chat pipeline ([ChatManager] → ToolRunCoordinator → ToolExecutor), but:
 * - inside [RemoteToolOrigin] (set by [RemoteAgentRunner]) — ToolExecutor then
 *   allows only read-only tools without a tap, refuses NEVER mode and calls
 *   aimed at AndroLLM itself, and remote cards never accept a spoken "yes";
 * - in a dedicated, archived conversation, never the owner's active chat;
 * - without memory retrieval/extraction and without conversation history;
 * - after the owner's own turn has finished (bounded wait).
 */
@Module
@InstallIn(SingletonComponent::class)
object JarvisLinkHookModule {

    const val CONVERSATION_ID = "jarvis-remote-tasks"
    const val CONVERSATION_TITLE = "Jarvis — удалённые задачи"

    @Provides
    @Singleton
    fun provideJarvisTaskRunner(
        @ApplicationContext context: Context,
        chatManager: ChatManager,
        automationSettings: AutomationSettingsStore,
        traceStore: ToolExecutionTraceStore
    ): JarvisTaskRunner = RemoteAgentRunner(
        turns = RemoteTurnExecutor { prompt ->
            chatManager.sendMessageStream(
                prompt,
                origin = MessageOrigin.AUTOMATION,
                conversationId = CONVERSATION_ID,
                conversationTitle = CONVERSATION_TITLE,
                useMemory = false,
                includeHistory = false
            )
        },
        automationSettings = { automationSettings.current() },
        ownerBusy = traceStore.turnActive,
        originFactory = { RemoteToolOrigin(ownPackages = setOf(context.packageName, "io.androllm")) }
    )
}
