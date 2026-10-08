package io.github.panuwattegif.readyproof.core

/** Decides navigation only. Never presses order actions, approves dialogs or invents proof. */
object DedicatedMonitor {
    enum class Action { IDLE, WATCH_READY, OPEN_READY, OPEN_ORDERS, WAIT_FOR_GRAB }

    /** History can be opened by the owner for manual proof. Never steal that page. */
    fun historyOpen(roots: List<UiNode>, cfg: Config): Boolean {
        if (ClosingQueueAnalyzer.selected(roots, listOf("History", "ประวัติ"))) return true
        if (ClosingQueueAnalyzer.selected(roots, cfg.readyTabLabels + ClosingTab.PREPARING.labels +
                listOf("Upcoming", "ที่กำลังจะถึง"))) return false
        // Grab sometimes omits selected state. Terminal cards still identify History.
        return ScreenAnalyzer.analyze(roots, cfg).items.any {
            it.type in listOf(ObsType.DONE, ObsType.CANCELLED, ObsType.DELAY)
        }
    }

    fun decide(roots: List<UiNode>, cfg: Config, workflowBusy: Boolean): Action {
        if (!cfg.enabled || workflowBusy) return Action.IDLE
        if (roots.isEmpty()) return Action.WAIT_FOR_GRAB
        if (historyOpen(roots, cfg)) return Action.IDLE
        val texts = roots.flatMap { root -> root.walk().flatMap { it.ownStrings().asSequence() }.toList() }
        if (TabDetector.readyTabOpen(roots, cfg) == true) return Action.WATCH_READY
        if (texts.any { t -> cfg.readyTabLabels.any { TabDetector.isLabel(t, it) } }) return Action.OPEN_READY
        if (texts.any { t -> listOf("Orders", "คำสั่งซื้อ").any { TabDetector.isLabel(t, it) } }) return Action.OPEN_ORDERS
        return Action.WAIT_FOR_GRAB
    }
}
