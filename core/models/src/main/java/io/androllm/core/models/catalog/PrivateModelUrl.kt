package io.androllm.core.models.catalog

/**
 * Content-addressed URLs for the owner's PRIVATE models (branch F: the fine-tuned
 * Qwen3-4B "Jarvis" model). These files must never be published on Hugging Face,
 * so they are served by the owner's own box over Tailscale only.
 *
 * Catalog form (committed, public fork — contains no host):
 *   `tailnet://androllm-models/<64-hex sha256>/<file name>`
 *
 * The host comes from the git-ignored local.properties (`jarvis.modelsHost`, e.g.
 * `<tailscale ip>:8806`) via BuildConfig and is substituted only at download time:
 *   `http://<host>/<sha256>/<file name>`
 *
 * Plain HTTP is acceptable here because the transport is the WireGuard tunnel of
 * the tailnet (the server is bound to the Tailscale address and firewalled to
 * tailscale0), and the downloaded bytes are verified against the same sha256 that
 * is part of the URL. The sha in the path is also what makes the URL immutable:
 * [CatalogPinPolicy] accepts it only when it equals the entry's `sha256`.
 */
object PrivateModelUrl {

    const val SCHEME = "tailnet"
    private const val SERVICE = "androllm-models"

    private val PATTERN = Regex("^tailnet://$SERVICE/([0-9a-f]{64})/([A-Za-z0-9._-]+)$")
    private val HOST = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

    fun isPrivate(url: String): Boolean = url.startsWith("$SCHEME://")

    /** sha256 embedded in a well-formed private URL, else null. */
    fun sha256Of(url: String): String? = PATTERN.matchEntire(url)?.groupValues?.get(1)

    /**
     * Resolves a private URL against [host]; any other URL is returned unchanged.
     * Returns null for a malformed private URL or an invalid host, so callers
     * reject the download instead of guessing.
     */
    fun resolve(url: String, host: String): String? {
        if (!isPrivate(url)) return url
        val m = PATTERN.matchEntire(url) ?: return null
        val h = host.trim()
        if (!HOST.matches(h)) return null
        val (sha, file) = m.destructured
        return "http://$h/$sha/$file"
    }
}
