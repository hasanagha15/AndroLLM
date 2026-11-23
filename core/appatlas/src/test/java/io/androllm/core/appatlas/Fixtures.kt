package io.androllm.core.appatlas

import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.model.PackageBasics
import io.androllm.core.appatlas.model.ParsedManifest
import io.androllm.core.appatlas.model.ParsedShortcut
import io.androllm.core.appatlas.parse.ValueResolver
import io.androllm.core.appatlas.source.PackageSource
import io.androllm.core.appatlas.store.AtlasStore
import io.androllm.core.appatlas.store.StoredVersion
import java.io.StringReader
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** Synthetic test data only — no real device data. */
object Fixtures {

    fun parser(xml: String): XmlPullParser = KXmlParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }

    /** "@string/x" → strings[x]; literals unchanged. */
    fun resolver(strings: Map<String, String>) = ValueResolver { raw ->
        when {
            raw == null -> null
            raw.startsWith("@string/") -> strings[raw.removePrefix("@string/")]
            raw.startsWith("@") -> null
            else -> raw
        }
    }

    const val VIDEO_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.video" android:versionCode="420">
  <application android:label="@string/app_name">
    <activity android:name=".MainActivity" android:exported="true" android:label="@string/app_name">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
      <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />
    </activity>
    <activity android:name="com.example.video.ui.SearchActivity" android:label="@string/search">
      <intent-filter>
        <action android:name="android.intent.action.SEARCH" />
      </intent-filter>
      <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" />
        <data android:host="video.example.com" />
        <data android:pathPrefix="/results" />
      </intent-filter>
    </activity>
    <activity android:name=".WatchActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="examplevideo" android:host="watch" />
        <data android:pathPattern="/v/.*" />
      </intent-filter>
    </activity>
    <activity android:name=".InternalActivity" android:exported="false">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="internal-secret" />
      </intent-filter>
    </activity>
    <activity android:name=".ProtectedActivity" android:exported="true" android:permission="com.example.video.PRIVATE">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="protectedlink" />
      </intent-filter>
    </activity>
    <activity android:name=".ImageViewer" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <data android:mimeType="image/*" />
      </intent-filter>
    </activity>
    <activity android:name=".UploadActivity" android:exported="false" />
    <activity-alias android:name=".Launcher2" android:targetActivity=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="examplevideo" android:host="home" />
      </intent-filter>
    </activity-alias>
  </application>
</manifest>"""

    const val VIDEO_SHORTCUTS = """<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
  <shortcut android:shortcutId="search" android:enabled="true" android:shortcutShortLabel="@string/search">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.video" android:targetClass="com.example.video.ui.SearchActivity" />
  </shortcut>
  <shortcut android:shortcutId="upload" android:shortcutShortLabel="@string/upload">
    <intent android:action="android.intent.action.MAIN" android:targetPackage="com.example.video" android:targetClass="com.example.video.MainActivity" />
    <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.video" android:targetClass="com.example.video.UploadActivity">
      <extra android:name="mode" android:value="new" />
      <extra android:name="ref" android:value="@string/upload" />
    </intent>
  </shortcut>
  <shortcut android:shortcutId="disabled_one" android:enabled="false" android:shortcutShortLabel="Old">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.video" android:targetClass="com.example.video.MainActivity" />
  </shortcut>
  <shortcut android:shortcutId="other_app" android:shortcutShortLabel="Elsewhere">
    <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.other" android:targetClass="com.example.other.Main" />
  </shortcut>
</shortcuts>"""

    val STRINGS = mapOf("app_name" to "Example Video", "search" to "Search", "upload" to "Upload")

    fun basics(pkg: String, label: String, version: Long = 1, updated: Long = version * 1000) =
        PackageBasics(pkg, label, version, "v$version", updated, "$pkg.MainActivity")
}

/** In-memory [PackageSource] for indexer / synchronizer tests. */
class FakePackageSource(
    var packages: MutableList<PackageBasics> = mutableListOf(),
    val manifests: MutableMap<String, ParsedManifest> = mutableMapOf(),
    val shortcutsByPkg: MutableMap<String, List<ParsedShortcut>> = mutableMapOf(),
    val vocabularyByPkg: MutableMap<String, Pair<List<String>, List<String>>> = mutableMapOf(),
    val searchByPkg: MutableMap<String, List<String>> = mutableMapOf()
) : PackageSource {
    var manifestCalls = 0
    override val ownPackage: String = "io.androllm.app"
    override fun launchablePackages() = packages.toList()
    override fun basics(packageName: String) = packages.firstOrNull { it.packageName == packageName }
    override fun manifest(packageName: String): ParsedManifest? { manifestCalls++; return manifests[packageName] }
    override fun shortcuts(packageName: String, resourceRef: String?) = shortcutsByPkg[packageName].orEmpty()
    override fun searchActivities(packageName: String) = searchByPkg[packageName].orEmpty()
    override fun vocabulary(packageName: String, candidates: List<String>) =
        vocabularyByPkg[packageName] ?: (emptyList<String>() to emptyList())
    override fun changedSince(sequence: Int): Pair<Int, List<String>>? = null
    var fingerprint = "rules-1"
    override fun sensitivityFingerprint() = fingerprint
}

class InMemoryAtlasStore : AtlasStore {
    val apps = linkedMapOf<String, AppAtlasEntry>()
    val paths = mutableListOf<LearnedPath>()
    override suspend fun all() = apps.values.toList()
    override suspend fun get(packageName: String) = apps[packageName]
    override suspend fun upsert(entry: AppAtlasEntry) { apps[entry.packageName] = entry }
    override suspend fun remove(packageNames: Collection<String>) {
        packageNames.forEach { apps.remove(it) }
        paths.removeAll { it.packageName in packageNames }
    }
    override suspend fun versions() = apps.mapValues { StoredVersion(it.value.versionCode, it.value.lastUpdateTime) }
    override suspend fun paths(packageName: String) = paths.filter { it.packageName == packageName }
    override suspend fun allPaths() = paths.toList()
    override suspend fun upsertPath(path: LearnedPath) {
        paths.removeAll { it.packageName == path.packageName && it.taskKey == path.taskKey }
        paths += path
    }
    override suspend fun replacePaths(packageName: String, paths: List<LearnedPath>) {
        this.paths.removeAll { it.packageName == packageName }
        this.paths += paths
    }
    override suspend fun clearPaths() = paths.clear()
}
