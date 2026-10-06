package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import io.github.panuwattegif.readyproof.core.ClickInfo
import io.github.panuwattegif.readyproof.core.ClickMatcher
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
 * When the merchant taps the real Ready button, ReadyProof briefly opens the Ready tab,
 * scrolls the whole list by itself, captures any new order(s), then returns to Preparing.
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
        private const val PRESS_DEBOUNCE_MS = 650L
        private const val OPEN_READY_DELAY_MS = 750L
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

    private var lastPressAt = 0L
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    /** Sweep state. A forced Ready sweep is set only after ReadyProof itself clicks the Ready tab. */
    @Volatile private var forcedReadySweep = false
    @Volatile private var forcedHistorySweep = false
    @Volatile private var returnToPreparingAfterReady = false
    private var lastSweepSignature = ""
    private var repeatedSweepSignature = 0
    private var sweepScrolls = 0
    @Volatile private var returningReadyToTop = false
    private var returnToTopScrolls = 0
    private var historyTabWasSelected = false
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
    private var delayRepositionKey: String? = null
    private var delayRepositionAttempts = 0
    private val recentPageCaptures = LinkedHashMap<String, Long>()
    private var readySeenDate: LocalDate = LocalDate.now()
    private val readySeenToday = LinkedHashSet<String>()
    private val lastReadyLedgerAt = HashMap<String, Long>()

    @Volatile private var autoHistoryInProgress = false
    @Volatile private var autoHistoryTargetDate: LocalDate? = null
    @Volatile private var nextAutoHistoryAttemptAt = 0L
    @Volatile private var closingGate: ClosingHistoryGate? = null

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

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event, cfg)
        }
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

    /** Detect a real food-ready press, but do not save/log that Preparing screen. */
    private fun handleClick(event: AccessibilityEvent, cfg: Config) {
        val src = event.source
        val info = ClickInfo(
            ownText = src?.text?.toString(),
            desc = src?.contentDescription?.toString() ?: event.contentDescription?.toString(),
            eventTexts = event.text.map { it.toString() },
            className = (src?.className ?: event.className)?.toString(),
            viewId = src?.viewIdResourceName,
            roleDesc = src?.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString(),
        )
        val label = info.label()

        // If the user opens History, start an automatic History sweep.
        if (matchesAny(label, listOf("History", "ประวัติ"))) {
            // Our own navigation emits this event too. Wait for page confirmation, not just
            // ACTION_CLICK's return value, before starting any History scrolling.
            if (autoHistoryInProgress) return
            worker.post {
                closingGate = null
                startHistorySweep(LocalDate.now(), force = true)
            }
            return
        }

        if (closingGate != null || autoHistoryInProgress) return

        val now = System.currentTimeMillis()
        val isFoodReady = ClickMatcher.matches(info, cfg) && now - lastPressAt > PRESS_DEBOUNCE_MS
        if (!isFoodReady) return
        lastPressAt = now

        // Give Grab time to move the order from Preparing to Ready, then open Ready ourselves.
        worker.postDelayed({ openReadyTabForSweep(cfg) }, OPEN_READY_DELAY_MS)
    }

    private fun openReadyTabForSweep(cfg: Config) {
        if (closingGate != null || autoHistoryInProgress) return
        main.post {
            if (closingGate != null || autoHistoryInProgress) return@post
            val clicked = clickTab(cfg.readyTabLabels)
            if (clicked) {
                forcedReadySweep = true
                forcedHistorySweep = false
                returnToPreparingAfterReady = true
                resetSweepLoop()
                worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
            } else {
                toast("⚠️ หาแท็บ Ready / พร้อมจัดส่งไม่เจอ — เปิดแท็บนี้เอง 1 ครั้ง")
            }
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
                    maybeStartAutomaticHistory()
                    // Poll History as well as Ready: a transient empty accessibility root or
                    // omitted event must recover without a human touching the phone.
                    if (!returningReadyToTop && !returningHistoryToTop && !captureInFlight) scheduleScan()
                }
            } finally {
                runCatching { worker.postDelayed(this, READY_WATCH_INTERVAL_MS) }
            }
        }
    }

    /** Closing never interrupts pending orders: recheck both queues before each automatic pass. */
    private fun maybeStartAutomaticHistory() {
        if (closingGate != null || captureInFlight || autoHistoryInProgress || forcedHistorySweep ||
            forcedReadySweep || returningReadyToTop || returningHistoryToTop) return
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
            if (!config.enabled) {
                worker.post { retryClosing(gate, "พักการตรวจ: ปิดแคปอัตโนมัติอยู่") }
                return@post
            }
            val labels = if (gate.tab == ClosingTab.READY) config.readyTabLabels else gate.tab.labels
            val clicked = clickTab(labels)
            worker.postDelayed({
                if (closingGate !== gate) return@postDelayed
                if (!clicked) retryClosing(gate, "เปิด ${gate.tab.name} ไม่สำเร็จ / ต้องเปิด Grab ค้างไว้")
                else pollClosingTab(gate, day)
            }, 1_500L)
        }
    }

    private fun pollClosingTab(gate: ClosingHistoryGate, day: LocalDate) {
        if (closingGate !== gate) return
        if (!config.enabled || day != LocalDate.now()) {
            retryClosing(gate, "พักการตรวจ / วันเปลี่ยนแล้ว")
            return
        }
        // Only inspect the active Grab window: an overlay, locked phone or another app is UNKNOWN.
        val active = rootInActiveWindow
        val roots = if (active?.packageName?.toString() in config.targetPackages) {
            listOf(NodeSnapshot.capture(active!!, MAX_NODES))
        } else emptyList()
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
        closingGate = null
        nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_CLICK_RETRY_MS
        closingStatus(reason)
        // Continue capturing Ready evidence during the wait; never leave the monitor in Preparing.
        main.post { if (config.enabled) clickTab(config.readyTabLabels) }
        scheduleScan()
    }

    /** Manual hook used by the UI for testing; automatic end-of-day scanning uses the same path. */
    fun requestHistorySweepNow() {
        if (!::worker.isInitialized) return
        worker.postDelayed({
            closingGate = null
            startHistorySweep(LocalDate.now(), force = true)
        }, 2_500L)
    }

    private fun startHistorySweep(day: LocalDate, force: Boolean = false) {
        if (autoHistoryInProgress || forcedHistorySweep) return
        if (!force && System.currentTimeMillis() < nextAutoHistoryAttemptAt) return
        autoHistoryInProgress = true
        autoHistoryTargetDate = day
        sweepShopId = ShopStore.get(this)?.id
        main.post {
            val clicked = clickTab(listOf("History", "ประวัติ"))
            if (clicked) {
                worker.postDelayed({ confirmHistoryPage(day, SystemClock.uptimeMillis()) }, 1_500L)
            } else {
                worker.post { historyNavigationFailed() }
            }
        }
    }

    private fun confirmHistoryPage(day: LocalDate, startedAt: Long) {
        if (!autoHistoryInProgress || autoHistoryTargetDate != day || forcedHistorySweep) return
        val active = rootInActiveWindow
        val snaps = if (active?.packageName?.toString() in config.targetPackages)
            listOf(NodeSnapshot.capture(active!!, MAX_NODES)) else emptyList()
        val analysis = ScreenAnalyzer.analyze(snaps, config)
        val selected = selectedTabOpen(snaps, listOf("History", "ประวัติ"))
        val otherSelected = ClosingQueueAnalyzer.selected(snaps,
            config.readyTabLabels + ClosingTab.PREPARING.labels + listOf("Upcoming", "ที่กำลังจะถึง"))
        // When selection is omitted, terminal-row content confirms History, never an empty tree.
        val terminal = analysis.items.any { it.type in listOf(ObsType.DONE, ObsType.CANCELLED, ObsType.DELAY) }
        if (config.enabled && !otherSelected && (selected || terminal)) {
            forcedHistorySweep = true
            forcedReadySweep = false
            returnToPreparingAfterReady = false
            historyTabWasSelected = selected
            resetSweepLoop()
            closingStatus("เปิด History แล้ว: กำลังกวาดรายการทั้งวัน")
            startHistoryAtTop()
        } else if (!config.enabled || SystemClock.uptimeMillis() - startedAt >= 10_000L) {
            historyNavigationFailed()
        } else worker.postDelayed({ confirmHistoryPage(day, startedAt) }, 700L)
    }

    private fun historyNavigationFailed() {
        autoHistoryInProgress = false
        autoHistoryTargetDate = null
        nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_CLICK_RETRY_MS
        closingStatus("ยังเปิดหรือยืนยันหน้า History ไม่สำเร็จ: จะตรวจและลองใหม่ใน 1 นาที")
        main.post { if (config.enabled) clickTab(config.readyTabLabels) }
        toast("⚠️ เปิด History อัตโนมัติไม่สำเร็จ — จะลองใหม่")
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
        if (closingGate != null || (autoHistoryInProgress && !forcedHistorySweep)) return
        if (returningReadyToTop || returningHistoryToTop || captureInFlight) return
        val cfg = config
        if (!cfg.enabled) return
        val roots = targetRoots(cfg)
        val snaps = roots.map { NodeSnapshot.capture(it, MAX_NODES) }
        if (snaps.isEmpty()) return
        if (cfg.diagnostics) Diagnostics.dump(this, "SCAN", snaps, force = false)

        var analysis = ScreenAnalyzer.analyze(snaps, cfg, allowUnknownDelayed = forcedHistorySweep)

        // Detect History from the selected tab itself. Some Grab builds emit a click event from
        // the tab container with no text, so relying only on TYPE_VIEW_CLICKED can miss the sweep.
        val historyTabSelected = selectedTabOpen(snaps, listOf("History", "ประวัติ"))
        if (historyTabSelected && !historyTabWasSelected && !forcedReadySweep) {
            forcedHistorySweep = true
            autoHistoryTargetDate = LocalDate.now()
            sweepShopId = ShopStore.get(this)?.id
            resetSweepLoop()
            historyTabWasSelected = true
            startHistoryAtTop()
            return
        }
        historyTabWasSelected = historyTabSelected

        // If we successfully clicked Ready ourselves but Grab does not expose tab-selected state,
        // the current list is still known to be Ready. Mark every listed GF as READY evidence.
        if (forcedReadySweep && analysis.readyTab != false) {
            val existing = analysis.items.filterNot { it.type == ObsType.READY }
            val readyItems = analysis.cards.filter { it.inList }.distinctBy { it.gf }.map { card ->
                Item(
                    gf = card.gf,
                    type = ObsType.READY,
                    status = ScreenAnalyzer.statusLine(card, cfg.gfExtractor()),
                    card = card.texts,
                )
            }
            analysis = analysis.copy(items = (readyItems + existing).distinctBy { Triple(it.gf, it.type, it.doneAt) })
        }

        val historyMode = forcedHistorySweep || historyTabSelected || analysis.items.any { it.type == ObsType.DONE || it.type == ObsType.DELAY || it.type == ObsType.CANCELLED }
        val readyMode = forcedReadySweep || analysis.readyTab == true
        val sweepMode = readyMode || historyMode
        if (historyMode) {
            val dated = HistoryDates.assign(analysis.items, analysis, snaps, LocalDate.now(), historyHeader)
            analysis = analysis.copy(items = dated.first)
            historyHeader = dated.second
        }

        if (sweepMode) {
            // Include card positions, not just GF numbers. Grab often scrolls by less than a full
            // card, so the visible GF set can stay identical for several successful scrolls.
            val signature = analysis.cards.filter { it.inList }
                .joinToString("|") { "${it.gf}@${it.node.top}:${it.node.bottom}" }
            updateSweepSignature(signature.ifEmpty { analysis.visible.joinToString("|") })
        }

        val today = LocalDate.now()
        if (today != readySeenDate) {
            deduper.clear()
            readySeenDate = today
            readySeenToday.clear()
            lastReadyLedgerAt.clear()
            historySeenKeys.clear()
        }
        val now = System.currentTimeMillis()

        // Coverage ledger is written before screenshotting. If Android misses a bitmap we still
        // know exactly which GF reached Ready, while the screenshot deduper keeps it eligible.
        if (readyMode) {
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
        // Short periodic recapture also covers reused GF numbers that return to Ready quickly.
        val fresh = deduper.fresh(analysis.items, now, cfg.copy(readyRepeatMinutes = 1))

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
 …2415 tokens truncated…   }
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
        if (candidates.isEmpty()) return false

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
        }
        return false
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
        if (node.isScrollable || node.collectionInfo != null) out += node
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

        lastCaptureText = record.kind.label + " " + record.gfs.joinToString(", ").ifEmpty { "-" } +
            " · " + ReportText.time(record.t, ZoneId.systemDefault())
        if (config.showToast) toast("📸 " + record.kind.label + ": " + record.gfs.joinToString(", "))

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
