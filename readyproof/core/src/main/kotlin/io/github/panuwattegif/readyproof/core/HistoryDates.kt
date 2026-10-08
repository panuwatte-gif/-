package io.github.panuwattegif.readyproof.core

import java.time.LocalDate

object HistoryDates {
    private val months = listOf(
        listOf("jan", "january", "ม.ค.", "มกราคม"), listOf("feb", "february", "ก.พ.", "กุมภาพันธ์"),
        listOf("mar", "march", "มี.ค.", "มีนาคม"), listOf("apr", "april", "เม.ย.", "เมษายน"),
        listOf("may", "พ.ค.", "พฤษภาคม"), listOf("jun", "june", "มิ.ย.", "มิถุนายน"),
        listOf("jul", "july", "ก.ค.", "กรกฎาคม"), listOf("aug", "august", "ส.ค.", "สิงหาคม"),
        listOf("sep", "sept", "september", "ก.ย.", "กันยายน"), listOf("oct", "october", "ต.ค.", "ตุลาคม"),
        listOf("nov", "november", "พ.ย.", "พฤศจิกายน"), listOf("dec", "december", "ธ.ค.", "ธันวาคม")
    )
    private fun datedHeader(text: String): LocalDate? {
        val match = Regex("(?<!\\d)(\\d{1,2})\\s+([A-Za-zก-๿.]+)\\s*,?\\s+(\\d{4})(?!\\d)").find(text) ?: return null
        val month = months.indexOfFirst { keys -> keys.any { it.equals(match.groupValues[2], true) } } + 1
        if (month == 0) return null
        val year = match.groupValues[3].toInt().let { if (it > 2400) it - 543 else it }
        return runCatching { LocalDate.of(year, month, match.groupValues[1].toInt()) }.getOrNull()
    }
    /** Deliberately narrow: unsupported localized headers remain UNKNOWN, never guessed. */
    fun parse(text: String, today: LocalDate): LocalDate? {
        val numeric = Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").matchEntire(text.trim())
        if (numeric != null) {
            val year = numeric.groupValues[3].toInt().let { if (it > 2400) it - 543 else it }
            return runCatching { LocalDate.of(year, numeric.groupValues[2].toInt(), numeric.groupValues[1].toInt()) }.getOrNull()
        }
        return when (TextNorm.key(text)) {
            "today", "วันนี้" -> today
            "yesterday", "เมื่อวาน", "เมื่อวานนี้" -> today.minusDays(1)
            else -> datedHeader(text) ?: runCatching { LocalDate.parse(text.trim()) }.getOrNull()
        }
    }

    fun assign(items: List<Item>, analysis: ScreenAnalysis, roots: List<UiNode>, today: LocalDate,
               previousHeader: LocalDate?): Pair<List<Item>, LocalDate?> {
        val headers = roots.flatMap { it.walk().toList() }.mapNotNull { n ->
            n.ownStrings().firstNotNullOfOrNull { parse(it, today) }?.let { n.top to it }
        }.sortedBy { it.first }
        val assigned = items.map { item ->
            if (item.type !in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED)) item else {
                val card = analysis.cards.firstOrNull { c -> c.gf == item.gf && c.texts == item.card }
                val day = headers.lastOrNull { it.first <= (card?.node?.top ?: Int.MIN_VALUE) }?.second ?: previousHeader
                item.copy(historyDate = day?.toString())
            }
        }
        return assigned to (headers.lastOrNull()?.second ?: previousHeader)
    }
}
