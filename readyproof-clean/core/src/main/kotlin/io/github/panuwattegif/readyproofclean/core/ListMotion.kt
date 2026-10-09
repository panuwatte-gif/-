package io.github.panuwattegif.readyproofclean.core

data class ListViewport(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = bottom - top
}

/** Geometry comes from order cards and their container, not isScrollable or changing rider text. */
object ListMotion {
    fun key(a: ScreenAnalysis): String = a.cards.filter { it.inList }
        .joinToString("|") { "${it.gf}@${it.node.left}:${it.node.top}:${it.node.right}:${it.node.bottom}" }

    fun viewport(a: ScreenAnalysis): ListViewport? {
        val cards = a.cards.filter { it.inList }
        val first = cards.firstOrNull()?.node ?: return null
        var n = first.parent
        while (n != null) {
            val parent = n
            val containsAll = cards.all { c ->
                var p: UiNode? = c.node
                while (p != null && p !== parent) p = p.parent
                p === parent
            }
            if (containsAll && parent.right > parent.left && parent.bottom - parent.top >= 160)
                return ListViewport(parent.left, parent.top, parent.right, parent.bottom)
            n = parent.parent
        }
        return null
    }

    fun proofVisible(card: Card, a: ScreenAnalysis): Boolean {
        val vp = viewport(a) ?: return false
        val n = card.node
        return n.right > n.left && n.bottom > n.top && n.left >= vp.left && n.right <= vp.right &&
            n.top >= vp.top && n.bottom <= vp.bottom
    }

    fun changed(before: String, after: String): Boolean = before.isNotEmpty() && after.isNotEmpty() && before != after
}

object HistoryScreen {
    fun isOpen(roots: List<UiNode>, cfg: Config): Boolean {
        if (TabDetector.readyTabOpen(roots, cfg) == true) return false
        val nodes = roots.flatMap { it.walk().toList() }
        val hasTab = nodes.any { n -> n.ownStrings().any { t ->
            listOf("History", "ประวัติ").any { TabDetector.isLabel(t, it) }
        } }
        if (!hasTab) return false
        val selected = nodes.any { n ->
            (n.selected || n.parent?.selected == true || n.parent?.parent?.selected == true) &&
                n.ownStrings().any { t -> listOf("History", "ประวัติ").any { TabDetector.isLabel(t, it) } }
        }
        val terminal = ScreenAnalyzer.analyze(roots, cfg, true).items.any {
            it.type in listOf(ObsType.DONE, ObsType.CANCELLED)
        }
        val header = nodes.flatMap { it.ownStrings() }.any { HistoryDates.parse(it, java.time.LocalDate.now()) != null } &&
            HistoryTotalsParser.parseRoots(roots, cfg) != null
        return selected || terminal || header
    }
}
