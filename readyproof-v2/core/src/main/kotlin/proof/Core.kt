package proof

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PageKind { READY, HISTORY, OTHER }
enum class Status { COMPLETED, CANCELLED }
enum class CaseKind { MATCHED, NO_READY, UNKNOWN }
data class Card(
    val gf: String, val top: Int, val bottom: Int,
    val status: Status? = null, val completedAt: String? = null,
    val amount: String? = null, val delayed: Boolean = false
) {
    // The label belongs to this card only. A screenshot can contain other cards.
    val signature get() = listOf(gf, status?.name ?: "", completedAt ?: "", amount ?: "").joinToString("|")
}
data class Header(val total: Int?, val completed: Int?, val cancelled: Int?) {
    val consistent get() = total != null && completed != null && cancelled != null &&
        total == completed + cancelled
}
data class Page(val kind: PageKind, val cards: List<Card>, val header: Header? = null)
data class Ready(val id: String, val date: String, val gf: String, val firstSeen: Long,
                 val context: String, val image: String?)
data class History(val id: String, val date: String, val card: Card, val captured: Long,
                   val image: String?)
data class Case(val history: History, val kind: CaseKind, val ready: Ready?)
data class Audit(val header: Header?, val rows: Int, val images: Int, val stuck: Boolean) {
    val complete: Boolean get() = !stuck && header?.consistent == true &&
        header.total == rows && rows == images
    val missingRows: Int? get() = header?.total?.let { (it - rows).coerceAtLeast(0) }
}

object Logic {
    // A missing frame does not end an instance; a later reappearance does.
    fun instance(existing: List<Ready>, date: String, gf: String, now: Long,
                 context: String, activeIds: Set<String>): Ready {
        val prior = existing.filter { it.date == date && it.gf == gf }.maxByOrNull { it.firstSeen }
        if (prior != null && prior.id in activeIds) return prior
        if (prior != null && now - prior.firstSeen < 120_000 && prior.context == context) return prior
        return Ready("$date:$gf:$now", date, gf, now, context, null)
    }
    fun needCapture(cards: List<Card>, orders: List<Ready>): List<Ready> =
        orders.filter { row -> row.image == null && cards.any { it.gf == row.gf } }

    fun match(history: List<History>, ready: List<Ready>): List<Case> =
        history.filter { it.card.delayed && it.image != null }.map { h ->
            val candidates = ready.filter { r ->
                r.gf == h.card.gf && r.date == h.date && r.image != null &&
                    r.firstSeen <= h.captured && plausible(r.firstSeen, h.date, h.card.completedAt)
            }
            when (candidates.size) {
                0 -> Case(h, CaseKind.NO_READY, null)
                1 -> Case(h, CaseKind.MATCHED, candidates.single())
                else -> Case(h, CaseKind.UNKNOWN, null)
            }
        }
    private fun plausible(firstSeen: Long, date: String, completed: String?): Boolean {
        if (completed == null) return true
        return try {
            val d = LocalDate.parse(date)
            val rx = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)?""", RegexOption.IGNORE_CASE)
            val m = rx.find(completed) ?: return true
            var hr = m.groupValues[1].toInt()
            val ap = m.groupValues[3].uppercase(Locale.US)
            if (ap.isNotEmpty()) hr = hr % 12 + if (ap == "PM") 12 else 0
            val end = d.atTime(hr, m.groupValues[2].toInt()).atZone(java.time.ZoneId.of("Asia/Bangkok")).toInstant().toEpochMilli()
            firstSeen <= end + 60_000
        } catch (_: Exception) { true }
    }
    fun audit(header: Header?, history: List<History>, stuck: Boolean): Audit =
        Audit(header, history.map { it.id }.distinct().size,
            history.filter { it.image != null }.map { it.id }.distinct().size, stuck)

    fun report(shop: String, date: String, audit: Audit, cases: List<Case>,
               observedDelayed: Int = cases.size): String {
        val delayed = observedDelayed
        val paired = cases.filter { it.kind == CaseKind.MATCHED }
        val alone = cases.filter { it.kind == CaseKind.NO_READY }
        val unknown = cases.filter { it.kind == CaseKind.UNKNOWN }
        val total = if (audit.complete) audit.header?.total else null
        fun pct(n: Int) = total?.takeIf { it > 0 }?.let {
            String.format(Locale.US, "%.2f%%", n * 100.0 / it)
        } ?: "UNKNOWN"
        return buildString {
            appendLine("$shop — $date")
            appendLine("History: ${if (audit.complete) "ครบ" else "ยังไม่ยืนยัน/ชั่วคราว"}")
            appendLine("Total orders = ${total ?: "UNKNOWN"} (Completed ${audit.header?.completed ?: "UNKNOWN"} + Cancelled ${audit.header?.cancelled ?: "UNKNOWN"})")
            appendLine("Grab delayed = $delayed (${pct(delayed)})")
            appendLine("ภาพ DELAY ที่ยังขาด = ${(observedDelayed - cases.size).coerceAtLeast(0)}")
            appendLine("Delayed + Ready = ${paired.size}: ${paired.joinToString { it.history.card.gf }}")
            appendLine("Delayed ไม่มี Ready = ${alone.size}: ${alone.joinToString { it.history.card.gf }}")
            appendLine("UNKNOWN = ${unknown.size}: ${unknown.joinToString { it.history.card.gf }}")
            appendLine("Actual delayed = ${(delayed - paired.size).coerceAtLeast(0)} (${pct((delayed - paired.size).coerceAtLeast(0))})")
            appendLine("รายออเดอร์ ${audit.rows}, ภาพ History ${audit.images}, ขาดจากยอดหัวหน้า ${audit.missingRows ?: "UNKNOWN"}, ภาพขาด ${audit.rows - audit.images}")
        }
    }
    fun filename(gf: String, role: String, date: String, repeat: Boolean, millis: Long): String {
        val day = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("ddMMM", Locale.US)).uppercase(Locale.US)
        val suffix = if (repeat) "_" + java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Asia/Bangkok"))
            .format(DateTimeFormatter.ofPattern("HHmmss")) else ""
        return "${gf}_${role}_${day}${suffix}.jpg"
    }
}

class SweepGate {
    var mode = PageKind.READY
        private set
    var date: String? = null
        private set
    fun start(date: String) { LocalDate.parse(date); this.date = date; mode = PageKind.HISTORY }
    fun stop() { mode = PageKind.READY; date = null }
    fun allows(page: PageKind) = page == mode
}
