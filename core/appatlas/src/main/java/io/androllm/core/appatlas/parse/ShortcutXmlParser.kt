package io.androllm.core.appatlas.parse

import io.androllm.core.appatlas.model.ParsedShortcut
import io.androllm.core.appatlas.model.ShortcutIntent
import org.xmlpull.v1.XmlPullParser

/**
 * Parses a static-shortcuts resource (`<shortcuts><shortcut …><intent …>`),
 * the XML an app points to with `<meta-data android:name="android.app.shortcuts">`.
 * Labels are resolved through [ValueResolver] (they are usually @string refs).
 * Pure: no Android framework calls.
 */
object ShortcutXmlParser {

    private const val MAX_SHORTCUTS = 16
    private const val MAX_EXTRAS = 8

    fun parse(parser: XmlPullParser, resolver: ValueResolver): List<ParsedShortcut> {
        val out = mutableListOf<ParsedShortcut>()
        var id: String? = null
        var shortLabel: String? = null
        var longLabel: String? = null
        var enabled = true
        var intents = mutableListOf<ShortcutIntent>()

        var inIntent = false
        var iAction: String? = null
        var iPkg: String? = null
        var iCls: String? = null
        var iData: String? = null
        var iExtras = mutableMapOf<String, String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "shortcut" -> {
                        id = parser.androidAttr("shortcutId")
                        shortLabel = resolver.resolve(parser.androidAttr("shortcutShortLabel"))
                        longLabel = resolver.resolve(parser.androidAttr("shortcutLongLabel"))
                        enabled = parser.androidAttr("enabled")?.lowercase() != "false"
                        intents = mutableListOf()
                    }
                    "intent" -> if (id != null) {
                        inIntent = true
                        iAction = parser.androidAttr("action")
                        iPkg = parser.androidAttr("targetPackage")
                        iCls = parser.androidAttr("targetClass")
                        iData = parser.androidAttr("data")
                        iExtras = mutableMapOf()
                    }
                    "extra" -> if (inIntent && iExtras.size < MAX_EXTRAS) {
                        val k = parser.androidAttr("name")
                        val v = parser.androidAttr("value")
                        // References (non-literal values) cannot be reproduced safely — skip them.
                        if (!k.isNullOrBlank() && v != null && !v.startsWith("@")) iExtras[k] = v
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "intent" -> if (inIntent) {
                        inIntent = false
                        intents += ShortcutIntent(iAction, iPkg, iCls, iData, iExtras.toMap())
                    }
                    "shortcut" -> {
                        val sid = id
                        if (sid != null && out.size < MAX_SHORTCUTS && intents.isNotEmpty()) {
                            out += ParsedShortcut(
                                id = sid,
                                shortLabel = shortLabel?.trim()?.takeIf { it.isNotEmpty() },
                                longLabel = longLabel?.trim()?.takeIf { it.isNotEmpty() },
                                enabled = enabled,
                                intents = intents.toList()
                            )
                        }
                        id = null
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
