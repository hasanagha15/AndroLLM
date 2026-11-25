package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.ScreenNode

/** The node a step's locators resolved to, its score and WHICH locator kinds matched (ids only). */
data class Resolution(val node: ScreenNode, val score: Double, val matched: List<String>)

/**
 * Weighted multi-locator resolution (SkillDroid style). Each node of the
 * screen scores the sum of the weights of the locators it satisfies:
 *
 * | kind        | default weight | match                                        |
 * |-------------|----------------|----------------------------------------------|
 * | id          | 1.0            | resource entry name, exact (case-insensitive) |
 * | desc        | 0.8            | content-desc, exact (trimmed, case-insensitive) |
 * | text        | 0.7            | text, exact (trimmed, case-insensitive)       |
 * | class_index | 0.4            | class (simple or full name) + index among that class |
 * | bounds      | 0.3            | node centre inside the relative rect ± 0.05   |
 *
 * The best node needs at least the step's minimum score. Strict mode
 * (unverified app version) additionally needs a semantic match (id / desc /
 * text) and a unique best node. Pure.
 */
object LocatorResolver {

    private const val BOUNDS_SLACK = 0.05

    fun score(locators: List<Locator>, node: ScreenNode): Pair<Double, List<String>> {
        var s = 0.0
        val matched = mutableListOf<String>()
        for (l in locators) {
            if (matches(l, node)) {
                s += l.effectiveWeight
                matched += l.kind
            }
        }
        return s to matched
    }

    fun matches(l: Locator, n: ScreenNode): Boolean = when (l.kind) {
        Locator.ID -> l.value != null && n.idEntry?.equals(l.value, ignoreCase = true) == true
        Locator.DESC -> l.value != null && n.description?.trim()?.equals(l.value.trim(), ignoreCase = true) == true
        Locator.TEXT -> l.value != null && n.text?.trim()?.equals(l.value.trim(), ignoreCase = true) == true
        Locator.CLASS_INDEX -> {
            val v = l.value
            val cls = n.className
            v != null && cls != null && l.index == n.classIndex &&
                (if ('.' in v) cls == v else cls.substringAfterLast('.') == v)
        }
        Locator.BOUNDS -> {
            val b = l.bounds
            b != null && b.size == 4 &&
                n.centerX >= b[0] - BOUNDS_SLACK && n.centerX <= b[2] + BOUNDS_SLACK &&
                n.centerY >= b[1] - BOUNDS_SLACK && n.centerY <= b[3] + BOUNDS_SLACK
        }
        else -> false
    }

    /**
     * Best node for [locators] on [nodes], or null. [preferEditable] for type
     * steps; ties go to clickable (tap) / editable (type) nodes, then tree order.
     */
    fun resolve(
        locators: List<Locator>,
        nodes: List<ScreenNode>,
        minScore: Double = Locator.DEFAULT_MIN_SCORE,
        strict: Boolean = false,
        preferEditable: Boolean = false
    ): Resolution? {
        if (locators.isEmpty()) return null
        val scored = nodes.map { n -> val (s, m) = score(locators, n); Resolution(n, s, m) }
            .filter { it.score >= minScore - 1e-9 && it.score > 0 }
            .filter { !strict || it.matched.any { k -> k == Locator.ID || k == Locator.DESC || k == Locator.TEXT } }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.score }
        var top = scored.filter { it.score >= best - 1e-9 }
        if (top.size > 1) {
            val preferred = top.filter { if (preferEditable) it.node.editable else it.node.clickable }
            if (preferred.isNotEmpty()) top = preferred
        }
        if (strict && top.size > 1) return null
        return top.minByOrNull { it.node.index }
    }

    /** Best score on the screen (diagnostics when nothing resolved). */
    fun bestScore(locators: List<Locator>, nodes: List<ScreenNode>): Double =
        nodes.maxOfOrNull { score(locators, it).first } ?: 0.0
}
