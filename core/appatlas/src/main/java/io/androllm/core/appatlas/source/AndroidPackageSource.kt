package io.androllm.core.appatlas.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.appatlas.model.PackageBasics
import io.androllm.core.appatlas.model.ParsedManifest
import io.androllm.core.appatlas.model.ParsedShortcut
import io.androllm.core.appatlas.parse.ManifestIntentParser
import io.androllm.core.appatlas.parse.ShortcutXmlParser
import io.androllm.core.appatlas.parse.ValueResolver
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * [PackageSource] over the real PackageManager.
 *
 * Package visibility: the app manifest already declares a `<queries>` entry
 * for MAIN/LAUNCHER, which makes EVERY launchable package visible — and
 * visibility is per package, so getPackageInfo, getResourcesForApplication,
 * the package's manifest/resources and queryIntentActivities(setPackage) all
 * work for those packages. QUERY_ALL_PACKAGES is therefore NOT requested: the
 * atlas only covers apps the agent can open anyway.
 *
 * Manifest reading: the target's binary AndroidManifest.xml is opened through
 * its AssetManager by cookie (public API `openXmlResourceParser(cookie, …)`);
 * the AssetManager also holds the framework, overlays and shared libraries, so
 * cookies are tried in order until the manifest whose `package` matches.
 */
@Singleton
class AndroidPackageSource @Inject constructor(
    @ApplicationContext private val context: Context
) : PackageSource {

    private val pm: PackageManager get() = context.packageManager

    override val ownPackage: String get() = context.packageName


    override fun launchablePackages(): List<PackageBasics> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val byPkg = LinkedHashMap<String, String>()
        runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val ai = ri.activityInfo ?: return@forEach
            byPkg.putIfAbsent(ai.packageName, ai.name)
        }
        return byPkg.mapNotNull { (pkg, activity) -> basicsOf(pkg, activity) }
    }

    override fun basics(packageName: String): PackageBasics? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val activity = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrNull()
            ?.firstOrNull()?.activityInfo?.name ?: return null
        return basicsOf(packageName, activity)
    }

    private fun basicsOf(pkg: String, activity: String): PackageBasics? {
        val info: PackageInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        val app: ApplicationInfo = info.applicationInfo ?: return null
        val label = runCatching { pm.getApplicationLabel(app).toString().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: pkg
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
        return PackageBasics(
            packageName = pkg,
            label = label,
            versionCode = versionCode,
            versionName = info.versionName,
            lastUpdateTime = info.lastUpdateTime,
            launcherActivity = activity,
            isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }

    private fun resources(pkg: String): Resources? =
        runCatching { pm.getResourcesForApplication(pkg) }.getOrNull()

    private fun resolver(res: Resources) = ValueResolver { raw ->
        when {
            raw == null -> null
            raw.startsWith("@") -> refId(raw)?.let { id -> runCatching { res.getString(id) }.getOrNull() }
            else -> raw
        }
    }

    override fun manifest(packageName: String): ParsedManifest? {
        val res = resources(packageName) ?: return null
        val assets = res.assets
        val resolver = resolver(res)
        for (cookie in 1..MAX_COOKIES) {
            val parser = try {
                assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
            } catch (_: Throwable) {
                continue
            }
            try {
                val parsed = ManifestIntentParser.parse(parser, resolver, expectedPackage = packageName)
                if (parsed != null) return parsed
            } catch (t: Throwable) {
                Timber.d("AppAtlas: manifest parse failed (cookie %d): %s", cookie, t.javaClass.simpleName)
            } finally {
                runCatching { parser.close() }
            }
        }
        return null
    }

    @SuppressLint("DiscouragedApi")
    override fun shortcuts(packageName: String, resourceRef: String?): List<ParsedShortcut> {
        val res = resources(packageName) ?: return emptyList()
        val id = resourceRef?.let(::refId) ?: launcherShortcutsResId(packageName) ?: return emptyList()
        return runCatching {
            val parser = res.getXml(id)
            try {
                ShortcutXmlParser.parse(parser, resolver(res))
            } finally {
                parser.close()
            }
        }.getOrDefault(emptyList())
    }

    private fun launcherShortcutsResId(pkg: String): Int? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return runCatching { pm.queryIntentActivities(launcher, PackageManager.GET_META_DATA) }.getOrNull()
            ?.firstNotNullOfOrNull { ri ->
                ri.activityInfo?.metaData?.getInt("android.app.shortcuts", 0)?.takeIf { it != 0 }
            }
    }

    override fun searchActivities(packageName: String): List<String> =
        runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH).setPackage(packageName), 0)
                .mapNotNull { it.activityInfo?.takeIf { ai -> ai.exported }?.name }
        }.getOrDefault(emptyList())

    @SuppressLint("DiscouragedApi")
    override fun vocabulary(packageName: String, candidates: List<String>): Pair<List<String>, List<String>> {
        val res = resources(packageName) ?: return emptyList<String>() to emptyList()
        val ids = ArrayList<String>()
        val strings = ArrayList<String>()
        for (name in candidates) {
            runCatching {
                if (res.getIdentifier(name, "id", packageName) != 0) ids += name
                val sid = res.getIdentifier(name, "string", packageName)
                if (sid != 0) strings += res.getString(sid)
            }
        }
        return ids to strings
    }

    override fun changedSince(sequence: Int): Pair<Int, List<String>>? = runCatching {
        val changed = pm.getChangedPackages(sequence) ?: return@runCatching null
        changed.sequenceNumber to changed.packageNames
    }.getOrNull()

    companion object {
        private const val MAX_COOKIES = 16

        /** "@2131951616" / "@0x7f0f0001" → id; "@xml/foo" (text XML) → null. */
        internal fun refId(raw: String): Int? {
            val body = raw.removePrefix("@").trim()
            return when {
                body.startsWith("0x", ignoreCase = true) -> body.substring(2).toLongOrNull(16)?.toInt()
                else -> body.toLongOrNull()?.toInt()
            }?.takeIf { it != 0 }
        }
    }
}
