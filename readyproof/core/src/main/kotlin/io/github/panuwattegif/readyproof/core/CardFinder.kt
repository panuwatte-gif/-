package io.github.panuwattegif.readyproof.core

import java.util.IdentityHashMap

/** One order card on screen: its order number and every string inside it. */
class Card(val node: UiNode, val gf: String, val texts: List<String>)

/**
 * Groups on-screen texts into order cards without knowing the target app's layout:
 * a card is the largest element that contains exactly one order number, never crossing a
 * list container (so one card never swallows its neighbours).
 */
class CardFinder(private val gfx: GfExtractor, root: UiNode) {
    private val own = IdentityHashMap<UiNode, List<String>>()
    private val subtree = IdentityHashMap<UiNode, Set<String>>()
    private val nodes: List<UiNode> = root.walk().toList()

    init {
        for (n in nodes) own[n] = n.ownStrings().flatMap { gfx.extract(it) }.distinct()
        // children appear after their parent in pre-order, so walking backwards is post-order
        for (n in nodes.asReversed()) {
            val set = LinkedHashSet<String>(own[n]!!)
            for (c in n.children) set.addAll(subtree[c]!!)
            subtree[n] = set
        }
    }

    /** Every order number visible on screen, in reading order. */
    fun visibleGfs(): List<String> = nodes.flatMap { own[it]!! }.distinct()

    fun gfsIn(node: UiNode): Set<String> = subtree[node] ?: emptySet()

    /** The card that contains [start] (a tapped button, a text ...), or null when ambiguous. */
    fun cardFor(start: UiNode): UiNode? {
        var cur: UiNode? = start
        var best: UiNode? = null
        while (cur != null) {
            val n = gfsIn(cur).size
            if (n >= 2) break
            if (n == 1) best = cur
            val p = cur.parent ?: break
            if (best != null && isListContainer(p)) break
            cur = p
        }
        return best
    }

    fun cards(): List<Card> {
        val seen = IdentityHashMap<UiNode, Boolean>()
        val out = ArrayList<Card>()
        for (n in nodes) {
            if (own[n]!!.size != 1) continue
            val cardNode = cardFor(n) ?: continue
            if (seen.put(cardNode, true) != null) continue
            out += Card(cardNode, gfsIn(cardNode).first(), textsOf(cardNode, n))
        }
        return out
    }

    /** Strings of a card; falls back to reading order when the app flattens its cards. */
    fun textsOf(cardNode: UiNode, gfNode: UiNode = cardNode): List<String> {
        val texts = cardNode.collectTexts()
        val onlyNumber = texts.all { gfx.extract(it).isNotEmpty() }
        if (!onlyNumber || cardNode !== gfNode) return texts
        // Flattened list: the order number's siblings up to the next order number belong to it.
        val parent = gfNode.parent ?: return texts
        val out = ArrayList(texts)
        val idx = parent.children.indexOfFirst { it === gfNode }
        for (i in idx + 1 until parent.children.size) {
            val sib = parent.children[i]
            if (gfsIn(sib).isNotEmpty()) break
            for (s in sib.collectTexts()) if (out.lastOrNull() != s) out += s
            if (out.size >= 20) break
        }
        return out
    }

    companion object {
        private val LIST_CLASS = Regex(
            "RecyclerView|ListView|GridView|ScrollView|ViewPager|LazyColumn|LazyRow|LazyList",
            RegexOption.IGNORE_CASE,
        )

        fun isListContainer(n: UiNode): Boolean =
            n.collection || n.scrollable || (n.className?.let { LIST_CLASS.containsMatchIn(it) } ?: false)
    }
}
