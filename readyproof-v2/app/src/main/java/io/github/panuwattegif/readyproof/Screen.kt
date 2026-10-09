package io.github.panuwattegif.readyproof

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import proof.Card
import proof.Header
import proof.Page
import proof.PageKind
import proof.Status

internal data class CardView(val card: Card, val bounds: Rect)
internal data class Screen(val page: Page, val views: List<CardView>, val scroller: AccessibilityNodeInfo?)

internal object ScreenReader {
    private val gf = Regex("""GF[-\s]?\d+""", RegexOption.IGNORE_CASE)
    private val delayed = Regex("""\bDelayed\b|ล่าช้า""", RegexOption.IGNORE_CASE)
    private val completed = Regex("""Completed|สำเร็จ""", RegexOption.IGNORE_CASE)
    private val cancelled = Regex("""Cancelled|Canceled|ยกเลิก""", RegexOption.IGNORE_CASE)
    private data class TextNode(val text: String, val bounds: Rect, val node: AccessibilityNodeInfo)

    fun read(root: AccessibilityNodeInfo, width: Int, height: Int): Screen {
        val text = mutableListOf<TextNode>()
        var scroller: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            val b = Rect(); node.getBoundsInScreen(b)
            val t = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!t.isNullOrBlank()) text += TextNode(t, b, node)
            if (node.isScrollable && scroller == null && b.height() > height / 3) scroller = node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        // Prefer the selected tab. A label elsewhere on the page is insufficient.
        fun selected(label: Regex) = text.any { label.containsMatchIn(it.text) &&
            (it.node.isSelected || it.node.parent?.isSelected == true) }
        val kind = when {
            selected(Regex("""^Ready$|พร้อมจัดส่ง""", RegexOption.IGNORE_CASE)) -> PageKind.READY
            selected(Regex("""^History$|ประวัติ""", RegexOption.IGNORE_CASE)) -> PageKind.HISTORY
            else -> PageKind.OTHER
        }
        if (kind == PageKind.OTHER) return Screen(Page(kind, emptyList()), emptyList(), scroller)
        val views = text.mapNotNull { item ->
            val match = gf.find(item.text) ?: return@mapNotNull null
            var n = item.node
            var box = item.bounds
            while (n.parent != null) {
                val parent = n.parent ?: break
                val candidate = Rect(); parent.getBoundsInScreen(candidate)
                if (candidate.width() > width * 0.96 || candidate.height() > height * 0.55) break
                n = parent; box = candidate
                if (n.isClickable && box.height() >= 60) break
            }
            if (box.top < 0 || box.bottom > height || box.height() < 40) return@mapNotNull null
            val within = text.filter { box.contains(it.bounds.centerX(), it.bounds.centerY()) }
            // Reject an ancestor that has swallowed a second GF card.
            if (within.mapNotNull { gf.find(it.text)?.value }.distinct().size > 1) return@mapNotNull null
            val label = within.joinToString(" ") { it.text }
            val status = when {
                cancelled.containsMatchIn(label) -> Status.CANCELLED
                completed.containsMatchIn(label) -> Status.COMPLETED
                else -> null
            }
            if (kind == PageKind.HISTORY && status == null) return@mapNotNull null
            val clock = Regex("""(?:Completed|Cancelled|Canceled|สำเร็จ|ยกเลิก).*?(\d{1,2}:\d{2}\s*(?:AM|PM)?)""",
                RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)
            val amount = Regex("""\b\d+[.,]\d{2}\b""").find(label)?.value
            CardView(Card(match.value.uppercase().replace(" ", "-"), box.top, box.bottom,
                status, clock, amount, kind == PageKind.HISTORY && delayed.containsMatchIn(label)), Rect(box))
        }.distinctBy { it.card.gf to it.bounds.top }
        val header = if (kind == PageKind.HISTORY) parseHeader(text.map { it.text }) else null
        return Screen(Page(kind, views.map { it.card }, header), views, scroller)
    }

    // A missing header remains UNKNOWN; no inference from the number of cards.
    private fun parseHeader(lines: List<String>): Header {
        val joined = lines.take(80).joinToString(" ")
        fun count(vararg labels: String): Int? = labels.firstNotNullOfOrNull { label ->
            Regex("""(?:$label)\s*[:：(]?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(joined)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\s*(?:$label)""", RegexOption.IGNORE_CASE)
                    .find(joined)?.groupValues?.get(1)?.toIntOrNull()
        }
        return Header(count("Total", "All", "ทั้งหมด"), count("Completed", "สำเร็จ"),
            count("Cancelled", "Canceled", "ยกเลิก"))
    }
}
