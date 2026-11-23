package io.androllm.core.appatlas.source

import io.androllm.core.appatlas.model.PackageBasics
import io.androllm.core.appatlas.model.ParsedManifest
import io.androllm.core.appatlas.model.ParsedShortcut

/**
 * Everything the indexer needs from PackageManager / the target's Resources,
 * behind an interface so the indexing rules are unit-tested with fake data.
 * Implementations must never throw — a failure is "unknown" (null / empty).
 */
interface PackageSource {

    /** Every app with a LAUNCHER activity (one entry per package). */
    fun launchablePackages(): List<PackageBasics>

    /** Basics of one package, or null when it is gone / not launchable. */
    fun basics(packageName: String): PackageBasics?

    /** The parsed AndroidManifest.xml of [packageName], or null when unreadable. */
    fun manifest(packageName: String): ParsedManifest?

    /**
     * Static shortcuts of [packageName]. [resourceRef] is the raw meta-data
     * resource from the manifest when known; null = look it up from the
     * launcher activity's meta-data.
     */
    fun shortcuts(packageName: String, resourceRef: String?): List<ParsedShortcut>

    /** Activities that handle ACTION_SEARCH (fallback when the manifest is unreadable). */
    fun searchActivities(packageName: String): List<String>

    /**
     * Probes the target's resources for [candidates] entry names.
     * Returns (view ids that exist, resolved UI strings for string entries that exist).
     */
    fun vocabulary(packageName: String, candidates: List<String>): Pair<List<String>, List<String>>

    /**
     * PackageManager change token: packages changed since [sequence], plus the
     * new sequence number. Null when unsupported / the token is unusable.
     */
    fun changedSince(sequence: Int): Pair<Int, List<String>>?

    /**
     * Reloads the owner's protected-app list and returns a fingerprint of all
     * sensitivity inputs (rules version + extras). A change forces a full
     * re-index (gate B2). Called on a background thread.
     */
    fun sensitivityFingerprint(): String

    /** Package name of this app — never indexed. */
    val ownPackage: String
}
