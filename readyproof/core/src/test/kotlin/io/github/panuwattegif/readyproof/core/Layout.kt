package io.github.panuwattegif.readyproof.core

/**
 * Gives a test tree screen positions the way a phone would: each text is a 40 px line,
 * containers add 8 px padding, everything is stacked top to bottom on a 720 px wide screen.
 * The first order list can be cut to [listHeight] px and scrolled by [scrollY] px, like a
 * list longer than the screen.
 */
object Layout {
    const val W = 720
    private const val LINE = 40
    private const val PAD = 8

    /** Elements whose position is fixed instead of stacked (a floating button). */
    private val fixed = java.util.IdentityHashMap<UiNode, Box>()

    /** Marks [node] to be placed at [box] rather than in the flow. */
    fun fix(node: UiNode, box: Box): UiNode {
        fixed[node] = box
        return node
    }

    fun of(root: UiNode, listHeight: Int? = null, scrollY: Int = 0): UiNode {
        var listDone = false

        fun copy(n: UiNode, box: Box, fwd: Boolean = n.canScrollForward, back: Boolean = n.canScrollBackward) = UiNode(
            text = n.text, desc = n.desc, viewId = n.viewId, className = n.className, clickable = n.clickable,
            scrollable = n.scrollable, collection = n.collection, selected = n.selected, marked = n.marked,
            left = box.left, top = box.top, right = box.right, bottom = box.bottom,
            canScrollForward = fwd, canScrollBackward = back, shown = n.shown,
        )

        // Returns the copy and the y where the next sibling starts.
        fun lay(n: UiNode, left: Int, top: Int): Pair<UiNode, Int> {
            fixed[n]?.let { b ->
                val c = copy(n, b)
                n.children.forEach { k -> c.add(lay(k, b.left + PAD, b.top + PAD).first) }
                return c to top
            }
            if (n.children.isEmpty()) return copy(n, Box(left, top, W - left, top + LINE)) to top + LINE
            val isList = !listDone && CardFinder.isListContainer(n) && listHeight != null
            if (isList) listDone = true
            val kids = ArrayList<UiNode>()
            var y = top + PAD - (if (isList) scrollY else 0)
            for (k in n.children) {
                val (c, next) = lay(k, left + PAD, y)
                kids += c
                y = next
            }
            val contentBottom = y + PAD
            val bottom = if (isList) top + listHeight!! else contentBottom
            val c = if (isList) {
                copy(n, Box(left, top, W - left, bottom), fwd = contentBottom > bottom, back = scrollY > 0)
            } else {
                copy(n, Box(left, top, W - left, bottom))
            }
            kids.forEach { c.add(it) }
            return c to bottom
        }

        return lay(root, 0, 0).first
    }
}
