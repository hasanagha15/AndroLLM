package io.androllm.core.accessibility.controller

import io.androllm.core.accessibility.planner.SafetyClassifier

/**
 * Owner approval for tapping ONE risky element: the label (and optional view
 * id) shown on the tap-only card. Created by the ui_* tools only inside
 * [io.androllm.core.tools.confirmation.RiskyActionApproval], and by the ui_run
 * executor right after its own tap-only card was approved.
 */
data class RiskApproval(val label: String, val id: String? = null)

/**
 * Branch M (gate B1): the risk decision is made on the RESOLVED element — the
 * node the selector actually found (its text, content description, view id and
 * those of its clickable ancestors) — not on the model-supplied query. A
 * partial query ("Отпра", "öndər", "onfir") that resolves to «Отправить» /
 * «Göndər» / «Confirm» is refused unless the owner approved exactly that
 * element. Pure (unit-tested); the controller extracts [Facts] on the main
 * thread.
 */
object RiskyElementGate {

    /** What identifies an on-screen element. */
    data class Facts(
        val text: String? = null,
        val description: String? = null,
        val viewId: String? = null,
        /** Clickable ancestors (the tap may land on them), nearest first. */
        val ancestors: List<Facts> = emptyList()
    )

    sealed interface Decision {
        data object Allow : Decision
        /**
         * Risky element without a matching approval: refuse, name the element
         * ([element] = its risky label; [id] = the view id entry when only the id is risky).
         */
        data class Refuse(val element: String, val id: String? = null) : Decision
    }

    /** View id entry name ("com.app:id/send_button" → "send_button"). */
    fun idEntry(viewId: String?): String? =
        viewId?.substringAfter(":id/")?.trim()?.takeIf { it.isNotEmpty() }

    /** Human label of the element: text, else description, else id entry, else an ancestor's. */
    fun displayLabel(f: Facts): String =
        f.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: f.description?.trim()?.takeIf { it.isNotEmpty() }
            ?: f.ancestors.firstNotNullOfOrNull { a ->
                a.text?.trim()?.takeIf { it.isNotEmpty() } ?: a.description?.trim()?.takeIf { it.isNotEmpty() }
            }
            ?: idEntry(f.viewId)
            ?: ""

    private fun riskyFields(f: Facts): List<String> =
        listOfNotNull(f.text, f.description, idEntry(f.viewId)?.replace('_', ' '))
            .filter { it.isNotBlank() && SafetyClassifier.isRiskyTarget(it) }

    /** True when the element itself or one of its clickable ancestors looks risky. */
    fun isRisky(f: Facts): Boolean =
        riskyFields(f).isNotEmpty() || f.ancestors.any { riskyFields(it).isNotEmpty() }

    private fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return SafetyClassifier.fold(a) == SafetyClassifier.fold(b)
    }

    /** The approval names THIS element (full label or exact view id), not just a substring of it. */
    fun approvalMatches(f: Facts, approval: RiskApproval): Boolean {
        val all = listOf(f) + f.ancestors
        if (all.any { same(it.text, approval.label) || same(it.description, approval.label) }) return true
        val id = approval.id?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return all.any { a -> idEntry(a.viewId)?.equals(id, ignoreCase = true) == true }
    }

    fun decide(f: Facts, approval: RiskApproval?): Decision {
        if (!isRisky(f)) return Decision.Allow
        if (approval != null && approvalMatches(f, approval)) return Decision.Allow
        val all = listOf(f) + f.ancestors
        val riskyLabel = all.firstNotNullOfOrNull { a ->
            listOfNotNull(a.text, a.description).firstOrNull { it.isNotBlank() && SafetyClassifier.isRiskyTarget(it) }
        }
        if (riskyLabel != null) return Decision.Refuse(riskyLabel.trim())
        val riskyId = all.firstNotNullOfOrNull { a ->
            idEntry(a.viewId)?.takeIf { SafetyClassifier.isRiskyTarget(it.replace('_', ' ')) }
        }
        return Decision.Refuse(displayLabel(f).ifEmpty { riskyId ?: "this element" }, riskyId)
    }

    /** Refusal text for the model (ui_click / ui_gesture). */
    fun refusalMessage(refusal: Decision.Refuse, verb: String = "ui_click"): String {
        val label = refusal.element.take(160)
        val args = "label='$label'" + (refusal.id?.let { " and id='$it'" } ?: "")
        return "The element that would be tapped is '$label' — a send / pay / call / confirm / delete step. " +
            "It was not tapped. Call $verb again with $args so the user can confirm exactly " +
            "this element on the confirmation card, or let the user tap it."
    }
}
