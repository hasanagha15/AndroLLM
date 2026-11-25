package io.androllm.core.appatlas.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.appatlas.source.AndroidPackageSource
import io.androllm.core.appatlas.source.PackageSource
import io.androllm.core.appatlas.store.AtlasDatabase
import io.androllm.core.appatlas.store.AtlasMigrations
import io.androllm.core.appatlas.store.AtlasStore
import io.androllm.core.appatlas.store.PlaybookStore
import io.androllm.core.appatlas.store.RoomPlaybookStore
import io.androllm.core.appatlas.store.RoomAtlasStore
import io.androllm.core.appatlas.tools.AppAtlasSearchTool
import io.androllm.core.appatlas.tools.AppOpenActionTool
import io.androllm.core.appatlas.playbook.PlaybookListTool
import io.androllm.core.appatlas.playbook.PlaybookRunTool
import io.androllm.core.appatlas.playbook.PlaybookSyncHandler
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import java.io.File
import javax.inject.Singleton

/**
 * Branch L: registers the atlas tools into the shared `Set<Tool>` (they show
 * up in Settings → Automation under "App Launcher", same toggle as
 * launch_app) and provides the on-device store.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppAtlasModule {

    @Binds @IntoSet abstract fun bindAtlasSearch(tool: AppAtlasSearchTool): Tool
    @Binds @IntoSet abstract fun bindOpenAction(tool: AppOpenActionTool): Tool
    // Branch N: app playbooks.
    @Binds @IntoSet abstract fun bindPlaybookList(tool: PlaybookListTool): Tool
    @Binds @IntoSet abstract fun bindPlaybookRun(tool: PlaybookRunTool): Tool
    @Binds @IntoSet abstract fun bindPlaybookSync(handler: PlaybookSyncHandler): RemoteDataTaskHandler

    @Binds abstract fun bindPackageSource(source: AndroidPackageSource): PackageSource

    companion object {
        @Provides
        @Singleton
        fun provideAtlasDatabase(@ApplicationContext context: Context): AtlasDatabase =
            Room.databaseBuilder(
                context,
                AtlasDatabase::class.java,
                // noBackupFilesDir: never in cloud backup or device-to-device transfer.
                File(context.noBackupFilesDir, AtlasDatabase.FILE_NAME).absolutePath
            )
                // Branch N: v2 → v3 keeps the atlas and learned paths; older versions are rebuilt.
                .addMigrations(AtlasMigrations.MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        @Singleton
        fun provideAtlasStore(db: AtlasDatabase): AtlasStore = RoomAtlasStore(db.dao())

        @Provides
        @Singleton
        fun providePlaybookStore(db: AtlasDatabase): PlaybookStore = RoomPlaybookStore(db.playbookDao())
    }
}
