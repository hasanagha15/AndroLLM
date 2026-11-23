package io.androllm.core.appatlas.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.learn.LearnedPathRecorder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the atlas fresh without user action:
 * 1. a runtime receiver for PACKAGE_ADDED / REPLACED / REMOVED while the
 *    process lives (manifest receivers cannot get these implicit broadcasts
 *    on Android 8+), debounced into one incremental sync;
 * 2. an incremental sync shortly after start-up (PackageManager change token
 *    + versionCode compare catches everything that changed while we were dead);
 * 3. a weekly WorkManager sweep (battery not low).
 * Called once from the Application.
 */
@Singleton
class AppAtlasStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppAtlasRepository,
    private val recorder: LearnedPathRecorder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashSet<String>()
    private var debounceJob: Job? = null
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        recorder.attach()
        registerPackageReceiver()
        scheduleWeeklySweep()
        scope.launch {
            delay(STARTUP_DELAY_MS) // stay off the cold-start critical path
            repository.sync(AppAtlasRepository.Mode.INCREMENTAL)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(context, PackageReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Timber.w("AppAtlas: package receiver not registered: %s", it.message) }
    }

    private inner class PackageReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            onPackageChanged(pkg)
        }
    }

    private fun onPackageChanged(pkg: String) {
        synchronized(pending) {
            pending += pkg
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                val batch = synchronized(pending) { pending.toSet().also { pending.clear() } }
                repository.sync(AppAtlasRepository.Mode.INCREMENTAL, only = batch)
            }
        }
    }

    private fun scheduleWeeklySweep() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<AppAtlasSweepWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(SWEEP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("AppAtlas: weekly sweep not scheduled: %s", it.message) }
    }

    companion object {
        const val SWEEP_WORK = "app_atlas_weekly_sweep"
        private const val STARTUP_DELAY_MS = 30_000L
        private const val DEBOUNCE_MS = 5_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppAtlasEntryPoint {
    fun repository(): AppAtlasRepository
}

/**
 * Weekly sweep. A plain CoroutineWorker (no assisted injection): the app's
 * HiltWorkerFactory falls back to the default factory for it, and the
 * repository comes from a Hilt entry point.
 */
class AppAtlasSweepWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, AppAtlasEntryPoint::class.java).repository()
        repo.sync(AppAtlasRepository.Mode.SWEEP)
        return Result.success()
    }
}
