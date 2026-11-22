package io.androllm.core.jarvistools.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.jarvistools.confirm.AppSideEffectGate
import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.jarvistools.glasses.GlassesDisplay
import io.androllm.core.jarvistools.glasses.GlassesShowTextTool
import io.androllm.core.jarvistools.glasses.NotificationMirrorGlassesDisplay
import io.androllm.core.jarvistools.net.HttpTransport
import io.androllm.core.jarvistools.net.OkHttpTransport
import io.androllm.core.jarvistools.secrets.KeystoreSecretStore
import io.androllm.core.jarvistools.secrets.SecretStore
import io.androllm.core.jarvistools.settings.JarvisEndpoints
import io.androllm.core.jarvistools.settings.JarvisToolsConfig
import io.androllm.core.jarvistools.tesla.TeslaCommandTool
import io.androllm.core.jarvistools.tesla.TeslaStatusTool
import io.androllm.core.jarvistools.whatsapp.WhatsAppInboxTool
import io.androllm.core.jarvistools.whatsapp.WhatsAppPendingTool
import io.androllm.core.jarvistools.whatsapp.WhatsAppReplyTool
import io.androllm.core.jarvistools.whatsapp.WhatsAppStatusTool
import io.androllm.core.tools.api.Tool

/**
 * Registers the Jarvis tools into the same `Set<Tool>` multibinding that
 * fills [io.androllm.core.tools.registry.ToolRegistry] (same pattern as
 * AccessibilityToolsModule) — the planner and Settings → Automation see them
 * like every built-in tool.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JarvisToolsModule {

    // Tesla (tesla-bridge, https://tesla.lilab.ai)
    @Binds @IntoSet abstract fun bindTeslaStatus(tool: TeslaStatusTool): Tool
    @Binds @IntoSet abstract fun bindTeslaCommand(tool: TeslaCommandTool): Tool

    // WhatsApp bot (wa-bot on pi-vision, LAN)
    @Binds @IntoSet abstract fun bindWhatsAppStatus(tool: WhatsAppStatusTool): Tool
    @Binds @IntoSet abstract fun bindWhatsAppPending(tool: WhatsAppPendingTool): Tool
    @Binds @IntoSet abstract fun bindWhatsAppInbox(tool: WhatsAppInboxTool): Tool
    @Binds @IntoSet abstract fun bindWhatsAppReply(tool: WhatsAppReplyTool): Tool

    // Even G2 glasses
    @Binds @IntoSet abstract fun bindGlassesShowText(tool: GlassesShowTextTool): Tool

    @Binds abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun bindHttpTransport(impl: OkHttpTransport): HttpTransport
    @Binds abstract fun bindEndpoints(impl: JarvisToolsConfig): JarvisEndpoints
    @Binds abstract fun bindSideEffectGate(impl: AppSideEffectGate): SideEffectGate
    @Binds abstract fun bindGlassesDisplay(impl: NotificationMirrorGlassesDisplay): GlassesDisplay
}
