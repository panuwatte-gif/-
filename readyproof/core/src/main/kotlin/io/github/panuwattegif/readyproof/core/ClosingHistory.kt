package io.github.panuwattegif.readyproof.core

import java.time.LocalTime

enum class ClosingTab(val labels: List<String>) {
    READY(listOf("Ready", "พร้อมจัดส่ง")),
    PREPARING(listOf("Preparing", "กำลังเตรียม")),
}

enum class QueueState { EMPTY, BUSY, UNKNOWN }

/** No GF is not proof of an empty queue: require a zero badge or explicit empty-state text. */
object ClosingQueueAnalyzer {
    private val emptyLabels = listOf(
        "No orders", "No orders yet", "No active orders", "You have no orders",
        "ไม่มีออเดอร์", "ยังไม่มีออเดอร์", "ไม่มีคำสั่งซื้อ", "ยังไม่มีคำสั่งซื้อ",
        "ไม่มีรายการสั่งซื้อ", "ไม่มีรายการ",
    ).map(TextNorm::key)
    private val unavailable = listOf("loading", "กำลังโหลด", "try again", "something went wrong",
        "ลองใหม่", "เกิดข้อผิดพลาด", "connection", "เชื่อมต่อ")

    fun selected(roots: List<UiNode>, labels: List<String>): Boolean = roots.any { root ->
        root.walk().any { n -> n.ownStrings().any { s -> labels.any { TabDetector.isLabel(s, it) } } &&
            (n.selected || n.parent?.selected == true || n.parent?.parent?.selected == true) }
    }

    fun inspect(roots: List<UiNode>, cfg: Config, tab: ClosingTab, navigationAccepted: Boolean): QueueState {
        if (roots.isEmpty()) return QueueState.UNKNOWN
        val nodes = roots.flatMap { it.walk().toList() }
        val labels = if (tab == ClosingTab.READY) cfg.readyTabLabels else tab.labels
        val tabNodes = nodes.filter { n -> n.ownStrings().any { s -> labels.any { TabDetector.isLabel(s, it) } } }
        if (tabNodes.isEmpty()) return QueueState.UNKNOWN
        val otherLabels = (cfg.tabLabels + ClosingTab.PREPARING.labels).filter { l ->
            labels.none { TextNorm.key(it) == TextNorm.key(l) }
        }
        // Some Grab versions omit selection. A successful tab action may be used only when no
        // other tab is selected, after the Android adapter has allowed the page to settle.
        if (selected(roots, otherLabels) || (!selected(roots, labels) && !navigationAccepted)) return QueueState.UNKNOWN
        val texts = nodes.flatMap { it.ownStrings() }.map(TextNorm::key)
        if (texts.any { s -> unavailable.any { s.contains(it) } }) return QueueState.UNKNOWN
        if (nodes.any { n -> n.ownStrings().any { cfg.gfExtractor().extract(it).isNotEmpty() } }) return QueueState.BUSY
        val counts = tabNodes.flatMap { n ->
            val own = n.ownStrings().mapNotNull { badge(it, labels) }
            // Material tabs often expose label and numeric badge as separate siblings. Do not
            // inspect a whole tab bar and accidentally borrow Preparing's count for Ready.
            val p = n.parent
            val siblings = if (p != null && p.walk().flatMap { it.ownStrings().asSequence() }
                    .none { s -> otherLabels.any { TabDetector.isLabel(s, it) } }) {
                p.children.flatMap { it.ownStrings() }.mapNotNull { it.trim().toIntOrNull() }
            } else emptyList()
            own + siblings
        }
        if (counts.any { it > 0 }) return QueueState.BUSY
        if (counts.any { it == 0 } || texts.any { it in emptyLabels }) return QueueState.EMPTY
        return QueueState.UNKNOWN
    }

    private fun badge(raw: String, labels: List<String>): Int? {
        val t = TextNorm.key(raw)
        val label = labels.firstOrNull { TabDetector.isLabel(t, it) } ?: return null
        return Regex("\\d+").find(t.substring(TextNorm.key(label).length))?.value?.toIntOrNull()
    }
}

/** A fresh Ready -> Preparing -> Ready check; EMPTY must be stable on every visited tab. */
class ClosingHistoryGate(private val startedAt: Long) {
    enum class Result { WAIT, NEXT_TAB, OPEN_HISTORY, RETRY }
    var tab: ClosingTab = ClosingTab.READY
        private set
    private var step = 0
    private var emptySince: Long? = null

    fun observe(state: QueueState, now: Long): Result {
        if (now - startedAt >= 45_000L || state == QueueState.BUSY) return Result.RETRY
        if (state != QueueState.EMPTY) { emptySince = null; return Result.WAIT }
        val first = emptySince
        if (first == null) { emptySince = now; return Result.WAIT }
        if (now - first < 1_500L) return Result.WAIT
        emptySince = null
        step++
        return when (step) {
            1 -> { tab = ClosingTab.PREPARING; Result.NEXT_TAB }
            2 -> { tab = ClosingTab.READY; Result.NEXT_TAB }
            else -> Result.OPEN_HISTORY
        }
    }

    companion object {
        /** User-confirmed closing time, every day; device local time (shop phones: Bangkok). */
        fun isDue(time: LocalTime): Boolean = !time.isBefore(LocalTime.of(19, 0))
    }
}
