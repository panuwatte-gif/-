package io.github.panuwattegif.readyproof.core

import java.time.LocalDate

/** Top of the History tab: which day is shown and Grab's own totals for it. */
data class HistoryHeader(
    /** "Today, 08 Oct 2026" -> 2026-10-08; null when no date is on screen. */
    val date: LocalDate?,
    /** "Completed 77". */
    val completed: Int?,
    /** "Cancelled 0". */
    val cancelled: Int?,
) {
    val hasCounts: Boolean get() = completed != null
}

object HistoryReader {
    private val INT = Regex("""^\d{1,3}(?:,\d{3})*$|^\d{1,6}$""")
    // "08 Oct 2026", "3 ต.ค. 2569", "8 ตุลาคม 2569", "Oct 8, 2026"
    private val DMY = Regex("""(?<!\d)(\d{1,2})\s+([A-Za-z฀-๿.]+)\s*,?\s+(\d{4})(?!\d)""")
    private val MDY = Regex("""(?<![A-Za-z])([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})(?!\d)""")

    private val MONTHS: Map<String, Int> = buildMap {
        val en = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val enFull = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        val thShort = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")
        val thFull = listOf(
            "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
            "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
        )
        for (i in 0 until 12) {
            put(en[i], i + 1)
            put(enFull[i], i + 1)
            put(thShort[i], i + 1)
            put(thShort[i].replace(".", ""), i + 1)
            put(thFull[i], i + 1)
        }
        put("sept", 9)
    }

    /** Reads the header if it is on screen (it scrolls away with the list). */
    fun read(roots: List<UiNode>, cfg: Config): HistoryHeader {
        val gfx = cfg.gfExtractor()
        val nodes = roots.flatMap { r -> r.walk().filter { it.shown }.toList() }
        // Texts inside order cards never belong to the header.
        val cardTexts = roots.flatMap { CardFinder(gfx, it).cards() }.flatMap { it.node.walk().toList() }.toHashSet()
        val free = nodes.filter { it !in cardTexts }
        var date: LocalDate? = null
        for (n in free) {
            for (s in n.ownStrings()) {
                date = parseDate(s)
                if (date != null) break
            }
            if (date != null) break
        }
        return HistoryHeader(date, count(free, cfg.completedLabels), count(free, cfg.cancelledLabels))
    }

    /**
     * The number that belongs to a label: in the same text ("Completed 77") or the closest number
     * right under / beside the label. Position decides, so "Completed  Cancelled / 77  0" laid out
     * as a grid still pairs correctly.
     */
    fun count(nodes: List<UiNode>, labels: List<String>): Int? {
        val keys = labels.map { TextNorm.key(it) }.filter { it.isNotEmpty() }
        // Both totals in one line: "เสร็จสมบูรณ์ 77 ยกเลิก 0" (one element on the shop's phone).
        for (n in nodes) for (s in n.ownStrings()) countIn(s, labels)?.let { return it }
        for ((i, n) in nodes.withIndex()) {
            val s = n.ownStrings().firstOrNull() ?: continue
            val k = TextNorm.key(s)
            val key = keys.firstOrNull { k == it || (k.startsWith(it) && INT.matches(k.substring(it.length).trim())) }
                ?: continue
            if (k != key) return toInt(k.substring(key.length).trim())
            val label = n.box()
            if (!label.isEmpty) {
                val below = nodes.filter { m -> isInt(m) && !m.box().isEmpty }.map { it to it.box() }.filter { (_, b) ->
                    val overlapX = minOf(b.right, label.right) - maxOf(b.left, label.left)
                    val under = b.top >= label.top + label.height / 2 && b.top - label.bottom <= label.height * 3
                    val beside = b.left >= label.right - 2 && b.left - label.right <= label.height * 3 &&
                        b.top < label.bottom && b.bottom > label.top
                    (overlapX > 0 && under) || beside
                }.minByOrNull { (_, b) -> (b.top - label.top) + (b.left - label.left).coerceAtLeast(0) }
                if (below != null) return toInt(below.first.ownStrings().first())
            }
            // No positions: the next text, if it is a number.
            val next = nodes.getOrNull(i + 1) ?: continue
            if (isInt(next)) return toInt(next.ownStrings().first())
        }
        return null
    }

    /**
     * The number right after one of [labels] anywhere in [text]: "เสร็จสมบูรณ์ 77 ยกเลิก 0" -> 77 for
     * "เสร็จสมบูรณ์", 0 for "ยกเลิก". A clock time never counts ("เสร็จสมบูรณ์เมื่อ 7:32" has a word
     * between, and "Completed 7:32" is a time).
     */
    fun countIn(text: String, labels: List<String>): Int? {
        val t = TextNorm.clean(text)
        for (label in labels) {
            val l = TextNorm.clean(label)
            if (l.isEmpty()) continue
            val m = Regex(Regex.escape(l) + """\s*[:：]?\s*(\d{1,3}(?:,\d{3})+|\d{1,6})(?![\d:.,])""", RegexOption.IGNORE_CASE).find(t)
                ?: continue
            return toInt(m.groupValues[1])
        }
        return null
    }

    fun parseDate(text: String): LocalDate? {
        val t = TextNorm.clean(text)
        DMY.find(t)?.let { m ->
            val month = MONTHS[TextNorm.key(m.groupValues[2])] ?: MONTHS[TextNorm.key(m.groupValues[2]).trimEnd('.')]
            if (month != null) return date(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())
        }
        MDY.find(t)?.let { m ->
            val month = MONTHS[TextNorm.key(m.groupValues[1])]
            if (month != null) return date(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
        }
        return null
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? {
        // Thai Buddhist years (2569) are 543 ahead.
        val y = if (year > 2400) year - 543 else year
        return try {
            LocalDate.of(y, month, day)
        } catch (e: Exception) {
            null
        }
    }

    private fun isInt(n: UiNode): Boolean = n.ownStrings().firstOrNull()?.let { INT.matches(it.trim()) } ?: false

    private fun toInt(s: String): Int? = s.replace(",", "").trim().toIntOrNull()
}
