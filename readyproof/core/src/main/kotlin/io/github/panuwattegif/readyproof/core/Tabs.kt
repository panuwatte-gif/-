package io.github.panuwattegif.readyproof.core

/** The order tabs of GrabMerchant that matter here; everything else is [OTHER]. */
enum class OrderTab { READY, PREPARING, HISTORY, OTHER }

/**
 * Which order tab is open, and where to tap to open another one. Tab bars mark the open tab as
 * "selected" for accessibility (the same flag a screen reader announces), so every order shown
 * under the Ready tab can be treated as "done" without depending on status wording or language.
 */
object TabDetector {
    // What may follow a tab label: a count badge such as "Ready 2", "Ready (2)" or "Ready, 2".
    private val AFTER_LABEL = Regex("""[\s,.:()\-]*\d{0,4}\)?\s*""")
    private val NUMBER = Regex("""^\(?(\d{1,4})\)?$""")
    private val TRAILING_NUMBER = Regex("""(\d{1,4})\)?\s*$""")

    /** true = Ready tab open, false = another tab, null = no selected tab reported. */
    fun readyTabOpen(roots: List<UiNode>, cfg: Config): Boolean? = when (openTab(roots, cfg)) {
        OrderTab.READY -> true
        null -> null
        else -> false
    }

    /** The selected order tab, or null when no selected tab label is reported. */
    fun openTab(roots: List<UiNode>, cfg: Config): OrderTab? {
        var found: OrderTab? = null
        for (root in roots) {
            for (n in root.walk()) {
                val label = labelOf(n, cfg.tabLabels) ?: continue
                if (!selectedNear(n)) continue
                val tab = when {
                    isAny(label, cfg.readyTabLabels) -> OrderTab.READY
                    isAny(label, cfg.historyTabLabels) -> OrderTab.HISTORY
                    isAny(label, cfg.preparingTabLabels) -> OrderTab.PREPARING
                    else -> OrderTab.OTHER
                }
                if (tab == OrderTab.READY) return tab
                if (found == null || found == OrderTab.OTHER) found = tab
            }
        }
        return found
    }

    /** "Ready", "Ready 2", "Ready (2)" name the tab; "Ready in: 9:32 min" does not. */
    fun isLabel(text: String, label: String): Boolean {
        val t = TextNorm.key(text)
        val l = TextNorm.key(label)
        if (l.isEmpty() || !t.startsWith(l)) return false
        return AFTER_LABEL.matches(t.substring(l.length))
    }

    /**
     * The number badge on the Ready tab ("พร้อมจัดส่ง 4"), or null when none is shown.
     * Only a hint that the list changed; never used to decide what is in the list.
     */
    fun readyCount(roots: List<UiNode>, cfg: Config): Int? {
        val gfx = cfg.gfExtractor()
        for (root in roots) {
            for (n in root.walk()) {
                val s = n.ownStrings().firstOrNull { s -> cfg.readyTabLabels.any { isLabel(s, it) } } ?: continue
                if (!inBar(n, cfg.tabLabels, gfx)) continue
                val label = cfg.readyTabLabels.first { isLabel(s, it) }
                TRAILING_NUMBER.find(TextNorm.clean(s).substring(TextNorm.clean(label).length))
                    ?.let { return it.groupValues[1].toInt() }
                // badge as its own element next to the label
                val siblings = n.parent?.children ?: return null
                val idx = siblings.indexOfFirst { it === n }
                for (i in idx + 1 until siblings.size) {
                    val t = siblings[i].collectTexts(4).firstOrNull() ?: continue
                    return NUMBER.find(TextNorm.clean(t))?.groupValues?.get(1)?.toInt()
                }
                return null
            }
        }
        return null
    }

    /**
     * The element to tap to open the tab named by one of [want]. Returned only when it sits in a
     * bar that holds at least 3 different [group] labels and no order number, so a tap can never
     * land on an order card or one of its buttons (the "Ready" button of an order has the same
     * word as the Ready tab).
     */
    fun tapTarget(roots: List<UiNode>, want: List<String>, group: List<String>, gfx: GfExtractor): UiNode? {
        // The label whose bar is closest wins, so a screen title such as "Orders" (which only
        // reaches the bottom bar far up the tree) never beats the real bottom-bar item.
        var best: UiNode? = null
        var bestDepth = Int.MAX_VALUE
        for (root in roots) {
            for (n in root.walk()) {
                if (labelOf(n, want) == null) continue
                val depth = barDepth(n, group, gfx)
                if (depth in 0 until bestDepth) {
                    best = n
                    bestDepth = depth
                }
            }
        }
        return best?.let { clickableNear(it) }
    }

    /** True when a bar with at least 3 of these labels is on screen (the Orders tab bar, the bottom bar). */
    fun barVisible(roots: List<UiNode>, group: List<String>, gfx: GfExtractor): Boolean =
        roots.any { root -> root.walk().any { n -> n.shown && labelOf(n, group) != null && inBar(n, group, gfx) } }

    private fun inBar(n: UiNode, group: List<String>, gfx: GfExtractor): Boolean = barDepth(n, group, gfx) >= 0

    /** Levels up to the bar holding [n] (3+ distinct [group] labels, no order number), or -1. */
    private fun barDepth(n: UiNode, group: List<String>, gfx: GfExtractor): Int {
        var p = n.parent
        var depth = 0
        while (p != null && depth < 4) {
            val texts = p.collectTexts(80)
            if (texts.any { gfx.extract(it).isNotEmpty() }) return -1
            val distinct = group.filter { g -> texts.any { isLabel(it, g) } }.map { TextNorm.key(it) }.distinct()
            if (distinct.size >= 3) return depth
            p = p.parent
            depth++
        }
        return -1
    }

    private fun clickableNear(n: UiNode): UiNode {
        var c: UiNode? = n
        var depth = 0
        while (c != null && depth <= 3) {
            if (c.clickable) return c
            c = c.parent
            depth++
        }
        return n
    }

    private fun labelOf(n: UiNode, labels: List<String>): String? =
        n.ownStrings().firstNotNullOfOrNull { s -> labels.firstOrNull { isLabel(s, it) } }

    private fun isAny(label: String, labels: List<String>): Boolean =
        labels.any { TextNorm.key(it) == TextNorm.key(label) }

    // Tab widgets pass "selected" down to their label; some only set it on the container.
    private fun selectedNear(n: UiNode): Boolean =
        n.selected || n.parent?.selected == true || n.parent?.parent?.selected == true
}
