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

    /** Branch N: typed data task handlers (may be empty). */
    @dagger.multibindings.Multibinds
    abstract fun dataTaskHandlers(): Set<io.androllm.core.tools.remote.RemoteDataTaskHandler>
}
