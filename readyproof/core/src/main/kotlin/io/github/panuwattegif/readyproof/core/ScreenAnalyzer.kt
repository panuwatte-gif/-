package io.github.panuwattegif.readyproof.core

import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * What lies on top of the watched app (other windows' areas), passed in by the Android layer,
 * plus [inkBox]: where the letters of a text element actually are (a text box can be much wider
 * than its text, e.g. reach under a floating button while the number itself is clear of it).
 */
class ScanContext(
    val occluders: List<Box> = emptyList(),
    val inkBox: (UiNode) -> Box? = { null },
)

/** One order card and whether a screenshot taken right now would show it properly. */
data class CardView(
    val card: Card,
    /** READY / DELAY / DONE observations for this card. */
    val items: List<Item>,
    /**
     * The order number and the line that matters (status, or "Delayed by") are completely on
     * screen and nothing covers them. Only then may a shot count as evidence for this order.
     */
    val full: Boolean,
    /** Where those lines are; a shot is kept only if they have not moved while it was taken. */
    val keyBoxes: List<Box>,
    val status: String?,
    /**
     * Backup check: at least the order number is completely inside the list and no other window
     * covers it (the status line and Grab's own overlays are not checked). Used only when an order
     * could not get a fully checked shot for a while, so no order is left without any photo.
     */
    val gfClear: Boolean = false,
) {
    val gf: String get() = card.gf

    fun has(type: ObsType): Boolean = items.any { it.type == type }
}

/** Everything worth knowing about the screen at one moment. */
data class ScreenAnalysis(
    /** Open order tab; null = no tab bar reports a selected tab. */
    val tab: OrderTab?,
    val views: List<CardView>,
    /** Every order number on screen (cards, banners ...), in reading order. */
    val visible: List<String>,
    /** The element that scrolls the order list, if any. */
    val scroller: UiNode? = null,
    /** History header (only read on the History tab). */
    val header: HistoryHeader? = null,
    /** The Orders tab bar is on screen. */
    val tabsVisible: Boolean = false,
    /** Number badge on the Ready tab, if shown. */
    val readyCount: Int? = null,
) {
    val cards: List<Card> get() = views.map { it.card }
    val items: List<Item> get() = views.flatMap { it.items }.distinctBy { Triple(it.gf, it.type, it.doneAt) }
    val readyTab: Boolean?
        get() = when (tab) {
            OrderTab.READY -> true
            null -> null
            else -> false
        }
    val canScrollForward: Boolean get() = scroller?.canScrollForward == true
    val canScrollBackward: Boolean get() = scroller?.canScrollBackward == true
    val canScroll: Boolean get() = canScrollForward || canScrollBackward

    /** Orders listed in the Ready tab right now (fully visible or not). */
    fun readyGfs(): List<String> = views.filter { it.has(ObsType.READY) }.map { it.gf }.distinct()

    fun readyViews(): List<CardView> = views.filter { it.has(ObsType.READY) }

    /** History rows: finished or cancelled orders. */
    fun historyViews(): List<CardView> = views.filter { it.has(ObsType.DONE) || it.has(ObsType.CANCELLED) }

    /**
     * Which order sits where: changes whenever the list moves. Used to tell whether a scroll or a
     * drag really did something, because Grab does not always say whether its list can scroll.
     */
    fun motionKey(): String = views.joinToString("|") { it.gf + "@" + it.card.gfNode.box().top }
}

object ScreenAnalyzer {

    /**
     * [allowUnknownDelayed]: keep a "Delayed" row even when its finish time cannot be read (History
     * sweeps), so the report knows it exists; it can never be paired by order number alone.
     */
    fun analyze(
        roots: List<UiNode>,
        cfg: Config,
        ctx: ScanContext = ScanContext(),
        allowUnknownDelayed: Boolean = false,
    ): ScreenAnalysis {
        val gfx = cfg.gfExtractor()
        val rules = StatusRules(cfg)
        val tab = TabDetector.openTab(roots, cfg)
        val views = ArrayList<CardView>()
        val visible = LinkedHashSet<String>()
        for (root in roots) {
            val finder = CardFinder(gfx, root)
            visible += finder.visibleGfs()
            val occluderCache = IdentityHashMap<UiNode, List<Box>>()
            val found = finder.cards().map { card ->
                val observed = rules.evaluate(card, allowUnknownDelayed || tab == OrderTab.HISTORY)
                val history = observed.filter { it.type == ObsType.DONE || it.type == ObsType.DELAY || it.type == ObsType.CANCELLED }
                val status = statusLine(card, gfx)
                val ready = when (tab) {
                    // Everything listed under the Ready tab is done and waiting, whatever its status says.
                    OrderTab.READY -> if (card.inList && history.isEmpty()) {
                        Item(card.gf, ObsType.READY, status = status, card = card.texts)
                    } else {
                        null
                    }
                    OrderTab.HISTORY, OrderTab.PREPARING, OrderTab.OTHER -> null
                    null -> observed.firstOrNull { it.type == ObsType.READY }
                }
                val items = listOfNotNull(ready) + history
                val vp = viewport(card, root)
                val occluders = ctx.occluders + (card.list?.let { l -> occluderCache.getOrPut(l) { inAppOccluders(root, l, vp) } } ?: emptyList())
                Found(card, items, status, keyNodes(card, items, cfg, gfx), vp, occluders)
            }
            for (f in found) {
                val edges = openEdges(f, found)
                val boxes = f.keys.map { it.box() }
                // A key line that is not drawn at all (scrolled away) also means "not in the picture".
                val full = f.keys.isNotEmpty() && f.keys.all { it.shown } &&
                    f.keys.all { k -> clear(k, f.vp, f.occluders, ctx, edges.of(k, f.card)) }
                val gfClear = f.card.gfNode.shown && clear(f.card.gfNode, f.vp, ctx.occluders, ctx, edges.of(f.card.gfNode, f.card))
                views += CardView(f.card, f.items, full, boxes, f.status, gfClear)
            }
        }
        val scroller = pickScroller(roots, views)
        return ScreenAnalysis(
            tab = tab,
            views = views,
            visible = visible.toList(),
            scroller = scroller,
            header = if (tab == OrderTab.HISTORY) HistoryReader.read(roots, cfg) else null,
            tabsVisible = TabDetector.barVisible(roots, cfg.tabLabels, gfx),
            readyCount = if (tab == OrderTab.READY) TabDetector.readyCount(roots, cfg) else null,
        )
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

    /** [k] is completely inside the list area and nothing covers its letters. */
    private fun clear(k: UiNode, vp: Box, occluders: List<Box>, ctx: ScanContext, edges: Edges = Edges.CLOSED): Boolean {
        val b = k.box()
        if (!fullyInside(b, vp, edges)) return false
        val hits = occluders.filter { it.intersects(b) }
        if (hits.isEmpty()) return true
        val ink = ctx.inkBox(k) ?: return false
        return !ink.isEmpty && hits.none { it.intersects(ink) }
    }

    /**
     * True when [b] lies completely inside [vp]. Touching the top or bottom edge normally means cut
     * off (Android shrinks a half-scrolled element to the visible part), unless [edges] says that
     * edge is known not to cut this card.
     */
    fun fullyInside(b: Box, vp: Box, edges: Edges = Edges.CLOSED): Boolean =
        !b.isEmpty && !vp.isEmpty && b.left >= vp.left && b.right <= vp.right &&
            (b.top > vp.top || (edges.top && b.top >= vp.top - EDGE_PX)) &&
            (b.bottom < vp.bottom || (edges.bottom && b.bottom <= vp.bottom + EDGE_PX))

    /** A card as found on screen, before deciding whether a shot would show it completely. */
    private class Found(
        val card: Card,
        val items: List<Item>,
        val status: String?,
        val keys: List<UiNode>,
        val vp: Box,
        val occluders: List<Box>,
    ) {
        val box: Box get() = card.node.box()
        val ready: Boolean get() = items.any { it.type == ObsType.READY }
    }

    /** List edges a card may touch without being cut off (see [openEdges]). */
    class Edges(val top: Boolean, val bottom: Boolean) {
        /** Applies to [k] only where it starts / ends with its card (the lines inside a card never touch the edge). */
        fun of(k: UiNode, card: Card): Edges {
            if (!top && !bottom) return CLOSED
            val c = card.node.box()
            val b = k.box()
            return Edges(top && abs(b.top - c.top) <= EDGE_PX, bottom && abs(b.bottom - c.bottom) <= EDGE_PX)
        }

        companion object {
            val CLOSED = Edges(top = false, bottom = false)
        }
    }

    private const val EDGE_PX = 2

    /**
     * Grab on the shop's phone reports each Ready order as one element that includes the gap above
     * the card, so the first order starts exactly at the top of the list although nothing of it is
     * cut off (10 Oct: the top order was never photographed). An order touching an edge of the
     * list still counts as complete when:
     *  - it is as tall as the orders lying fully inside the same list (a cut-off one is shorter), or
     *  - it is the only order and the list below it is mostly empty (nothing scrolled away above).
     * Only Ready orders: History rows keep the strict rule that works there.
     */
    private fun openEdges(f: Found, all: List<Found>): Edges {
        if (!f.ready || f.card.list == null) return Edges.CLOSED
        val b = f.box
        val vp = f.vp
        if (b.isEmpty || vp.isEmpty) return Edges.CLOSED
        val atTop = abs(b.top - vp.top) <= EDGE_PX
        val atBottom = abs(b.bottom - vp.bottom) <= EDGE_PX
        if (!atTop && !atBottom) return Edges.CLOSED
        val sameList = all.filter { it !== f && it.ready && it.card.list === f.card.list }
        val reference = sameList.map { it.box }
            .filter { !it.isEmpty && it.top > vp.top + EDGE_PX && it.bottom < vp.bottom - EDGE_PX }
            .maxOfOrNull { it.height }
        val complete = when {
            reference != null -> b.height * 100 >= reference * 97
            sameList.isEmpty() && atTop && !atBottom -> (vp.bottom - b.bottom) * 3 >= vp.height
            else -> false
        }
        return if (complete) Edges(top = atTop, bottom = atBottom) else Edges.CLOSED
    }

    /** The lines a shot must show for this card: its number plus the status or delay line. */
    private fun keyNodes(card: Card, items: List<Item>, cfg: Config, gfx: GfExtractor): List<UiNode> {
        val second: UiNode? = when {
            items.any { it.type == ObsType.DELAY } -> nodeWith(card, gfx) { TextNorm.containsAny(it, cfg.delayAny) }
            items.any { it.type == ObsType.DONE } -> nodeWith(card, gfx) { TextNorm.containsAny(it, cfg.doneAny) }
            items.any { it.type == ObsType.CANCELLED } -> nodeWith(card, gfx) { TextNorm.containsAny(it, cfg.cancelAny) }
            else -> nodeWith(card, gfx) { gfx.extract(it).isEmpty() }
        }
        return listOfNotNull(card.gfNode, second).distinct()
    }

    /**
     * First element of the card whose text passes [test] (shown or not: a line scrolled out of
     * view must still be found so the card counts as cut off); handles flattened lists too.
     */
    private fun nodeWith(card: Card, gfx: GfExtractor, test: (String) -> Boolean): UiNode? {
        if (card.node !== card.gfNode) {
            return card.node.walk().firstOrNull { n -> n !== card.gfNode && n.ownStrings().any(test) }
        }
        // One element holding the whole card ("GF-396, Finding a driver...").
        if (card.gfNode.ownStrings().any { s -> gfx.strip(s).trim(' ', ',', '|').let { it.isNotEmpty() && test(it) } }) {
            return card.gfNode
        }
        val parent = card.gfNode.parent ?: return null
        val idx = parent.children.indexOfFirst { it === card.gfNode }
        for (i in idx + 1 until parent.children.size) {
            val sib = parent.children[i]
            if (sib.walk().any { n -> n.ownStrings().any { gfx.extract(it).isNotEmpty() } }) break
            sib.walk().firstOrNull { n -> n.ownStrings().any(test) }?.let { return it }
        }
        return null
    }

    /** The part of the screen where the card's list is drawn. */
    private fun viewport(card: Card, root: UiNode): Box {
        var vp = root.box()
        for (n in listOfNotNull(card.list, card.scroller)) {
            val b = n.box()
            if (!b.isEmpty) vp = intersect(vp, b)
        }
        return vp
    }

    /**
     * Elements of the same app drawn on top of the list: anything after the list in drawing order
     * that overlaps it (a floating button, a bottom bar drawn over the list), as long as it is
     * not a big see-through container.
     */
    private fun inAppOccluders(root: UiNode, list: UiNode, vp: Box): List<Box> {
        val all = root.walk().toList()
        val listIdx = all.indexOfFirst { it === list }
        if (listIdx < 0) return emptyList()
        val listSize = list.walk().count()
        val vpArea = vp.width.toLong() * vp.height
        return all.drop(listIdx + listSize).filter { n ->
            val b = n.box()
            n.shown && !b.isEmpty && b.intersects(vp) &&
                (n.clickable || n.ownStrings().isNotEmpty()) &&
                b.width.toLong() * b.height < vpArea * 4 / 10
        }.map { it.box() }
    }

    private fun pickScroller(roots: List<UiNode>, views: List<CardView>): UiNode? {
        views.mapNotNull { it.card.scroller }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key }
        return roots.flatMap { r ->
            r.walk().filter { it.shown && !it.horizontal && (it.scrollable || it.canScrollForward || it.canScrollBackward) }.toList()
        }
            .maxByOrNull { it.box().width.toLong() * it.box().height }
    }

    private fun intersect(a: Box, b: Box): Box =
        Box(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom))
}
