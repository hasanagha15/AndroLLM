package io.androllm.core.appatlas.tools

import io.androllm.core.appatlas.index.SchemePolicy
import io.androllm.core.appatlas.index.SensitiveClassifier
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
 * - https share / compose links (prefilled message, post or e-mail drafts)
 *   are communication actions and are refused like tel:/sms:/mailto:;
 * - query text is bounded.
 */
object ActionGuard {

    const val MAX_URI_CHARS = 1_000
    const val MAX_QUERY_CHARS = 300
    private const val USE_CONFIRMED_TOOLS =
        "Calls, messages, e-mails and payments never go through the atlas: use make_call / send_sms / send_email " +
            "(they ask the user first); payments are made by the user."

    /** Branch M: refusal for prefilled share / compose web links. */
    const val COMPOSE_LINK =
        "This link opens a prefilled message / post / e-mail draft (a communication action), so the atlas will not open it. " +
            USE_CONFIRMED_TOOLS

    sealed interface Verdict {
        data class Allowed(val uri: String?) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun check(
        stored: AppAtlasEntry,
        action: AtlasAction,
        uri: String?,
        query: String?,
        extras: Set<String> = emptySet()
    ): Verdict {
        // Gate B2: sensitivity is re-evaluated now (incl. the owner's list), not trusted from the database.
        val entry = SensitiveClassifier.effective(stored, extras)
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
        // Gate B1: never call / message / mail / pay / delete / install, whatever the database says.
        if (SchemePolicy.isBlockedScheme(action.scheme) || SchemePolicy.isBlockedUri(action.uri) || SchemePolicy.isBlockedUri(action.data)) {
            return Verdict.Refused(USE_CONFIRMED_TOOLS)
        }
        // Branch M: an https share / compose link (t.me/share, wa.me?text=, Gmail compose…) is a message draft too.
        if (SchemePolicy.isComposeWebUrl(action.uri) || SchemePolicy.isComposeWebUrl(action.data)) {
            return Verdict.Refused(COMPOSE_LINK)
        }
        if (action.kind == ActionKind.SHORTCUT && SchemePolicy.isBlockedShortcutAction(action.intentAction)) {
            return Verdict.Refused(USE_CONFIRMED_TOOLS)
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
        if (SchemePolicy.isBlockedScheme(scheme)) return Verdict.Refused("scheme '$scheme' is not allowed. $USE_CONFIRMED_TOOLS")
        if (SchemePolicy.isComposeWebUrl(requested)) return Verdict.Refused(COMPOSE_LINK)
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

    /**
     * Gate m3: the same scheme/host rules applied to what the LAUNCH parser
     * (android.net.Uri) sees, so a parser differential cannot slip through.
     */
    fun launchParseMatches(action: AtlasAction, scheme: String?, host: String?, userInfo: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        if (SchemePolicy.isBlockedScheme(s) || s != action.scheme?.lowercase()) return false
        val expectedHost = action.host?.lowercase() ?: return true
        return host?.lowercase() == expectedHost && userInfo == null
    }
}
