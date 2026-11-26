package io.androllm.core.jarvislink.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.core.jarvislink.confirm.ConfirmationNotifier
import io.androllm.core.jarvislink.confirm.NotificationConfirmationNotifier
import javax.inject.Singleton

/**
 * DI for the Jarvis link. `JarvisTaskRunner` is bound in the app module
 * (JarvisLinkHook) because it needs the chat pipeline from feature:voice.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JarvisLinkModule {
    @Binds
    @Singleton
    abstract fun bindConfirmationNotifier(impl: NotificationConfirmationNotifier): ConfirmationNotifier

    /** Branch R1: phone → Jarvis events (`apps.changed`) through the persisted outbox. */
    @Binds
    abstract fun bindEventSink(impl: io.androllm.core.jarvislink.events.JarvisEventOutbox): io.androllm.core.tools.remote.JarvisEventSink

    /** Branch N: typed data task handlers (may be empty). */
    @dagger.multibindings.Multibinds
    abstract fun dataTaskHandlers(): Set<io.androllm.core.tools.remote.RemoteDataTaskHandler>

    /** Branch V: `appfunction.confirm` - tap-only owner approval for an outward AppFunction run by the Acer bridge. */
    @Binds
    @dagger.multibindings.IntoSet
    abstract fun bindAppFunctionConfirm(
        impl: io.androllm.core.jarvislink.confirm.AppFunctionConfirmHandler
    ): io.androllm.core.tools.remote.RemoteDataTaskHandler

    /** Branch V (gate B1): approvals are signed by a non-exportable Android Keystore key. */
    @Binds
    @Singleton
    abstract fun bindConfirmSigner(
        impl: io.androllm.core.jarvislink.confirm.KeystoreConfirmSigner
    ): io.androllm.core.jarvislink.confirm.ConfirmSigner
}
