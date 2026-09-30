package io.github.panuwattegif.readyproof.core

/** Detects which Grab order tab is selected without relying on order-card status text. */
object TabDetector {
    /**
     * true  = the Ready / พร้อมจัดส่ง tab is selected
     * false = another known order tab is selected
     * null  = Grab did not expose selected-tab state through Accessibility
     */
    fun readyTabOpen(roots: List<UiNode>, cfg: Config): Boolean? {
        val allTabs = cfg.tabLabels.map(TextNorm::key).filter { it.isNotEmpty() }.toSet()
        val readyTabs = cfg.readyTabLabels.map(TextNorm::key).filter { it.isNotEmpty() }.toSet()
        if (allTabs.isEmpty() || readyTabs.isEmpty()) return null

        var selectedKnown: String? = null
        for (root in roots) {
            for (n in root.walk()) {
                val labels = n.ownStrings().map(TextNorm::key)
                val hit = labels.firstOrNull { label -> allTabs.any { tab -> label == tab || label.contains(tab) } }
                    ?: continue
                if (isSelectedTabNode(n)) {
                    selectedKnown = hit
                    break
                }
            }
            if (selectedKnown != null) break
        }
        val selected = selectedKnown ?: return null
        return readyTabs.any { tab -> selected == tab || selected.contains(tab) }
    }

    private fun isSelectedTabNode(node: UiNode): Boolean {
        var n: UiNode? = node
        repeat(4) {
            val cur = n ?: return false
            if (cur.selected) return true
            n = cur.parent
        }
        return false
    }
}
