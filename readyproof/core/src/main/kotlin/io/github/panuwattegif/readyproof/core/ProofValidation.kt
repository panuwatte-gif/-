package io.github.panuwattegif.readyproof.core

data class ValidatedTarget(val item: Item, val fingerprint: String)

/** Accessibility metadata is checked on both sides of the bitmap callback, per order. */
object ProofValidation {
    fun targets(kind: RecordKind, wanted: List<Item>, roots: List<UiNode>, cfg: Config): List<ValidatedTarget> {
        val analysis = ScreenAnalyzer.analyze(roots, cfg)
        val rules = StatusRules(cfg)
        val gfx = cfg.gfExtractor()
        fun onScreen(n: UiNode): Boolean = roots.any { root ->
            n.left >= root.left && n.right > n.left && n.right <= root.right &&
                n.top >= root.top && n.bottom > n.top && n.bottom <= root.bottom
        }
        return wanted.mapNotNull { target ->
            val cards = analysis.cards.filter { c ->
                c.inList && c.gf == target.gf && (target.type == ObsType.READY ||
                    rules.evaluate(c).any { it.type == target.type && it.doneAt == target.doneAt })
            }
            // Two identical identities visible at once cannot safely be assigned to one bitmap row.
            val card = cards.singleOrNull() ?: return@mapNotNull null
            val nodes = card.node.walk().toList()
            if (nodes.none { n -> onScreen(n) && n.ownStrings().any { target.gf in gfx.extract(it) } }) return@mapNotNull null
            val observed = rules.evaluate(card)
            val history = observed.any { it.type in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED) }
            val valid = when (target.type) {
                ObsType.READY -> kind in listOf(RecordKind.READY, RecordKind.MANUAL) && !history &&
                    (analysis.readyTab == true || (analysis.readyTab == null && observed.any { it.type == ObsType.READY }))
                ObsType.DELAY -> target.doneAt != null && nodes.any { n -> onScreen(n) &&
                    n.ownStrings().any { TextNorm.containsAny(it, cfg.delayAny) } } &&
                    nodes.any { n -> onScreen(n) && n.ownStrings().any { Parsers.doneAt(it, cfg.doneAny + cfg.cancelAny) == target.doneAt } }
                ObsType.DONE, ObsType.CANCELLED -> target.doneAt != null && nodes.any { n -> onScreen(n) &&
                    n.ownStrings().any { Parsers.doneAt(it, if (target.type == ObsType.DONE) cfg.doneAny else cfg.cancelAny) == target.doneAt } }
                else -> false
            }
            if (!valid) return@mapNotNull null
            val fingerprint = Json.write(listOf(target.gf, target.type.name, target.doneAt,
                card.node.left, card.node.top, card.node.right, card.node.bottom, card.texts))
            ValidatedTarget(target, fingerprint)
        }
    }

    fun stableSubset(before: List<ValidatedTarget>, after: List<ValidatedTarget>): List<Item> =
        before.filter { b -> after.any { a -> a.item == b.item && a.fingerprint == b.fingerprint } }.map { it.item }
}
