package io.androllm.core.appatlas.parse

import io.androllm.core.appatlas.model.DataSpec
import io.androllm.core.appatlas.model.ParsedComponent
import io.androllm.core.appatlas.model.ParsedFilter
import io.androllm.core.appatlas.model.ParsedManifest
import org.xmlpull.v1.XmlPullParser

/** Resolves an attribute value that may be a resource reference ("@2131..." / "@string/x"). */
fun interface ValueResolver {
    /** Returns the literal value, the resolved string for a reference, or null. */
    fun resolve(raw: String?): String?

    companion object {
        /** Keeps literals, drops references (used when no Resources are available). */
        val LITERAL_ONLY = ValueResolver { raw -> raw?.takeUnless { it.startsWith("@") } }
    }
}

const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Reads an android:-namespaced attribute from either a binary (compiled) or a text XML parser. */
internal fun XmlPullParser.androidAttr(name: String): String? =
    getAttributeValue(ANDROID_NS, name)
        ?: getAttributeValue(null, "android:$name")

/**
 * Parses the activities, activity-aliases, intent filters and shortcut
 * meta-data out of an app's AndroidManifest.xml. Works on the compiled binary
 * manifest (AssetManager.openXmlResourceParser) and on plain-text XML (unit
 * tests). Pure: no Android framework calls.
 *
 * Returns null when the document is not the manifest of [expectedPackage]
 * (the AssetManager of an app also holds the framework, overlays and shared
 * libraries — each with its own AndroidManifest.xml).
 */
object ManifestIntentParser {

    private const val MAX_COMPONENTS = 400
    private const val MAX_FILTERS_PER_COMPONENT = 12
    private const val MAX_DATA_PER_FILTER = 24

    fun parse(parser: XmlPullParser, resolver: ValueResolver, expectedPackage: String?): ParsedManifest? {
        var packageName: String? = null
        var versionCode: Long? = null
        val components = mutableListOf<ParsedComponent>()

        // Current component state.
        var compName: String? = null
        var compLabel: String? = null
        var compExported: String? = null
        var compEnabled = true
        var compPermission: String? = null
        var compShortcuts: String? = null
        var compAlias = false
        var compDepth = -1
        var filters = mutableListOf<ParsedFilter>()

        // Current filter state.
        var inFilter = false
        var fActions = mutableSetOf<String>()
        var fCategories = mutableSetOf<String>()
        var fData = mutableListOf<DataSpec>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "manifest" -> {
                        packageName = parser.getAttributeValue(null, "package")
                        if (expectedPackage != null && packageName != expectedPackage) return null
                        versionCode = parser.androidAttr("versionCode")?.toLongOrNull()
                    }
                    "activity", "activity-alias" -> if (packageName != null && components.size < MAX_COMPONENTS) {
                        val alias = parser.name == "activity-alias"
                        compName = resolveClass(packageName, parser.androidAttr("name"))
                        compLabel = resolver.resolve(parser.androidAttr("label"))?.trim()?.takeIf { it.isNotEmpty() }
                        compExported = parser.androidAttr("exported")
                        compEnabled = parser.androidAttr("enabled")?.lowercase() != "false"
                        compPermission = parser.androidAttr("permission")?.takeIf { it.isNotBlank() }
                        compShortcuts = null
                        compAlias = alias
                        compDepth = parser.depth
                        filters = mutableListOf()
                    }
                    "intent-filter" -> if (compName != null) {
                        inFilter = true
                        fActions = mutableSetOf()
                        fCategories = mutableSetOf()
                        fData = mutableListOf()
                    }
                    "action" -> if (inFilter) parser.androidAttr("name")?.let { fActions += it }
                    "category" -> if (inFilter) parser.androidAttr("name")?.let { fCategories += it }
                    "data" -> if (inFilter && fData.size < MAX_DATA_PER_FILTER) {
                        fData += DataSpec(
                            scheme = parser.androidAttr("scheme")?.lowercase(),
                            host = parser.androidAttr("host")?.lowercase(),
                            port = parser.androidAttr("port"),
                            path = parser.androidAttr("path"),
                            pathPrefix = parser.androidAttr("pathPrefix"),
                            pathPattern = parser.androidAttr("pathPattern"),
                            mimeType = parser.androidAttr("mimeType")
                        )
                    }
                    "meta-data" -> if (compName != null && !inFilter &&
                        parser.androidAttr("name") == "android.app.shortcuts"
                    ) {
                        compShortcuts = parser.androidAttr("resource")
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "intent-filter" -> if (inFilter) {
                        inFilter = false
                        if (filters.size < MAX_FILTERS_PER_COMPONENT && fActions.isNotEmpty()) {
                            filters += ParsedFilter(fActions, fCategories, fData)
                        }
                    }
                    "activity", "activity-alias" -> if (compName != null && parser.depth == compDepth) {
                        val exported = when (compExported?.lowercase()) {
                            "true" -> true
                            "false" -> false
                            // Pre-Android-12 default: exported iff it has an intent filter.
                            else -> filters.isNotEmpty()
                        }
                        components += ParsedComponent(
                            name = compName!!,
                            label = compLabel,
                            exported = exported,
                            enabled = compEnabled,
                            permission = compPermission,
                            filters = filters.toList(),
                            shortcutsResource = compShortcuts,
                            isAlias = compAlias
                        )
                        compName = null
                        compDepth = -1
                    }
                }
            }
            event = parser.next()
        }
        val pkg = packageName ?: return null
        return ParsedManifest(pkg, versionCode, components)
    }

    /** ".Main" → "pkg.Main"; "Main" (no dot) → "pkg.Main"; FQCN unchanged. */
    internal fun resolveClass(pkg: String, raw: String?): String? {
        val n = raw?.trim().orEmpty()
        if (n.isEmpty()) return null
        return when {
            n.startsWith(".") -> pkg + n
            '.' !in n -> "$pkg.$n"
            else -> n
        }
    }
}
