package io.androllm.core.appatlas.index

import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.AtlasAction
import io.androllm.core.appatlas.model.PackageBasics
import io.androllm.core.appatlas.model.ParsedComponent
import io.androllm.core.appatlas.model.ParsedManifest
import io.androllm.core.appatlas.model.ParsedShortcut
import io.androllm.core.appatlas.source.PackageSource

/**
 * Turns what [PackageSource] can read about one app into a compact,
 * size-bounded [AppAtlasEntry]. Pure rules (unit-tested with a fake source):
 *
 * - "open" (launcher) is always present;
 * - "search" = an exported ACTION_SEARCH activity;
 * - "shortcut:<id>" = enabled static shortcuts (fireable only when the target
 *   activity is startable by a third party);
 * - "link:<n>" = ACTION_VIEW filters with a scheme on startable activities
 *   (custom schemes and http(s) app links with a host), deterministic order so
 *   ids stay stable for one app version;
 * - UI vocabulary = view ids / UI strings from a fixed candidate list that
 *   actually exist in the app's resources (bounded);
 * - sensitive apps: metadata + "open" only — no links, shortcuts, vocabulary.
 */
class AtlasIndexer(
    private val source: PackageSource,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun index(basics: PackageBasics): AppAtlasEntry {
        val label = AtlasText.clean(basics.label, MAX_TITLE) ?: basics.packageName.take(MAX_TITLE)
        val launch = AtlasAction(
            id = ACTION_OPEN,
            kind = ActionKind.LAUNCH,
            title = "Open $label".take(MAX_TITLE),
            component = basics.launcherActivity?.let { "${basics.packageName}/$it" }
        )
        fun metadataOnly(reason: String, manifestParsed: Boolean) = AppAtlasEntry(
            packageName = basics.packageName,
            label = label,
            versionCode = basics.versionCode,
            versionName = AtlasText.clean(basics.versionName, MAX_STRING),
            lastUpdateTime = basics.lastUpdateTime,
            sensitive = true,
            sensitiveReason = reason,
            manifestParsed = manifestParsed,
            actions = listOf(launch),
            indexedAt = clock()
        )
        SensitiveClassifier.reason(basics.packageName, basics.label)?.let { return metadataOnly(it, false) }

        val manifest = source.manifest(basics.packageName)
        // Gate B1: an app that handles a payment scheme (upi:, bitcoin:…) is a payment app,
        // whatever its name — metadata only, open only.
        paymentScheme(manifest)?.let { return metadataOnly("payment app (handles $it: links) — open only", true) }
        val actions = mutableListOf(launch)
        actions += searchAction(basics, manifest)
        actions += shortcutActions(basics, manifest)
        actions += deepLinkActions(basics, manifest)

        val (ids, strings) = source.vocabulary(basics.packageName, VOCAB_CANDIDATES)
        return AppAtlasEntry(
            packageName = basics.packageName,
            label = label,
            versionCode = basics.versionCode,
            versionName = AtlasText.clean(basics.versionName, MAX_STRING),
            lastUpdateTime = basics.lastUpdateTime,
            sensitive = false,
            manifestParsed = manifest != null,
            actions = actions.distinctBy { it.id }.take(MAX_ACTIONS),
            uiIds = boundedVocabulary(ids, idStyle = true),
            uiStrings = boundedVocabulary(strings, idStyle = false),
            indexedAt = clock()
        )
    }

    private fun paymentScheme(manifest: ParsedManifest?): String? =
        manifest?.components?.asSequence()
            ?.flatMap { it.filters.asSequence() }
            ?.flatMap { it.data.asSequence() }
            ?.mapNotNull { it.scheme }
            ?.firstOrNull { it in SchemePolicy.PAYMENT_SCHEMES }

    private fun searchAction(basics: PackageBasics, manifest: ParsedManifest?): List<AtlasAction> {
        val activity = if (manifest != null) {
            manifest.components.firstOrNull { c ->
                c.startableByOthers && c.filters.any { ACTION_SEARCH_INTENT in it.actions }
            }?.name
        } else {
            source.searchActivities(basics.packageName).firstOrNull()
        } ?: return emptyList()
        return listOf(
            AtlasAction(
                id = ACTION_SEARCH_ID,
                kind = ActionKind.SEARCH,
                title = "Search in ${AtlasText.clean(basics.label, MAX_TITLE) ?: basics.packageName}".take(MAX_TITLE),
                component = "${basics.packageName}/$activity"
            )
        )
    }

    private fun shortcutActions(basics: PackageBasics, manifest: ParsedManifest?): List<AtlasAction> {
        val ref = manifest?.let { m ->
            (m.components.firstOrNull { it.name == basics.launcherActivity && it.shortcutsResource != null }
                ?: m.components.firstOrNull { it.shortcutsResource != null })?.shortcutsResource
        }
        if (manifest != null && ref == null) return emptyList()
        val shortcuts = source.shortcuts(basics.packageName, ref)
        val byName: Map<String, ParsedComponent> = manifest?.components?.associateBy { it.name }.orEmpty()
        return shortcuts.asSequence()
            .filter { it.enabled }
            .mapNotNull { toShortcutAction(basics.packageName, it, manifest, byName) }
            .take(MAX_SHORTCUTS)
            .toList()
    }

    private fun toShortcutAction(
        pkg: String,
        s: ParsedShortcut,
        manifest: ParsedManifest?,
        byName: Map<String, ParsedComponent>
    ): AtlasAction? {
        val intent = s.intents.lastOrNull() ?: return null
        val targetPkg = intent.targetPackage ?: pkg
        if (targetPkg != pkg) return null // never jump into another app from this entry
        // Gate B1: no call / send / delete / install shortcuts, no tel:/sms:/mailto:/upi: data.
        if (s.intents.any { SchemePolicy.isBlockedShortcutAction(it.action) || SchemePolicy.isBlockedUri(it.data) }) return null
        val data = intent.data?.let { AtlasText.cleanUri(it, MAX_URI) ?: return null }
        val cls = intent.targetClass?.let { if (it.startsWith(".")) pkg + it else it }
        val fireable = when {
            cls == null -> intent.action != null // implicit intent, resolved inside the package
            manifest == null -> true // unknown — try, the launch result is reported honestly
            else -> byName[cls]?.startableByOthers == true
        }
        val sid = s.id.filter { it.isLetterOrDigit() || it in "_.-" }.take(40).ifEmpty { return null }
        return AtlasAction(
            id = "shortcut:$sid",
            kind = ActionKind.SHORTCUT,
            title = AtlasText.clean(s.shortLabel ?: s.longLabel, MAX_TITLE) ?: sid,
            component = cls?.let { "$pkg/$it" },
            intentAction = intent.action,
            data = data,
            extras = intent.extras.mapNotNull { (k, v) ->
                val ck = AtlasText.clean(k, MAX_STRING) ?: return@mapNotNull null
                ck to (AtlasText.clean(v, MAX_URI) ?: return@mapNotNull null)
            }.toMap(),
            fireable = fireable
        )
    }

    private fun deepLinkActions(basics: PackageBasics, manifest: ParsedManifest?): List<AtlasAction> {
        if (manifest == null) return emptyList()
        data class Cand(val uri: String, val scheme: String, val host: String?, val prefix: String?, val title: String, val score: Int)
        val cands = mutableListOf<Cand>()
        for (c in manifest.components) {
            if (!c.startableByOthers) continue
            for (f in c.filters) {
                if (ACTION_VIEW !in f.actions) continue
                val schemes = f.data.mapNotNull { it.scheme }.distinct().filter { !SchemePolicy.isBlockedScheme(it) }
                if (schemes.isEmpty()) continue
                val hosts: List<String?> = f.data.mapNotNull { it.host }.distinct().filter { it != "*" }.ifEmpty { listOf(null) }
                val paths: List<String?> = f.data.mapNotNull { d ->
                    d.path ?: d.pathPrefix ?: d.pathPattern?.let(::literalPrefix)
                }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(null) }
                val browsable = CATEGORY_BROWSABLE in f.categories
                for (s in schemes.take(3)) for (h in hosts.take(4)) for (p in paths.take(3)) {
                    if (s in WEB_SCHEMES && h == null) continue
                    val uri = AtlasText.cleanUri(buildUri(s, h, p), MAX_URI) ?: continue
                    var score = 0
                    if (browsable) score += 2
                    if (s !in WEB_SCHEMES) score += 1
                    if (p != null) score += 1
                    val where = listOfNotNull(h, p).joinToString("").ifEmpty { "$s:" }
                    val title = (AtlasText.clean(c.label, MAX_TITLE) ?: humanize(c.name)) + " (" + (AtlasText.clean(where, MAX_TITLE) ?: s) + ")"
                    cands += Cand(uri, s, h, p, title.take(MAX_TITLE), score)
                }
            }
        }
        return cands
            .distinctBy { it.uri }
            .sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.uri })
            .take(MAX_DEEP_LINKS)
            .mapIndexed { i, c ->
                AtlasAction(
                    id = "link:${i + 1}",
                    kind = ActionKind.DEEP_LINK,
                    title = c.title,
                    uri = c.uri,
                    scheme = c.scheme,
                    host = c.host,
                    pathPrefix = c.prefix
                )
            }
    }

    companion object {
        const val ACTION_OPEN = "open"
        const val ACTION_SEARCH_ID = "search"

        const val MAX_ACTIONS = 24
        const val MAX_DEEP_LINKS = 12
        const val MAX_SHORTCUTS = 8
        const val MAX_UI_IDS = 32
        const val MAX_UI_STRINGS = 32
        const val MAX_STRING = 40
        const val MAX_TITLE = 60
        const val MAX_URI = 200

        private const val ACTION_VIEW = "android.intent.action.VIEW"
        private const val ACTION_SEARCH_INTENT = "android.intent.action.SEARCH"
        private const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        private val WEB_SCHEMES = setOf("http", "https")

        /**
         * Candidate resource entry names probed in every app (as `id` and as
         * `string`). Action-relevant words only; bounded, so probing ~400 apps
         * stays a few seconds of background work.
         */
        val VOCAB_CANDIDATES: List<String> = listOf(
            "search", "action_search", "menu_search", "search_button", "search_bar", "search_box",
            "search_edit_text", "search_src_text", "search_hint", "search_view",
            "send", "action_send", "send_button", "btn_send", "compose", "fab_compose", "new_message",
            "new_chat", "message", "messages", "chat", "reply", "forward",
            "settings", "action_settings", "menu_settings", "title_settings", "preferences",
            "play", "pause", "play_pause", "next", "previous", "shuffle", "repeat", "player",
            "share", "action_share", "save", "done", "ok", "cancel", "confirm", "submit", "delete",
            "edit", "add", "create", "new", "upload", "download", "refresh", "filter", "sort",
            "profile", "account", "home", "library", "favorites", "history", "notifications", "inbox",
            "cart", "checkout", "order", "orders", "call", "camera", "record", "scan", "qr",
            "translate", "navigate", "directions", "route", "map", "subscribe", "like", "comment",
            "post", "back", "close", "menu", "more", "help", "login", "sign_in", "logout"
        )

        internal fun literalPrefix(pattern: String): String {
            val cut = listOf(".*", "*", "\\", ".+").mapNotNull { m -> pattern.indexOf(m).takeIf { it >= 0 } }.minOrNull()
            return if (cut == null) pattern else pattern.substring(0, cut)
        }

        internal fun buildUri(scheme: String, host: String?, path: String?): String = when {
            host != null -> buildString {
                append(scheme).append("://").append(host)
                if (path != null) append(if (path.startsWith("/")) path else "/$path")
            }
            path != null -> "$scheme:$path"
            else -> "$scheme:"
        }

        internal fun humanize(className: String): String {
            val simple = className.substringAfterLast('.')
                .removeSuffix("Activity").removeSuffix("Alias")
                .ifEmpty { className.substringAfterLast('.') }
            return simple.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
                .replaceFirstChar { it.uppercase() }
        }

        /** Distinct, single-line, 2..[MAX_STRING] chars, at most 32 entries. */
        internal fun boundedVocabulary(items: List<String>, idStyle: Boolean): List<String> {
            val seen = HashSet<String>()
            val out = ArrayList<String>()
            for (raw in items) {
                // Gate M1: third-party text — control chars / line breaks / markup removed.
                val s = AtlasText.clean(raw, MAX_STRING + 1) ?: continue
                if (s.length < 2 || s.length > MAX_STRING) continue
                if (idStyle && !s.all { it.isLetterOrDigit() || it == '_' }) continue
                if (seen.add(s.lowercase())) out += s
                if (out.size >= (if (idStyle) MAX_UI_IDS else MAX_UI_STRINGS)) break
            }
            return out
        }
    }
}
