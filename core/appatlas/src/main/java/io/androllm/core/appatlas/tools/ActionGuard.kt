package io.androllm.core.appatlas.tools

import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.AtlasAction
import java.net.URI

/**
 * Pure checks before `app_open_action` fires anything (unit-tested):
 * - sensitive apps (banking/payment, Tesla, WhatsApp…) → only "open";
 * - non-fireable actions are refused with an explanation;
 * - a model-supplied URI must keep the action's scheme and host and may not
 *   use transport schemes (intent:, file:, content:, javascript:…), so a
 *   prompt-injected link can never be redirected elsewhere;
 * - query text is bounded.
 */
object ActionGuard {

    const val MAX_URI_CHARS = 1_000
    const val MAX_QUERY_CHARS = 300
    private val FORBIDDEN_SCHEMES = setOf("intent", "file", "content", "javascript", "data", "android-app", "jar")

    sealed interface Verdict {
        data class Allowed(val uri: String?) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun check(entry: AppAtlasEntry, action: AtlasAction, uri: String?, query: String?): Verdict {
        if (entry.sensitive && action.kind != ActionKind.LAUNCH) {
            return Verdict.Refused(
                "${entry.label} is a protected app (${entry.sensitiveReason ?: "no automation"}): only action_id=open is allowed."
            )
        }
        if (!action.fireable) {
            return Verdict.Refused("Action '${action.id}' of ${entry.label} cannot be started by other apps. Use action_id=open instead.")
        }
        if (query != null && query.length > MAX_QUERY_CHARS) {
            return Verdict.Refused("query is too long (max $MAX_QUERY_CHARS characters).")
        }
        if (action.kind != ActionKind.DEEP_LINK) return Verdict.Allowed(null)
        val requested = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return Verdict.Allowed(action.uri)
        return validateUri(action, requested)
    }

    internal fun validateUri(action: AtlasAction, requested: String): Verdict {
        if (requested.length > MAX_URI_CHARS) return Verdict.Refused("uri is too long (max $MAX_URI_CHARS characters).")
        if (requested.any { it.isWhitespace() || it.code < 0x20 }) return Verdict.Refused("uri must not contain spaces or control characters (percent-encode them).")
        val parsed = runCatching { URI(requested) }.getOrNull() ?: return Verdict.Refused("uri is not a valid URI.")
        val scheme = parsed.scheme?.lowercase() ?: return Verdict.Refused("uri has no scheme.")
        if (scheme in FORBIDDEN_SCHEMES) return Verdict.Refused("scheme '$scheme' is not allowed.")
        if (scheme != action.scheme?.lowercase()) {
            return Verdict.Refused("uri must use the action's scheme '${action.scheme}', got '$scheme'.")
        }
        val expectedHost = action.host?.lowercase()
        if (expectedHost != null) {
            val host = parsed.host?.lowercase() ?: return Verdict.Refused("uri must have host '$expectedHost'.")
            if (host != expectedHost) return Verdict.Refused("uri must use host '$expectedHost', got '$host'.")
            if (parsed.userInfo != null) return Verdict.Refused("uri must not contain user info.")
        }
        return Verdict.Allowed(requested)
    }
}
