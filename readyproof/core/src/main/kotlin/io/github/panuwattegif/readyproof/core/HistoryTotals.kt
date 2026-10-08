package io.github.panuwattegif.readyproof.core

data class HistoryTotals(val completed: Int, val cancelled: Int, val total: Int) {
    val consistent: Boolean get() = completed + cancelled == total
}

object HistoryTotalsParser {
    fun parseRoots(roots: List<UiNode>, cfg: Config): HistoryTotals? {
        val cards = roots.flatMap { CardFinder(cfg.gfExtractor(), it).cards() }.flatMap { it.node.walk().toList() }.toSet()
        val nodes = roots.flatMap { it.walk().toList() }.filter { it !in cards }
        parse(nodes.flatMap { it.ownStrings() })?.let { return it }
        fun count(labels: List<String>): Int? {
            val matches = nodes.mapIndexedNotNull { index, n ->
                if (n.ownStrings().none { t -> labels.any { TextNorm.key(it) == TextNorm.key(t) } }) return@mapIndexedNotNull null
                val nearby = nodes.filter { m ->
                    m.ownStrings().any { it.trim().matches(Regex("\\d+")) } && m.bottom > m.top && n.bottom > n.top &&
                        minOf(m.right, n.right) > maxOf(m.left, n.left) &&
                        m.top >= n.top && m.top - n.bottom <= (n.bottom - n.top) * 3
                }.minByOrNull { it.top - n.top }
                val text = nearby?.ownStrings()?.firstOrNull()
                    ?: nodes.getOrNull(index + 1)?.ownStrings()?.firstOrNull()
                text?.trim()?.toIntOrNull()
            }.distinct()
            return matches.singleOrNull()
        }
        val done = count(listOf("Completed", "สำเร็จ", "เสร็จสมบูรณ์")) ?: return null
        val cancelled = count(listOf("Cancelled", "Canceled", "ยกเลิก")) ?: return null
        return HistoryTotals(done, cancelled, done + cancelled)
    }

    fun parse(texts: List<String>): HistoryTotals? {
        fun count(labels: String): Int? {
            val values = texts.flatMap { text ->
                Regex("(?:^|[\\s|,])(?:$labels)\\s*[:：]?\\s*(\\d+)\\s*(?:orders?|ออเดอร์|รายการ)?(?:$|[\\s|,])", RegexOption.IGNORE_CASE)
                    .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            }.distinct()
            return values.singleOrNull()
        }
        val completed = count("Completed|สำเร็จ|เสร็จสมบูรณ์") ?: return null
        val cancelled = count("Cancelled|Canceled|ยกเลิก") ?: return null
        val total = count("Total orders|Orders total|Total|ออเดอร์ทั้งหมด|คำสั่งซื้อทั้งหมด|ทั้งหมด") ?: completed + cancelled
        return HistoryTotals(completed, cancelled, total)
    }
}
