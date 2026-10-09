package io.github.panuwattegif.readyproof.core

import java.util.IdentityHashMap

/**
 * One order card on screen: its order number and every string inside it.
 * [inList] is false for things outside the order list, e.g. a notification banner naming an order.
 * [gfNode] is the element showing the order number (it must be fully on screen in a shot);
 * [list] is the list the card sits in (its visible area) and [scroller] the element that scrolls it.
 */
class Card(
    val node: UiNode,
    val gf: String,
    val texts: List<String>,
    val inList: Boolean = false,
    val gfNode: UiNode = node,
    val list: UiNode? = null,
    val scroller: UiNode? = null,
)

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
        for (n in nodes) own[n] = if (n.shown) n.ownStrings().flatMap { gfx.extract(it) }.distinct() else emptyList()
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
            val list = listAround(cardNode)
            out += Card(
                node = cardNode,
                gf = gfsIn(cardNode).first(),
                texts = textsOf(cardNode, n),
                inList = list != null,
                gfNode = n,
                list = list,
                scroller = scrollerOf(cardNode) ?: list,
            )
        }
        return out
    }

    /** Nearest list container around [node], or null. */
    fun listAround(node: UiNode): UiNode? {
        var p = node.parent
        while (p != null) {
            if (isListContainer(p)) return p
            p = p.parent
        }
        return null
    }

    /**
     * Nearest ancestor that can actually scroll up/down (a list inside a scroll view may not scroll
     * itself). Stops at a sideways pager: scrolling that would switch Grab's tab, not the list.
     */
    fun scrollerOf(node: UiNode): UiNode? {
        var p = node.parent
        while (p != null) {
            if (p.horizontal) return null
            if (p.scrollable || p.canScrollForward || p.canScrollBackward) return p
            p = p.parent
        }
        return null
    }

    /** True when [node] sits inside a list container (the order list, not a banner or header). */
    fun inList(node: UiNode): Boolean {
        var p = node.parent
        while (p != null) {
            if (isListContainer(p)) return true
            p = p.parent
        }
        return false
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
