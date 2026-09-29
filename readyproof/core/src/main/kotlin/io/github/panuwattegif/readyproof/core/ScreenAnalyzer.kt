package io.github.panuwattegif.readyproof.core

/** Everything worth knowing about the screen at one moment. */
data class ScreenAnalysis(
    val cards: List<Card>,
    /** READY / DELAY / DONE observations (one per order and type). */
    val items: List<Item>,
    val visible: List<String>,
)

/** Result for a tap on the "food ready" button. */
data class PressAnalysis(
    /** Order the tapped button belongs to; null when it cannot be told apart. */
    val gf: String?,
    val card: List<String>,
    val countdown: String?,
    val visible: List<String>,
)

object ScreenAnalyzer {

    fun analyze(roots: List<UiNode>, cfg: Config): ScreenAnalysis {
        val gfx = cfg.gfExtractor()
        val rules = StatusRules(cfg)
        val cards = ArrayList<Card>()
        val visible = LinkedHashSet<String>()
        for (root in roots) {
            val finder = CardFinder(gfx, root)
            cards += finder.cards()
            visible += finder.visibleGfs()
        }
        val items = cards.flatMap { rules.evaluate(it) }.distinctBy { Triple(it.gf, it.type, it.doneAt) }
        return ScreenAnalysis(cards, items, visible.toList())
    }

    fun analyzePress(roots: List<UiNode>, cfg: Config): PressAnalysis {
        val gfx = cfg.gfExtractor()
        val rules = StatusRules(cfg)
        val visible = LinkedHashSet<String>()
        var gf: String? = null
        var texts: List<String> = emptyList()
        for (root in roots) {
            val finder = CardFinder(gfx, root)
            visible += finder.visibleGfs()
            if (gf != null) continue
            val marked = root.walk().firstOrNull { it.marked } ?: continue
            val cardNode = finder.cardFor(marked) ?: continue
            gf = finder.gfsIn(cardNode).first()
            texts = finder.textsOf(cardNode)
        }
        if (gf == null && visible.size == 1) {
            // The tapped element is gone already, but only one order is on screen.
            gf = visible.first()
            texts = roots.flatMap { CardFinder(gfx, it).cards() }.firstOrNull { it.gf == gf }?.texts ?: emptyList()
        }
        return PressAnalysis(gf, texts, rules.countdownOf(texts), visible.toList())
    }

    /** One VISIBLE item per order card, for hand-made captures. */
    fun visibleItems(analysis: ScreenAnalysis, cfg: Config): List<Item> {
        val gfx = cfg.gfExtractor()
        return analysis.cards.distinctBy { it.gf }.map { card ->
            val status = card.texts.firstOrNull { gfx.extract(it).isEmpty() }
            Item(card.gf, ObsType.VISIBLE, status = status, card = card.texts)
        }
    }
}
