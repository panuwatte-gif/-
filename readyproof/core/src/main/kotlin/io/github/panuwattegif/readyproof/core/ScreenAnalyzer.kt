package io.github.panuwattegif.readyproof.core

/** Everything worth knowing about the screen at one moment. */
data class ScreenAnalysis(
    val cards: List<Card>,
    /** READY / DELAY / DONE observations (one per order and type). */
    val items: List<Item>,
    val visible: List<String>,
    /** true = Ready tab open, false = another tab open, null = cannot tell. */
    val readyTab: Boolean? = null,
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
        val readyTab = TabDetector.readyTabOpen(roots, cfg)
        val cards = ArrayList<Card>()
        val visible = LinkedHashSet<String>()
        for (root in roots) {
            val finder = CardFinder(gfx, root)
            cards += finder.cards()
            visible += finder.visibleGfs()
        }
        val items = ArrayList<Item>()
        for (card in cards) {
            val observed = rules.evaluate(card)
            val history = observed.filter { it.type == ObsType.DONE || it.type == ObsType.DELAY }
            val ready = when (readyTab) {
                // Everything listed under the Ready tab has been pressed ready, whatever its status says.
                true -> if (card.inList && history.isEmpty()) {
                    Item(card.gf, ObsType.READY, status = statusLine(card, gfx), card = card.texts)
                } else {
                    null
                }
                false -> null
                null -> observed.firstOrNull { it.type == ObsType.READY }
            }
            if (ready != null) items += ready
            items += history
        }
        return ScreenAnalysis(cards, items.distinctBy { Triple(it.gf, it.type, it.doneAt) }, visible.toList(), readyTab)
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

    /**
     * Items for a hand-made capture: what the screen proves (READY / DELAY / DONE) plus a VISIBLE
     * item for every other order on screen, so the shot can still be found by order number.
     */
    fun manualItems(analysis: ScreenAnalysis, cfg: Config): List<Item> {
        val gfx = cfg.gfExtractor()
        val typed = analysis.items.map { it.gf }.toSet()
        val others = analysis.cards.distinctBy { it.gf }.filter { it.gf !in typed }.map { card ->
            Item(card.gf, ObsType.VISIBLE, status = statusLine(card, gfx), card = card.texts)
        }
        return analysis.items + others
    }

    /** First line of a card that is not the order number, e.g. "Finding a driver...". */
    fun statusLine(card: Card, gfx: GfExtractor): String? = card.texts.firstOrNull { gfx.extract(it).isEmpty() }
}
