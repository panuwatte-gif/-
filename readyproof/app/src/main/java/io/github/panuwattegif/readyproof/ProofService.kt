package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.panuwattegif.readyproof.core.DedicatedMonitor
import io.github.panuwattegif.readyproof.core.Config
import io.github.panuwattegif.readyproof.core.Deduper
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.ScreenAnalysis
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.StatusRules
import io.github.panuwattegif.readyproof.core.TextNorm
import io.github.panuwattegif.readyproof.core.UiNode
import io.github.panuwattegif.readyproof.core.HistoryDates
import io.github.panuwattegif.readyproof.core.ClosingHistoryGate
import io.github.panuwattegif.readyproof.core.ClosingQueueAnalyzer
import io.github.panuwattegif.readyproof.core.ClosingTab
import io.github.panuwattegif.readyproof.core.QueueState
import io.github.panuwattegif.readyproof.core.TabDetector
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ReadyProof watches GrabMerchant and stores only useful evidence:
 *  - READY: an order shown in Grab's Ready / พร้อมจัดส่ง tab.
 *  - DELAY: a History row explicitly marked Delayed / ล่าช้า.
 *
 * This phone only monitors proof. Order actions happen on the shop's Sunmi. Independently poll
 * Grab and return to Ready when another tab is opened; never wait for a local food-ready tap.
 * After 19:00, ReadyProof checks Ready and Preparing, opens History when both are confirmed
 * empty, and captures all terminal rows. Preparing-button presses are NOT stored as evidence.
 */
class ProofService : AccessibilityService() {

    companion object {
        @Volatile var instance: ProofService? = null
            private set
        @Volatile var lastTargetEventAt = 0L
            private set
        @Volatile var lastCaptureText: String? = null
            private set

        private const val MAX_NODES = 1500
        private const val SCAN_DELAY_MS = 450L
        private const val SCAN_MIN_INTERVAL_MS = 900L
        private const val READY_WATCH_INTERVAL_MS = 1_500L
        private const val AUTO_HISTORY_RETRY_MS = 5L * 60_000L
        private const val AUTO_HISTORY_CLICK_RETRY_MS = 60_000L
        private const val AUTO_HISTORY_PREF_COMPLETE_DATE = "auto_history_complete_date"
        private const val AUTO_HISTORY_PREF_LAST_RESULT = "auto_history_last_result"
        private const val SCROLL_SETTLE_MS = 650L
        private const val TEXT_ONLY_SCAN_INTERVAL_MS = 5_000L
        private const val AUTO_SCROLL_DELAY_MS = 450L
        private const val MAX_SWEEP_SCROLLS = 250
        private const val MAX_RETURN_TO_TOP_SCROLLS = 250
        private const val MAX_DELAY_REPOSITION_ATTEMPTS = 2
        private const val PAGE_DUPLICATE_WINDOW_MS = 30L * 60_000L
        private const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var capture: CaptureManager
    private val deduper = Deduper()
    private val scanPending = AtomicBoolean(false)
    private val seq = AtomicInteger()

    @Volatile private var config: Config = Config.DEFAULT
    @Volatile private var lastScanAt = 0L
    @Volatile private var lastScrollAt = 0L
    @Volatile private var lastFailToastAt = 0L

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    /** History sweeps require explicit authorization from closing or the in-app button. */
    @Volatile private var forcedHistorySweep = false
    private var lastSweepSignature = ""
    private var repeatedSweepSignature = 0
    private var sweepScrolls = 0
    @Volatile private var returningReadyToTop = false
    private var returnToTopScrolls = 0
    @Volatile private var captureInFlight = false
    @Volatile private var returningHistoryToTop = false
    private var historyAtTop = false
    private var historyReachedEnd = false
    private var scrollContainerFound = false
    private var historyHeader: LocalDate? = null
    private val historySeenKeys = LinkedHashSet<String>()
    private var captureFailureCount = 0
    private var captureFailureSignature = ""
    private var sweepShopId: String? = null
    private var historyLastProgressAt = 0L
    private val historyScrollRetry = io.github.panuwattegif.readyproof.core.HistoryScrollRetry()
    private var historyRowsRead = false
    @Volatile private var manualHistoryHold = false
    private var returnReadyAfterHistory = true
    private var manualHistoryPass = 0
    private fun autoNavigationEnabled() = io.github.panuwattegif.readyproof.core.ManualWorkflow.autoNavigation

    fun resumeReadyMonitor() {
        forcedHistorySweep = false
        autoHistoryInProgress = false
        autoHistoryTargetDate = null
        closingGate = null
        returningHistoryToTop = false
        manualHistoryHold = false
        main.post { clickTab(config.readyTabLabels) }
        scheduleScan()
    }
    private var delayRepositionKey: String? = null
    private var delayRepositionAttempts = 0
    private val recentPageCaptures = LinkedHashMap<String, Long>()
    private var readySeenDate: LocalDate = LocalDate.now()
    private val readySeenToday = LinkedHashSet<String>()
    private val readyStays = io.github.panuwattegif.readyproof.core.ReadyStayTracker()
    private val lastReadyLedgerAt = HashMap<String, Long>()

    @Volatile private var autoHistoryInProgress = false
    @Volatile private var autoHistoryTargetDate: LocalDate? = null
    @Volatile private var nextAutoHistoryAttemptAt = 0L
    @Volatile private var closingGate: ClosingHistoryGate? = null
    @Volatile private var monitorNavigationUntil = 0L
    private var nextMonitorNavigationAt = 0L
    private var monitorStatusText = ""
    private var monitorStatusSavedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = ConfigStore.get(this)
        workerThread = HandlerThread("readyproof-worker").also { it.start() }
        worker = Handler(workerThread.looper)
        capture = CaptureManager(this) { job, record, error -> onCaptureDone(job, record, error) }
        applyServiceInfo()
        registerShortcutButton()
        instance = this
        worker.post {
            try {
                val today = LocalDate.now()
                val startupRecords = RecordStore.loadRange(this, today.minusDays(1), today)
                deduper.seed(startupRecords.filter { it.shopId == ShopStore.get(this)?.id && RecordStore.dateOf(it.t) == today })
                readySeenDate = today
                startupRecords.filter { RecordStore.dateOf(it.t) == today && it.shopId == ShopStore.get(this)?.id }.forEach { r ->
                    r.items.filter { it.type == ObsType.READY }.forEach { readySeenToday += it.gf }
                }
                DriveSync.recover(this)
            } catch (e: Exception) {
                Diagnostics.error(this, "startup", e)
            }
            worker.postDelayed(readyWatchRunnable, READY_WATCH_INTERVAL_MS)
        }
    }

