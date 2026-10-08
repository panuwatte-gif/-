package io.github.panuwattegif.readyproof.core

data class HistoryTotals(val completed: Int, val cancelled: Int, val total: Int) {
    val consistent: Boolean get() = completed + cancelled == total
}

object HistoryTotalsParser {
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
