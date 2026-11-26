package io.androllm.core.appatlas.tools

/**
 * Branch U (step 5): `app_open_action(package="Settings")` failed with "not in
 * the App Atlas" — the model passed the app's LABEL. Resolves a label (any
 * case, also Russian / Azerbaijani names of the system Settings app) to the
 * atlas package. Returns null when nothing matches.
 */
object AppLabelResolver {

    const val SETTINGS_PACKAGE = "com.android.settings"

    /** Names of the system Settings app (lowercase). */
    private val SETTINGS_ALIASES = setOf(
        "settings", "system settings", "android settings", "настройки", "параметры",
        "ayarlar", "parametrlər", "tənzimləmələr"
    )

    /**
     * Branch U it.2 (B2): the ACTION_SETTINGS fallback is allowed only for the
     * system Settings package, the plain "open" action, and only when the owner
     * did not exclude it from the App Atlas.
     */
    fun settingsFallbackAllowed(resolvedPkg: String, actionId: String, excluded: Set<String>): Boolean =
        resolvedPkg == SETTINGS_PACKAGE && actionId.equals("open", ignoreCase = true) &&
            excluded.none { it.trim().equals(SETTINGS_PACKAGE, ignoreCase = true) }

    fun resolve(input: String, labelsToPackages: List<Pair<String, String>>): String? {
        val key = normalize(input)
        if (key.isEmpty()) return null
        labelsToPackages.firstOrNull { (_, pkg) -> pkg.equals(input.trim(), ignoreCase = true) }?.let { return it.second }
        val byLabel = labelsToPackages.filter { (label, _) -> normalize(label) == key }
        if (byLabel.size == 1) return byLabel.single().second
        if (key in SETTINGS_ALIASES) {
            // Several apps may be labelled "Settings" (vendor apps): the system one wins.
            return byLabel.firstOrNull { it.second == SETTINGS_PACKAGE }?.second ?: SETTINGS_PACKAGE
        }
        return byLabel.firstOrNull()?.second
    }

    private fun normalize(s: String): String = s.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }.joinToString(" ")
}