    fun onConfigChanged(cfg: Config) {
        config = cfg
        main.post { applyServiceInfo() }
    }

    private fun applyServiceInfo() {
        try {
            val info = serviceInfo ?: return
            info.packageNames = config.targetPackages.toTypedArray()
            serviceInfo = info
        } catch (e: Exception) {
            Diagnostics.error(this, "serviceInfo", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::worker.isInitialized) return
        val cfg = config
        if (!cfg.enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in cfg.targetPackages) return
        lastTargetEventAt = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollAt = SystemClock.uptimeMillis()
                scheduleScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val types = event.contentChangeTypes
                val textOnly = types != 0 && (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT.inv()) == 0
                if (!textOnly || SystemClock.uptimeMillis() - lastScanAt > TEXT_ONLY_SCAN_INTERVAL_MS) scheduleScan()
            }
            else -> scheduleScan()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        buttonCallback?.let { runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) } }
        buttonCallback = null
        if (::capture.isInitialized) capture.shutdown()
        if (::workerThread.isInitialized) workerThread.quitSafely()
    }

    // ---- scanning -----------------------------------------------------------------------------

    /** Independent poller: the dedicated proof phone may receive Ready changes from another
     * device without any useful Accessibility event. Never rely on a same-device Ready tap. */
    private val readyWatchRunnable = object : Runnable {
        override fun run() {
            if (!::worker.isInitialized) return
            try {
                if (config.enabled) {
                    preserveManualHistory(activeGrabSnapshots())
                    // A missing Grab tree must not strand an authorised pass forever.
                    if (forcedHistorySweep && !captureInFlight && !returningHistoryToTop &&
                        SystemClock.uptimeMillis() - historyLastProgressAt >= 90_000L) {
                        historyReachedEnd = false
                        closingStatus("อ่าน History ไม่คืบหน้า: เก็บและส่งชุดที่มี พร้อมรายงานว่ายังไม่ครบ แล้วลองใหม่")
                        finishSweep(false)
                    }
                    maybeStartAutomaticHistory()
                    superviseDedicatedMonitor()
                    // Poll History as well as Ready: a transient empty accessibility root or
                    // omitted event must recover without a human touching the phone.
                    if (!returningReadyToTop && !returningHistoryToTop && !captureInFlight) scheduleScan()
                }
            } catch (e: Exception) {
                Diagnostics.error(this@ProofService, "readyWatch", e)
                saveMonitorStatus("อ่านหน้าจอไม่สำเร็จ: ระบบจะตรวจใหม่เอง")
            } finally {
                runCatching { worker.postDelayed(this, READY_WATCH_INTERVAL_MS) }
            }
        }
    }

    private fun saveMonitorStatus(text: String) {
        val now = System.currentTimeMillis()
        if (text == monitorStatusText && now - monitorStatusSavedAt < 60_000L) return
        monitorStatusText = text
        monitorStatusSavedAt = now
        ConfigStore.prefs(this).edit().putString("monitor_status", text)
            .putLong("monitor_last_poll", now).apply()
    }

    /** Recover supported Grab navigation only; never accept/cancel orders or dismiss dialogs. */
    private fun preserveManualHistory(snaps: List<UiNode>) {
        if (autoHistoryInProgress || forcedHistorySweep) return
        if (!DedicatedMonitor.historyOpen(snaps, config)) return
        manualHistoryHold = true
        returnReadyAfterHistory = false
        returningReadyToTop = false
        closingGate = null
        saveMonitorStatus("พักการเปิดหน้าอัตโนมัติ: อยู่ History เพื่อแคปเอง / กลับ Ready เพื่อเฝ้าต่อ")
    }

    private fun superviseDedicatedMonitor() {
        if (!autoNavigationEnabled()) return
        preserveManualHistory(activeGrabSnapshots())
        if (manualHistoryHold) {
            if (autoHistoryInProgress || forcedHistorySweep) return
            if (selectedTabOpen(activeGrabSnapshots(), config.readyTabLabels)) manualHistoryHold = false
            else return
        }
        val busy = captureInFlight || closingGate != null || autoHistoryInProgress || forcedHistorySweep ||
            returningReadyToTop || returningHistoryToTop
        val action = DedicatedMonitor.decide(activeGrabSnapshots(), config, busy)
        when (action) {
            DedicatedMonitor.Action.IDLE -> return
            DedicatedMonitor.Action.WATCH_READY -> saveMonitorStatus("เฝ้า Ready อัตโนมัติ / รับการเปลี่ยนสถานะจาก Grab ไม่รอการกดบนมือถือ")
            DedicatedMonitor.Action.WAIT_FOR_GRAB -> saveMonitorStatus("ยังอ่านหน้าออเดอร์ Grab ไม่ได้: รอตรวจใหม่ / ตรวจจอล็อกหรือหน้าเข้าสู่ระบบเมื่อปิดร้าน")
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now < nextMonitorNavigationAt) return
                nextMonitorNavigationAt = now + 10_000L
                monitorNavigationUntil = now + 1_500L
                saveMonitorStatus("กำลังกลับหน้า Ready อัตโนมัติ")
                main.post {
                    if (!config.enabled || captureInFlight || closingGate != null || autoHistoryInProgress ||
                        forcedHistorySweep || returningReadyToTop || returningHistoryToTop || manualHistoryHold ||
                        DedicatedMonitor.historyOpen(activeGrabSnapshots(), config)) return@post
                    val labels = if (action == DedicatedMonitor.Action.OPEN_READY) config.readyTabLabels
                        else listOf("Orders", "คำสั่งซื้อ")
                    clickTab(labels)
                    // A click is not proof. scan() still requires a selected Ready tab or positive
                    // per-card Ready wording, and CaptureManager validates the actual bitmap.
                    worker.postDelayed({ scheduleScan() }, 1_500L)
                }
            }
        }
    }

    /** Closing never interrupts pending orders: recheck both queues before each automatic pass. */
    private fun maybeStartAutomaticHistory() {
        if (!autoNavigationEnabled() || manualHistoryHold) return
        if (SystemClock.uptimeMillis() < monitorNavigationUntil) return
        if (closingGate != null || captureInFlight || autoHistoryInProgress || forcedHistorySweep ||
            returningReadyToTop || returningHistoryToTop) return
        val now = LocalDateTime.now()
        if (!ClosingHistoryGate.isDue(now.toLocalTime())) return
        if (System.currentTimeMillis() < nextAutoHistoryAttemptAt) return
        val day = now.toLocalDate()
        val gate = ClosingHistoryGate(SystemClock.uptimeMillis())
        closingGate = gate
        navigateClosingTab(gate, day)
    }

    private fun closingStatus(text: String) {
        ConfigStore.prefs(this).edit().putString("closing_history_status", text).apply()
    }

    private fun navigateClosingTab(gate: ClosingHistoryGate, day: LocalDate) {
        closingStatus("หลัง 19:00: กำลังตรวจ ${gate.tab.name} ว่ามีออเดอร์ค้างหรือไม่")
        main.post {
            if (closingGate !== gate) return@post
            if (!config.enabled || !autoNavigationEnabled()) {
                worker.post { retryClosing(gate, "พักการตรวจ: ปิดแคปอัตโนมัติอยู่") }
                return@post
            }
            val labels = if (gate.tab == ClosingTab.READY) config.readyTabLabels else gate.tab.labels
            val clicked = runCatching { clickTab(labels) }.getOrDefault(false)
            worker.postDelayed({
                if (closingGate !== gate) return@postDelayed
                if (!clicked) retryClosing(gate, "เปิด ${gate.tab.name} ไม่สำเร็จ / ต้องเปิด Grab ค้างไว้")
                else pollClosingTab(gate, day)
            }, 1_500L)
        }
    }

    private fun pollClosingTab(gate: ClosingHistoryGate, day: LocalDate) {
        if (closingGate !== gate) return
        if (!config.enabled || !autoNavigationEnabled() || day != LocalDate.now()) {
            retryClosing(gate, "พักการตรวจ / วันเปลี่ยนแล้ว")
            return
        }
        // Only inspect the active Grab window: an overlay, locked phone or another app is UNKNOWN.
        val roots = activeGrabSnapshots()
        val state = ClosingQueueAnalyzer.inspect(roots, config, gate.tab, navigationAccepted = true)
        when (gate.observe(state, SystemClock.uptimeMillis())) {
            ClosingHistoryGate.Result.NEXT_TAB -> navigateClosingTab(gate, day)
            ClosingHistoryGate.Result.OPEN_HISTORY -> {
                closingGate = null
                closingStatus("Ready และ Preparing ว่าง: กำลังเปิด History อัตโนมัติ")
                startHistorySweep(day)
            }
            ClosingHistoryGate.Result.RETRY -> retryClosing(gate, if (state == QueueState.BUSY)
                "ยังมีออเดอร์ค้างใน ${gate.tab.name}: รอและตรวจใหม่ใน 1 นาที"
                else "ยังยืนยันหน้า ${gate.tab.name} ว่างไม่ได้: จะตรวจใหม่ใน 1 นาที")
            ClosingHistoryGate.Result.WAIT -> worker.postDelayed({ pollClosingTab(gate, day) }, 1_500L)
        }
    }

    private fun retryClosing(gate: ClosingHistoryGate, reason: String) {
        if (closingGate !== gate) return
        Diagnostics.dump(this, "CLOSING_RETRY $reason", activeGrabSnapshots(), force = true)
        closingGate = null
        nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_CLICK_RETRY_MS
        closingStatus(reason)
        // Continue capturing Ready evidence during the wait; never leave the monitor in Preparing.
        main.post { if (config.enabled && autoNavigationEnabled() && !manualHistoryHold &&
            !DedicatedMonitor.historyOpen(activeGrabSnapshots(), config)) clickTab(config.readyTabLabels) }
        scheduleScan()
    }

    /** Manual hook used by the UI for testing; automatic end-of-day scanning uses the same path. */
    fun requestHistorySweepNow() {
        if (!::worker.isInitialized) return
        // Claim manual navigation before launching Grab; the old delayed claim raced the monitor.
        manualHistoryHold = true
        returnReadyAfterHistory = false
        returningReadyToTop = false
        closingGate = null
        worker.postDelayed({
            manualHistoryPass = 0
            closingGate = null
            startHistorySweep(LocalDate.now(), force = true)
        }, 2_500L)
    }

    private fun startHistorySweep(day: LocalDate, force: Boolean = false) {
        if (autoHistoryInProgress || forcedHistorySweep) return
        if (!force && System.currentTimeMillis() < nextAutoHistoryAttemptAt) return
        autoHistoryInProgress = true
        returnReadyAfterHistory = !force
        if (force) manualHistoryHold = true
        autoHistoryTargetDate = day
        sweepShopId = ShopStore.get(this)?.id
        main.post {
            val clicked = runCatching { clickTab(listOf("History", "ประวัติ")) }.getOrDefault(false)
            if (clicked) {
                worker.postDelayed({ confirmHistoryPage(day, SystemClock.uptimeMillis()) }, 1_500L)
            } else {
                worker.post { historyNavigationFailed() }
            }
        }
    }

    private fun confirmHistoryPage(day: LocalDate, startedAt: Long) {
        if (!autoHistoryInProgress || autoHistoryTargetDate != day || forcedHistorySweep) return
        val snaps = activeGrabSnapshots()
        val analysis = ScreenAnalyzer.analyze(snaps, config)
        val selected = selectedTabOpen(snaps, listOf("History", "ประวัติ"))
        val otherSelected = ClosingQueueAnalyzer.selected(snaps,
            config.readyTabLabels + ClosingTab.PREPARING.labels + listOf("Upcoming", "ที่กำลังจะถึง"))
        // When selection is omitted, terminal-row content confirms History, never an empty tree.
        val terminal = analysis.items.any { it.type in listOf(ObsType.DONE, ObsType.CANCELLED, ObsType.DELAY) }
        if (config.enabled && !otherSelected && (selected || terminal)) {
            Diagnostics.dump(this, "HISTORY_CONFIRMED manual=$manualHistoryHold terminal=$terminal", snaps, force = true)
            forcedHistorySweep = true
            historyLastProgressAt = SystemClock.uptimeMillis()
            resetSweepLoop()
            closingStatus("เปิด History แล้ว: กำลังกวาดรายการทั้งวัน")
            startHistoryAtTop()
        } else if (!config.enabled || SystemClock.uptimeMillis() - startedAt >= 10_000L) {
            historyNavigationFailed()
        } else worker.postDelayed({ confirmHistoryPage(day, startedAt) }, 700L)
    }

    private fun historyNavigationFailed() {
        Diagnostics.dump(this, "HISTORY_NAVIGATION_FAILED", activeGrabSnapshots(), force = true)
        val failedDay = autoHistoryTargetDate
        if (failedDay != null) worker.post {
            runCatching { DailyExport.save(this, failedDay, sweepShopId, false,
                "เปิดหรือยืนยันหน้า History ไม่สำเร็จ — ไม่ใช่วันที่ไม่มีออเดอร์") }
                .onFailure { Diagnostics.error(this, "historyFailureExport", it) }
        }
        autoHistoryInProgress = false
        autoHistoryTargetDate = null
        nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_CLICK_RETRY_MS
        closingStatus("ยังเปิดหรือยืนยันหน้า History ไม่สำเร็จ: จะตรวจและลองใหม่ใน 1 นาที")
        main.post { if (config.enabled && returnReadyAfterHistory) clickTab(config.readyTabLabels) }
        toast("⚠️ เปิด History อัตโนมัติไม่สำเร็จ — จะลองใหม่")
    }

    private fun activeGrabSnapshots(): List<UiNode> = try {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() in config.targetPackages)
            listOf(NodeSnapshot.capture(active, MAX_NODES)) else emptyList()
    } catch (e: Exception) {
        Diagnostics.error(this, "closingNavigationSnapshot", e)
        emptyList()
    }

    private fun scheduleScan() {
        if (!scanPending.compareAndSet(false, true)) return
        val sinceLast = SystemClock.uptimeMillis() - lastScanAt
        worker.postDelayed(scanRunnable, maxOf(SCAN_DELAY_MS, SCAN_MIN_INTERVAL_MS - sinceLast))
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            val sinceScroll = SystemClock.uptimeMillis() - lastScrollAt
            if (sinceScroll < SCROLL_SETTLE_MS) {
                worker.postDelayed(this, SCROLL_SETTLE_MS - sinceScroll)
                return
            }
            scanPending.set(false)
            lastScanAt = SystemClock.uptimeMillis()
            try {
                scan()
            } catch (e: Exception) {
                Diagnostics.error(this@ProofService, "scan", e)
            }
        }
    }

    private fun scan() {
        if (SystemClock.uptimeMillis() < monitorNavigationUntil) return
        if (closingGate != null || (autoHistoryInProgress && !forcedHistorySweep)) return
        if (returningReadyToTop || returningHistoryToTop || captureInFlight) return
        val cfg = config
        if (!cfg.enabled) return
        val roots = targetRoots(cfg)
        val snaps = roots.map { NodeSnapshot.capture(it, MAX_NODES) }
        if (snaps.isEmpty()) return
        if (cfg.diagnostics) Diagnostics.dump(this, "SCAN", snaps, force = false)

        preserveManualHistory(snaps)

        var analysis = ScreenAnalyzer.analyze(snaps, cfg, allowUnknownDelayed = forcedHistorySweep)

        // Reading/capturing a user-opened History page does not authorize a sweep or navigation.
        val manualHistoryMode = (manualHistoryHold || !autoNavigationEnabled()) &&
            (selectedTabOpen(snaps, listOf("History", "ประวัติ")) ||
                analysis.items.any { it.type in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED) })
        if (!forcedHistorySweep && !manualHistoryMode && analysis.readyTab != true && analysis.items.none { it.type == ObsType.READY }) return

        val historyMode = forcedHistorySweep || manualHistoryMode
        val readyMode = !historyMode && (analysis.readyTab == true || analysis.items.any { it.type == ObsType.READY })
        // Scroll inside the already-open Ready list for proof coverage; never open/change tabs.
        val sweepMode = readyMode || forcedHistorySweep
        if (historyMode) {
            DailyExport.captureTotals(this, autoHistoryTargetDate ?: LocalDate.now(), ShopStore.get(this)?.id, snaps)
            val dated = HistoryDates.assign(analysis.items, analysis, snaps, LocalDate.now(), historyHeader)
            analysis = analysis.copy(items = dated.first)
            historyHeader = dated.second
        }

        if (sweepMode) {
            // Include card positions, not just GF numbers. Grab often scrolls by less than a full
            // card, so the visible GF set can stay identical for several successful scrolls.
            val signature = analysis.cards.filter { it.inList }
                .joinToString("|") { "${it.gf}@${it.node.top}:${it.node.bottom}" }
            val nextSignature = signature.ifEmpty { analysis.visible.joinToString("|") }
            if (historyMode && nextSignature.isNotEmpty() && nextSignature != lastSweepSignature)
                historyLastProgressAt = SystemClock.uptimeMillis()
            updateSweepSignature(nextSignature)
        }

        val today = LocalDate.now()
        if (today != readySeenDate) {
            deduper.clear()
            readySeenDate = today
            readySeenToday.clear()
            readyStays.clear()
            lastReadyLedgerAt.clear()
            historySeenKeys.clear()
        }
        val now = System.currentTimeMillis()

        // Coverage ledger is written before screenshotting. If Android misses a bitmap we still
        // know exactly which GF reached Ready, while the screenshot deduper keeps it eligible.
        if (readyMode) {
            readyStays.observe(analysis.items.filter { it.type == ObsType.READY }.map { it.gf })
            val newlySeen = analysis.items.filter { it.type == ObsType.READY }
                .filter { readySeenToday.add(it.gf) || now - (lastReadyLedgerAt[it.gf] ?: 0) >= 60_000 }
            newlySeen.forEach { lastReadyLedgerAt[it.gf] = now }
            if (newlySeen.isNotEmpty()) {
                RecordStore.append(
                    this,
                    Record(
                        id = "$now-rs${seq.incrementAndGet()}",
                        t = now,
                        kind = RecordKind.SEEN,
                        items = newlySeen,
                        visible = analysis.visible,
                        note = "READY_SEEN_PENDING_UNTIL_IMAGE",
                        shopId = ShopStore.get(this)?.id,
                    )
                )
            }
        }

        // Persist ALL terminal observations before any screenshot/reposition attempt.
        val terminal = analysis.items.filter { it.type in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED) }
        val targetDay = autoHistoryTargetDate ?: today
        val olderDateBoundary = terminal.any { i -> i.historyDate?.let { runCatching { LocalDate.parse(it).isBefore(targetDay) }.getOrDefault(false) } == true }
        val newTerminal = terminal.filter { historySeenKeys.add("${it.historyDate}|${Deduper.keyOf(it)}|${it.card}") }
        if (newTerminal.isNotEmpty()) RecordStore.append(this, Record("$now-hs${seq.incrementAndGet()}", now,
            RecordKind.SEEN, newTerminal, analysis.visible, shopId = ShopStore.get(this)?.id))
        // Respect the configured repeat window; polling is not permission to recapture every minute.
        val fresh = deduper.fresh(analysis.items, now, cfg.copy(readyRepeatMinutes = io.github.panuwattegif.readyproof.core.ManualWorkflow.readyRepeatMinutes))
        if (historyMode) historyRowsRead = terminal.isNotEmpty()

        // A History row is valid screenshot evidence only when the GF label is actually inside the
        // visible screen and the completed time was parsed. Accessibility can expose a clipped row
        // whose "Delayed" line is visible while GF-xxx has already moved above the screenshot.
        val unproofableDelays = fresh.filter { it.type == ObsType.DELAY && !delayProofable(it, analysis, cfg) }
        val clippedAbove = unproofableDelays.firstOrNull { item ->
            val card = matchingDelayCard(item, analysis, cfg)
            card != null && card.node.top < 0
        }
        if (historyMode && clippedAbove != null && tryRepositionDelay(clippedAbove)) return

        val captureCandidates = fresh.filterNot { it.type == ObsType.DELAY && it in unproofableDelays }
        // One viewport screenshot can prove every visible order. Do not serialize READY by GF:
        // batch all fresh READY targets currently visible, or all fresh DELAY targets in History.
        // CaptureManager validates each target and returns only the GFs actually covered by the
        // bitmap; any dropped target is released from dedupe and remains pending for the next scan.
        val readyTargets = captureCandidates.filter { it.type == ObsType.READY }
        val delayTargets = captureCandidates.filter { it.type == ObsType.DELAY }
        val captureItems = when {
            readyMode && cfg.captureReady && readyTargets.isNotEmpty() -> readyTargets
            historyMode && terminal.isNotEmpty() -> fresh.filter { it.type in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED) &&
                (it.historyDate == null || it.historyDate == targetDay.toString()) }
            cfg.captureReady && readyTargets.isNotEmpty() -> readyTargets
            cfg.captureDelay && delayTargets.isNotEmpty() -> delayTargets
            else -> emptyList()
        }
        val captureKeys = captureItems.mapNotNull { Deduper.keyOf(it) }
        val ready = captureItems.any { it.type == ObsType.READY }
        val delay = captureItems.any { it.type in listOf(ObsType.DONE, ObsType.DELAY, ObsType.CANCELLED) }

        // Keep unproofable delayed rows as text-only observations so the report knows they exist,
        // but do not seed screenshot dedupe from them. A later sweep can still capture proper proof.
        if (unproofableDelays.isNotEmpty()) {
            RecordStore.append(
                this,
                Record(
                    id = "$now-u${seq.incrementAndGet()}",
                    t = now,
                    kind = RecordKind.SEEN,
                    shopId = ShopStore.get(this)?.id,
                    items = unproofableDelays,
                    visible = analysis.visible,
                )
            )
        }

        if (ready || delay) {
            val kind = if (ready) RecordKind.READY else if (captureItems.any { it.type == ObsType.DELAY }) RecordKind.DELAY else RecordKind.HISTORY
            // Only a successful screenshot is allowed to make an order stay deduped.
            // Keys are tentatively marked here to stop duplicate jobs while Android is capturing;
            // on failure or partial batch coverage onCaptureDone() releases every unsaved key.
            if (captureKeys.isNotEmpty()) deduper.mark(captureKeys, now)
            val job = CaptureJob(kind, now, ShopStore.get(this)?.id)
            job.setMeta(
                CaptureMeta(
                    items = captureItems,
                    visible = analysis.visible,
                    dedupeKeys = captureKeys,
                    toast = toastFor(kind, captureItems),
                )
            )
            captureInFlight = true
            capture.submit(job)
            // Continue scrolling only after this screenshot finishes, so proof and metadata stay aligned.
            return
        }

        if (historyMode && olderDateBoundary && forcedHistorySweep) {
            historyReachedEnd = historyAtTop
            finishSweep(false)
        } else if (sweepMode) continueSweep(readyMode)
    }

    private fun matchingDelayCard(item: Item, analysis: ScreenAnalysis, cfg: Config) =
        analysis.cards.firstOrNull { card ->
            if (card.gf != item.gf) return@firstOrNull false
            StatusRules(cfg).evaluate(card).any { seen ->
                seen.type == ObsType.DELAY && (item.doneAt == null || seen.doneAt == item.doneAt)
            }
        }

    private fun delayProofable(item: Item, analysis: ScreenAnalysis, cfg: Config): Boolean {
        if (item.doneAt == null) return false
        val card = matchingDelayCard(item, analysis, cfg) ?: return false
        // Negative top means the card/GF has already slid above the screenshot. Zero-sized bounds
        // are also not trustworthy evidence.
        return card.node.top >= 0 && card.node.bottom > card.node.top
    }

    private fun tryRepositionDelay(item: Item): Boolean {
        val key = Deduper.keyOf(item) ?: return false
        if (delayRepositionKey != key) {
            delayRepositionKey = key
            delayRepositionAttempts = 0
        }
        if (delayRepositionAttempts >= MAX_DELAY_REPOSITION_ATTEMPTS) return false
        delayRepositionAttempts++
        main.post {
            val moved = scrollOrderListBackward()
            if (moved) lastScrollAt = SystemClock.uptimeMillis()
            worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
        }
        return true
    }

    private fun pageCaptureKey(kind: RecordKind, items: List<Item>, visible: List<String>): String {
        val type = if (kind == RecordKind.READY) ObsType.READY else ObsType.DELAY
        val target = items.filter { it.type == type }
            .map { Deduper.keyOf(it) ?: "${it.type}|${it.gf}" }
            .sorted()
        if (target.isEmpty()) return ""
        return kind.name + "|" + target.joinToString(";") + "|" + visible.joinToString(",")
    }

    private fun pageCapturedRecently(key: String, now: Long): Boolean {
        val last = recentPageCaptures[key] ?: return false
        return now - last < PAGE_DUPLICATE_WINDOW_MS
    }

    private fun rememberPageCapture(key: String, now: Long) {
        if (key.isEmpty()) return
        recentPageCaptures[key] = now
        recentPageCaptures.entries.removeAll { now - it.value > PAGE_DUPLICATE_WINDOW_MS }
    }

    private fun toastFor(kind: RecordKind, items: List<Item>): String = when (kind) {
        RecordKind.READY -> "📸 READY: " + items.filter { it.type == ObsType.READY }.joinToString(", ") { it.gf }
        else -> "📸 ล่าช้า: " + items.filter { it.type == ObsType.DELAY }
            .joinToString(", ") { it.gf + (it.delayMin?.let { m -> " ($m นาที)" } ?: "") }
    }

    // ---- automatic scrolling ------------------------------------------------------------------

    private fun updateSweepSignature(signature: String) {
        if (signature.isNotEmpty() && signature == lastSweepSignature) {
            repeatedSweepSignature++
        } else {
            lastSweepSignature = signature
            repeatedSweepSignature = 0
        }
    }

    private fun resetSweepLoop() {
        historyScrollRetry.reset()
        historyRowsRead = false
        lastSweepSignature = ""
        repeatedSweepSignature = 0
        sweepScrolls = 0
        delayRepositionKey = null
        delayRepositionAttempts = 0
    }

    private fun continueSweep(readyMode: Boolean? = null) {
        worker.postDelayed({
            main.post {
                if (closingGate != null || (autoHistoryInProgress && !forcedHistorySweep)) return@post
                if (captureInFlight || returningHistoryToTop || returningReadyToTop) return@post
                if (readyMode == false && !forcedHistorySweep) return@post
                if (readyMode == true && forcedHistorySweep) return@post
                // Stop only when the viewport truly stops moving several times or the safety cap
                // is reached. The old GF-only signature could stop while the list was still moving.
                val canContinue = repeatedSweepSignature < 4 && sweepScrolls < MAX_SWEEP_SCROLLS
                val moved = canContinue && scrollOrderListForward()
                if (forcedHistorySweep && !moved && historyScrollRetry.shouldRetry(false,
                        scrollContainerFound, historyRowsRead)) {
                    historyReachedEnd = false
                    closingStatus("History ยังอ่านหรือเลื่อนไม่สำเร็จ: กำลังตรวจซ้ำ ไม่ถือว่าจบรายการ")
                    Diagnostics.dump(this, "HISTORY_SCROLL_RETRY container=$scrollContainerFound rows=$historyRowsRead",
                        activeGrabSnapshots(), force = true)
                    worker.postDelayed({ scheduleScan() }, 1_500L)
                    return@post
                }
                if (moved) historyScrollRetry.reset()
                if (!moved && forcedHistorySweep) historyReachedEnd = historyAtTop && canContinue &&
                    scrollContainerFound && repeatedSweepSignature < 4
                if (moved) {
                    sweepScrolls++
                    lastScrollAt = SystemClock.uptimeMillis()
                    worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
                } else {
                    finishSweep(readyMode ?: false)
                }
            }
        }, AUTO_SCROLL_DELAY_MS)
    }

    private fun finishSweep(wasReady: Boolean) {
        if (closingGate != null || (autoHistoryInProgress && !forcedHistorySweep)) return
        if (wasReady) {
            val gone = readyStays.finish(SystemClock.uptimeMillis(), scrollContainerFound &&
                sweepScrolls < MAX_SWEEP_SCROLLS && repeatedSweepSignature < 4)
            deduper.forget(gone.map { "READY|$it" })
            resetSweepLoop()
            // Always stay on Ready: this phone never accepts or prepares orders.
            startReturnReadyToTop()
            return
        }
        if (forcedHistorySweep) {
            val target = autoHistoryTargetDate
            forcedHistorySweep = false
            resetSweepLoop()
            if (target != null) finishAutomaticHistory(target)
        }
    }

    private fun finishAutomaticHistory(day: LocalDate) {
        worker.post {
            try {
                Diagnostics.dump(this, "HISTORY_FINISH end=$historyReachedEnd rows=$historyRowsRead container=$scrollContainerFound",
                    activeGrabSnapshots(), force = true)
                val records = RecordStore.loadRange(this, day.minusDays(1), day.plusDays(1))
                val report = DailyExport.save(this, day, sweepShopId, historyReachedEnd)
                val missingDelayProof = report.cases.count { it.delayShot == null }
                val complete = report.complete
                val result = buildString {
                    append("Ready seen ").append(report.readySeenOrders)
                    append(" / Ready proof ").append(report.readyOrders)
                    append(" / Pending ").append(report.pendingReadyProof)
                    append(" / Completed ").append(report.completedSeen)
                    append(" / Cancelled ").append(report.cancelledSeen)
                    append(" / History total ").append(report.historyOrders)
                    append(" / Delayed ").append(report.delayed)
                    append(" / DELAY proof ").append(report.delayed - missingDelayProof).append('/').append(report.delayed)
                    append(if (complete) " / MATCH" else " / INCOMPLETE")
                }
                val edit = ConfigStore.prefs(this).edit()
                    .putString(AUTO_HISTORY_PREF_LAST_RESULT, result)
                if (complete) {
                    edit.putString(AUTO_HISTORY_PREF_COMPLETE_DATE, day.toString())
                    nextAutoHistoryAttemptAt = System.currentTimeMillis() + 15 * 60_000L
                } else {
                    edit.remove(AUTO_HISTORY_PREF_COMPLETE_DATE)
                    nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_RETRY_MS
                }
                edit.apply()
                closingStatus(if (complete) "กวาด History แล้ว / ครบตามข้อมูลที่อ่านได้" else "กวาด History ยังไม่ครบ: ดูจำนวนที่ขาดและกดกวาดใหม่ได้")
                lastCaptureText = result
                autoHistoryInProgress = false
                autoHistoryTargetDate = null
                if (manualHistoryHold && manualHistoryPass < 2 &&
                    (DailyExport.countMismatch(this, report) || report.missingHistoryInstances.isNotEmpty() || missingDelayProof > 0)) {
                    manualHistoryPass++
                    closingStatus("ยังขาดรายการหรือภาพ: กวาดซ้ำในคำสั่งเดียวกัน รอบ ${manualHistoryPass + 1}/3")
                    worker.postDelayed({ if (manualHistoryHold && config.enabled) startHistorySweep(day, force = true) }, 1_500L)
                    return@post
                }
                main.post {
                    // Return to the dedicated Ready monitor after every History pass. If counts are
                    // incomplete the scheduled retry will revisit History automatically.
                    if (returnReadyAfterHistory && autoNavigationEnabled()) clickTab(config.readyTabLabels)
                    toast(if (complete) "✓ History ครบ: $result" else "⚠️ History ยังไม่ครบ: $result — กดกวาดใหม่ได้")
                }
            } catch (e: Exception) {
                autoHistoryInProgress = false
                autoHistoryTargetDate = null
                nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_RETRY_MS
                Diagnostics.error(this, "finishAutomaticHistory", e)
                main.post { if (returnReadyAfterHistory && autoNavigationEnabled()) clickTab(config.readyTabLabels) }
            }
        }
    }

    fun onShopBound() {
        worker.post {
            // Never let pre-binding dedupe suppress new shop-tagged proof.
            val today = LocalDate.now()
            val records = RecordStore.loadRange(this, today.minusDays(1), today)
            deduper.forget(records.flatMap { it.items }.mapNotNull { Deduper.keyOf(it) })
            readySeenToday.clear()
            historySeenKeys.clear()
            ConfigStore.prefs(this).edit().remove(AUTO_HISTORY_PREF_COMPLETE_DATE).apply()
        }
    }

    private fun startHistoryAtTop() {
        if (returningHistoryToTop) return
        returningHistoryToTop = true
        historyAtTop = false
        historyReachedEnd = false
        historyHeader = null
        var attempts = 0
        var previousViewport = ""
        var unchanged = 0
        fun step() {
            main.postDelayed({
                if (!forcedHistorySweep) return@postDelayed
                val viewport = ScreenAnalyzer.analyze(activeGrabSnapshots(), config).cards.joinToString("|") {
                    "${it.gf}@${it.node.top}:${it.node.bottom}"
                }
                unchanged = if (viewport.isNotEmpty() && viewport == previousViewport) unchanged + 1 else 0
                previousViewport = viewport
                if (unchanged >= 3) {
                    // A dispatched swipe is not proof of movement. Bound top recovery even when
                    // the list accepts gestures at its boundary; completeness still needs counts.
                    historyAtTop = scrollContainerFound
                    returningHistoryToTop = false
                    resetSweepLoop()
                    scheduleScan()
                    return@postDelayed
                }
                val moved = attempts < MAX_RETURN_TO_TOP_SCROLLS && scrollOrderListBackward()
                if (moved) {
                    attempts++
                    lastScrollAt = SystemClock.uptimeMillis()
                    step()
                } else {
                    historyAtTop = attempts < MAX_RETURN_TO_TOP_SCROLLS && scrollContainerFound
                    returningHistoryToTop = false
                    resetSweepLoop()
                    worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
                }
            }, SCROLL_SETTLE_MS)
        }
        step()
    }

    private fun startReturnReadyToTop() {
        if (closingGate != null || (autoHistoryInProgress && !forcedHistorySweep)) return
        if (returningReadyToTop) return
        returningReadyToTop = true
        returnToTopScrolls = 0
        continueReturnReadyToTop()
    }

    private fun continueReturnReadyToTop() {
        worker.postDelayed({
            main.post {
                if (!returningReadyToTop || manualHistoryHold || forcedHistorySweep ||
                    autoHistoryInProgress || DedicatedMonitor.historyOpen(activeGrabSnapshots(), config)) {
                    returningReadyToTop = false
                    return@post
                }
                val canContinue = returnToTopScrolls < MAX_RETURN_TO_TOP_SCROLLS
                val moved = canContinue && scrollOrderListBackward()
                if (moved) {
                    returnToTopScrolls++
                    lastScrollAt = SystemClock.uptimeMillis()
                    continueReturnReadyToTop()
                } else {
                    returningReadyToTop = false
                    returnToTopScrolls = 0
                    worker.postDelayed({ scheduleScan() }, READY_WATCH_INTERVAL_MS)
                }
            }
        }, AUTO_SCROLL_DELAY_MS)
    }

    /** Scroll the most likely order-list widget by one page. */
    private fun scrollOrderListForward(): Boolean = scrollOrderList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    /** Used only to bring a clipped delayed card back down so GF-xxx is visible in the screenshot. */
    private fun scrollOrderListBackward(): Boolean = scrollOrderList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scrollOrderList(action: Int): Boolean {
        val roots = targetRoots(config)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) collectScrollable(root, candidates, 0)
        scrollContainerFound = candidates.isNotEmpty()

        // Prefer the scrollable container that actually contains the most visible GF order IDs.
        // This avoids accidentally scrolling the tab strip or an outer container on Grab builds
        // where several widgets report isScrollable=true.
        val ordered = candidates.sortedWith(
            compareByDescending<AccessibilityNodeInfo> { visibleGfCount(it) }
                .thenByDescending {
                    val c = it.className?.toString().orEmpty()
                    if (c.contains("Recycler", ignoreCase = true) || c.contains("ListView", ignoreCase = true)) 1 else 0
                }
        )
        for (node in ordered) {
            val moved = runCatching { node.performAction(action) }.getOrDefault(false)
            if (moved) return true
            val directional = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            if (node.actionList.any { it.id == directional } &&
                runCatching { node.performAction(directional) }.getOrDefault(false)) return true
        }
        return swipeHistoryList(action)
    }

    /** Only a user-authorised History pass may use a swipe fallback, never order action buttons. */
    private fun swipeHistoryList(action: Int): Boolean {
        if (!forcedHistorySweep) return false
        val active = rootInActiveWindow ?: return false
        if (active.packageName?.toString() !in config.targetPackages) return false
        val snaps = activeGrabSnapshots()
        if (!DedicatedMonitor.historyOpen(snaps, config)) return false
        val cards = ScreenAnalyzer.analyze(snaps, config).cards.filter { it.inList && it.node.bottom > it.node.top }
        if (cards.isEmpty()) return false
        val window = Rect().also { active.getBoundsInScreen(it) }
        val top = maxOf(window.top + 80, cards.minOf { it.node.top })
        val bottom = minOf(window.bottom - 100, cards.maxOf { it.node.bottom })
        if (bottom - top < 160 || window.width() < 120) return false
        scrollContainerFound = true
        val x = window.exactCenterX()
        val high = top + (bottom - top) * 0.2f
        val low = top + (bottom - top) * 0.8f
        val forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val path = Path().apply { moveTo(x, if (forward) low else high); lineTo(x, if (forward) high else low) }
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) { scheduleScan() }
                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Diagnostics.dump(this@ProofService, "HISTORY_SWIPE_CANCELLED", activeGrabSnapshots(), force = true)
                        scheduleScan()
                    }
                }, main)
        }.getOrDefault(false)
    }

    private fun visibleGfCount(root: AccessibilityNodeInfo): Int {
        val extractor = config.gfExtractor()
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 600) {
            val n = queue.removeFirst()
            n.text?.toString()?.let { found += extractor.extract(it) }
            n.contentDescription?.toString()?.let { found += extractor.extract(it) }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let(queue::addLast)
            }
        }
        return found.size
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 50 || out.size > 20) return
        val scrollActions = setOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
        if (node.isScrollable || node.collectionInfo != null || node.actionList.any { it.id in scrollActions }) out += node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectScrollable(child, out, depth + 1)
        }
    }

    /** True only when one of the requested tab labels is selected in Accessibility. */
    private fun selectedTabOpen(roots: List<UiNode>, labels: List<String>): Boolean {
        return ClosingQueueAnalyzer.selected(roots, labels)
    }

    // ---- tab navigation -----------------------------------------------------------------------

    private fun clickTab(labels: List<String>): Boolean {
        return try { clickTabUnchecked(labels) } catch (e: Exception) {
            Diagnostics.error(this, "tabNavigation", e)
            false
        }
    }

    private fun clickTabUnchecked(labels: List<String>): Boolean {
        // Never navigate a Grab window behind another app or a permission dialog.
        val active = rootInActiveWindow ?: return false
        if (active.packageName?.toString() !in config.targetPackages) return false
        for (root in listOf(active)) {
            val target = findNodeByLabels(root, labels) ?: continue
            var clickable: AccessibilityNodeInfo? = target
            repeat(5) {
                val cur = clickable ?: return@repeat
                if (cur.isClickable && runCatching { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                    return true
                }
                clickable = cur.parent
            }
        }
        return false
    }

    private fun findNodeByLabels(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val n = queue.removeFirst()
            val texts = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            if (n.isVisibleToUser && texts.any { t -> labels.any { TabDetector.isLabel(t, it) } }) {
                var score = if (n.isSelected) 80 else 0
                var tabHint = false
                var cur: AccessibilityNodeInfo? = n
                repeat(3) {
                    val node = cur ?: return@repeat
                    if (node.className?.toString()?.contains("tab", ignoreCase = true) == true ||
                        node.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString()?.contains("tab", ignoreCase = true) == true) tabHint = true
                    cur = node.parent
                }
                // A food-ready button has the same label, but its immediate container has a GF.
                val readyLabel = labels.any { l -> config.readyTabLabels.any { TextNorm.key(it) == TextNorm.key(l) } }
                val cardButton = readyLabel && !tabHint && n.parent?.let { visibleGfCount(it) > 0 } == true
                if (!cardButton) {
                    if (tabHint) score += 100
                    if (texts.any { Regex("\\d").containsMatchIn(it) }) score += 20
                    candidates += n to score
                }
            }
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let(queue::addLast)
            }
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun matchesAny(text: String?, labels: List<String>): Boolean {
        val t = TextNorm.key(text)
        return t.isNotEmpty() && labels.any { label ->
            val k = TextNorm.key(label)
            k.isNotEmpty() && (t == k || t.contains(k))
        }
    }

    /** Roots of Grab's application windows (list + any dialog on top). */
    private fun targetRoots(cfg: Config): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg != null && pkg in cfg.targetPackages) out += root
            }
        } catch (e: Exception) {
            Diagnostics.error(this, "windows", e)
        }
        if (out.isEmpty()) {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root != null && pkg != null && pkg in cfg.targetPackages) out += root
        }
        return out
    }

    // ---- manual test capture ------------------------------------------------------------------

    private fun registerShortcutButton() {
        try {
            val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    requestManualCapture(0, null)
                }
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(cb, main)
            buttonCallback = cb
        } catch (e: Exception) {
            Diagnostics.error(this, "button", e)
        }
    }

    fun requestManualCapture(delayMs: Long, note: String?) {
        worker.postDelayed({ manualCapture(note) }, delayMs)
    }

    private fun manualCapture(note: String?) {
        if (captureInFlight) { toast("กำลังแคปหลักฐาน — ลองแคปเองอีกครั้ง"); return }
        val job = CaptureJob(RecordKind.MANUAL, System.currentTimeMillis(), ShopStore.get(this)?.id)
        val cfg = config
        val meta = try {
            val snaps: List<UiNode> = targetRoots(cfg).map { NodeSnapshot.capture(it, MAX_NODES) }
            if (cfg.diagnostics) Diagnostics.dump(this, "MANUAL", snaps, force = true)
            val analysis = ScreenAnalyzer.analyze(snaps, cfg)
            val shown = analysis.visible.take(3).joinToString(", ")
            CaptureMeta(
                items = ScreenAnalyzer.manualItems(analysis, cfg),
                visible = analysis.visible,
                note = note,
                toast = "📸 แคปแล้ว" + (if (shown.isNotEmpty()) " $shown" else ""),
            )
        } catch (e: Exception) {
            Diagnostics.error(this, "manual", e)
            CaptureMeta(note = note, toast = "📸 แคปแล้ว")
        }
        job.setMeta(meta)
        captureInFlight = true
        capture.submit(job)
    }

    // ---- screenshot result --------------------------------------------------------------------

    private fun onCaptureDone(job: CaptureJob, record: Record?, error: String?) {
        worker.post { handleCaptureDone(job, record, error) }
    }

    private fun handleCaptureDone(job: CaptureJob, record: Record?, error: String?) {
        captureInFlight = false
        val meta = job.awaitMeta(0)
        if (record == null) {
            deduper.forget(meta.dedupeKeys)
            Diagnostics.error(this, "capture ${job.kind}", RuntimeException(error))
            Diagnostics.dump(this, "CAPTURE_FAILED ${job.kind}: ${meta.items.joinToString { "${it.gf}/${it.type}/${it.doneAt}/${it.historyDate}" }}",
                activeGrabSnapshots(), force = true)
            val now = SystemClock.uptimeMillis()
            if (now - lastFailToastAt > 30_000L) {
                lastFailToastAt = now
                toast("⚠️ ${error ?: "แคปไม่สำเร็จ"} — ออเดอร์ยังเป็น PENDING และจะลองใหม่")
            }
            // Retry a viewport, then continue the sweep with explicit missing proof. One damaged
            // page/permission failure must not strand the rest of the day's History indefinitely.
            val signature = meta.dedupeKeys.sorted().joinToString("|")
            if (signature == captureFailureSignature) captureFailureCount++ else {
                captureFailureSignature = signature
                captureFailureCount = 1
            }
            if (captureFailureCount >= 3 && job.kind != RecordKind.MANUAL) {
                captureFailureCount = 0
                continueSweep(job.kind == RecordKind.READY)
            } else scheduleScan()
            return
        }

        captureFailureCount = 0
        // Batch captures can save only the subset still visible when Android delivered the bitmap.
        // Release every requested GF that was not actually attached to this saved image so it is
        // immediately eligible for another capture instead of disappearing from coverage.
        val savedKeys = record.items.mapNotNull { Deduper.keyOf(it) }.toSet()
        val missingKeys = meta.dedupeKeys.filterNot { it in savedKeys }
        if (missingKeys.isNotEmpty()) deduper.forget(missingKeys)

        lastCaptureText = io.github.panuwattegif.readyproof.core.CaptureFeedback.status(record) +
            " · " + ReportText.time(record.t, ZoneId.systemDefault())
        if (config.showToast) io.github.panuwattegif.readyproof.core.CaptureFeedback.notification(record)?.let { toast(it) }

        if (job.kind in listOf(RecordKind.READY, RecordKind.DELAY, RecordKind.HISTORY)) {
            // Re-read this viewport before scrolling. Any GF not covered by the saved bitmap stays
            // fresh and is captured again; only when no pending target remains may the sweep move on.
            scheduleScan()
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
