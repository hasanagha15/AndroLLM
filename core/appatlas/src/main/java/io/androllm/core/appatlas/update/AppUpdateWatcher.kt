package io.androllm.core.appatlas.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.appatlas.playbook.PlaybookRepository
import io.androllm.core.appatlas.source.PackageSource
import io.androllm.core.tools.remote.JarvisEventSink
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * Branch R1: what one [AppChange] does on the phone (pure, unit-tested):
 * - protected package (SensitiveAppGuard list, the owner's list, the atlas
 *   heuristic / flag) → only reported to Jarvis as `sensitive`: no stale
 *   marking, no relearn request, nothing else;
 * - otherwise, installed or updated → every playbook of the package becomes
 *   STALE(app_updated) for the new versionCode (the fast path stops at once)
 *   + one relearn request each; removed → nothing to mark;
 * - every change is published as `apps.changed` (when a link sink exists).
 * Logs carry the package name, kind and numbers only.
 */
class AppUpdateHandler(
    private val playbooks: PlaybookRepository,
    private val isSensitive: suspend (String) -> Boolean,
    private val sink: JarvisEventSink?
) {
    data class Outcome(val change: AppChange, val sensitive: Boolean, val playbooksMarked: Int)

    suspend fun handle(changes: List<AppChange>): List<Outcome> = changes.map { c ->
        val sensitive = runCatching { isSensitive(c.packageName) }.getOrDefault(true)
        val marked = if (!sensitive && c.newVersionCode != null) {
            runCatching { playbooks.onAppUpdated(c.packageName, c.newVersionCode) }.getOrDefault(0)
        } else {
            0
        }
        runCatching { sink?.publish(AppChange.EVENT_TYPE, c.toEventBody(sensitive)) }
            .onFailure { Timber.w("AppUpdate: event not queued (%s)", it.javaClass.simpleName) }
        Timber.i(
            "AppUpdate: %s %s v%d -> v%d, sensitive=%b, playbooks marked stale: %d",
            c.kind.wire, c.packageName, c.oldVersionCode ?: -1L, c.newVersionCode ?: -1L, sensitive, marked
        )
        Outcome(c, sensitive, marked)
    }
}

/** SharedPreferences `app_update_watcher` (excluded from backup: it names installed apps). */
class PrefsAppUpdateState(context: Context) : AppUpdateState {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var sequence: Int
        get() = runCatching { prefs.getInt(KEY_SEQ, -1) }.getOrDefault(-1)
        set(v) { runCatching { prefs.edit().putInt(KEY_SEQ, v).apply() } }

    override var bootCount: Int
        get() = runCatching { prefs.getInt(KEY_BOOT, -1) }.getOrDefault(-1)
        set(v) { runCatching { prefs.edit().putInt(KEY_BOOT, v).apply() } }

    override fun known(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString(KEY_KNOWN, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    override fun saveKnown(known: Map<String, Long>) {
        runCatching { prefs.edit().putString(KEY_KNOWN, JSONObject(known as Map<*, *>).toString()).apply() }
    }

    companion object {
        const val PREFS_NAME = "app_update_watcher"
        private const val KEY_SEQ = "sequence"
        private const val KEY_BOOT = "boot_count"
        private const val KEY_KNOWN = "known_versions"
    }
}

/** [InstalledApps] over PackageManager; "installed" = launchable, as in the atlas. */
class AndroidInstalledApps(private val context: Context, private val source: PackageSource) : InstalledApps {
    private val pm: PackageManager get() = context.packageManager

    override fun versionOf(packageName: String): Long? = runCatching { source.basics(packageName)?.versionCode }.getOrNull()

    override fun snapshot(): Map<String, Long> =
        runCatching { source.launchablePackages().associate { it.packageName to it.versionCode } }.getOrDefault(emptyMap())

    override fun changedSince(sequence: Int): Pair<Int, List<String>>? = runCatching {
        val changed = pm.getChangedPackages(sequence) ?: return@runCatching null
        changed.sequenceNumber to changed.packageNames
    }.getOrNull()

    override fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    override val ownPackage: String get() = context.packageName
}

/**
 * Branch R1 — L1 of the sync/relearn design: the phone notices an app
 * install / update / removal within seconds and reacts with zero network.
 *
 * 1. A runtime receiver for PACKAGE_ADDED / REPLACED / REMOVED (scheme
 *    `package`) while the process lives — manifest receivers do not get
 *    these implicit broadcasts for other apps on Android 8+.
 * 2. `PackageManager.getChangedPackages(seq)` with the persisted sequence at
 *    start and from a periodic WorkManager job every 15 minutes (catches what
 *    happened while the process was dead).
 * 3. Each change goes through [AppUpdateHandler]: stale-on-update for the
 *    package's playbooks, `apps.changed` to Jarvis, protected apps reported only.
 *
 * Off while the App Atlas is off (the tracker then forgets its baseline).
 */
@Singleton
class AppUpdateWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: PackageSource,
    private val playbooks: PlaybookRepository,
    private val atlas: AppAtlasRepository,
    private val sink: Optional<JarvisEventSink>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracker = AppUpdateTracker(AndroidInstalledApps(context, source), PrefsAppUpdateState(context))
    private val handler = AppUpdateHandler(playbooks, ::isSensitive, sink.orElse(null))
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        registerReceiver()
        schedulePeriodicCheck()
        scope.launch { checkNow() }
    }

    /** getChangedPackages since the persisted sequence (start-up + the 15-minute job). */
    suspend fun checkNow() {
        if (!atlas.isEnabled) {
            tracker.reset()
            return
        }
        runCatching { handler.handle(tracker.checkChanged()) }
            .onFailure { Timber.w("AppUpdate: check failed (%s)", it.javaClass.simpleName) }
    }

    private suspend fun onPackageEvent(pkg: String) {
        if (!atlas.isEnabled) return
        runCatching { handler.handle(tracker.onPackageEvent(pkg)) }
            .onFailure { Timber.w("AppUpdate: package event failed (%s)", it.javaClass.simpleName) }
    }

    private suspend fun isSensitive(pkg: String): Boolean {
        val entry = atlas.cachedEntry(pkg)
        if (entry?.sensitive == true) return true
        val label = runCatching { source.basics(pkg)?.label }.getOrNull() ?: entry?.label.orEmpty()
        return SensitiveClassifier.reason(pkg, label, playbooks.extras()) != null
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(context, Receiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Timber.w("AppUpdate: receiver not registered (%s)", it.javaClass.simpleName) }
    }

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            // An update arrives as REMOVED+ADDED (EXTRA_REPLACING) + REPLACED: act on REPLACED only.
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            val pending = goAsync()
            scope.launch {
                try {
                    onPackageEvent(pkg)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun schedulePeriodicCheck() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(CHECK_INTERVAL_MIN, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("AppUpdate: periodic check not scheduled (%s)", it.javaClass.simpleName) }
    }

    companion object {
        const val CHECK_WORK = "app_update_check"
        /** WorkManager's minimum period. */
        const val CHECK_INTERVAL_MIN = 15L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppUpdateEntryPoint {
    fun appUpdateWatcher(): AppUpdateWatcher
}

/** The 15-minute `getChangedPackages` check (plain CoroutineWorker + Hilt entry point, like the atlas sweep). */
class AppUpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, AppUpdateEntryPoint::class.java)
            .appUpdateWatcher()
            .checkNow()
        return Result.success()
    }
}
