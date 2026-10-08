package io.github.panuwattegif.readyproof.core

import java.time.LocalDate

object HistoryDates {
    /**
     * Deliberately narrow: an explicit day + month + year ("Today, 08 Oct 2026", "ส. 3 ต.ค. 2569"),
     * a bare Today / Yesterday, or an ISO date. Anything else remains UNKNOWN, never guessed.
     */
    fun parse(text: String, today: LocalDate): LocalDate? {
        HistoryReader.parseDate(text)?.let { return it }
        return when (TextNorm.key(text)) {
            "today", "วันนี้" -> today
            "yesterday", "เมื่อวาน", "เมื่อวานนี้" -> today.minusDays(1)
            else -> runCatching { LocalDate.parse(text.trim()) }.getOrNull()
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
