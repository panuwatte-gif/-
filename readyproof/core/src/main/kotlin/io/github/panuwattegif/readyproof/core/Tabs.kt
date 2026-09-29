package io.github.panuwattegif.readyproof.core

/**
 * Which order tab is open. Tab bars mark the open tab as "selected" for accessibility (the same
 * flag a screen reader announces), so every order shown under the Ready tab can be treated as
 * "pressed ready" without depending on the status wording or the app language.
 */
object TabDetector {
    // What may follow a tab label: a count badge such as "Ready 2", "Ready (2)" or "Ready, 2".
    private val AFTER_LABEL = Regex("""[\s,.:()\-]*\d{0,4}\)?\s*""")

    /**
     * true = a Ready tab is selected, false = another known tab is selected,
     * null = no selected tab is reported (the caller then falls back to card wording).
     */
    fun readyTabOpen(roots: List<UiNode>, cfg: Config): Boolean? {
        var anySelected = false
        for (root in roots) {
            for (n in root.walk()) {
                val label = n.ownStrings().firstNotNullOfOrNull { s -> cfg.tabLabels.firstOrNull { isLabel(s, it) } }
                    ?: continue
                if (!selectedNear(n)) continue
                anySelected = true
                if (cfg.readyTabLabels.any { TextNorm.key(it) == TextNorm.key(label) }) return true
            }
        }
        return if (anySelected) false else null
    }

    /** "Ready", "Ready 2", "Ready (2)" name the tab; "Ready in: 9:32 min" does not. */
    fun isLabel(text: String, label: String): Boolean {
        val t = TextNorm.key(text)
        val l = TextNorm.key(label)
        if (l.isEmpty() || !t.startsWith(l)) return false
        return AFTER_LABEL.matches(t.substring(l.length))
    }

    // Tab widgets pass "selected" down to their label; some only set it on the container.
    private fun selectedNear(n: UiNode): Boolean =
        n.selected || n.parent?.selected == true || n.parent?.parent?.selected == true
}
