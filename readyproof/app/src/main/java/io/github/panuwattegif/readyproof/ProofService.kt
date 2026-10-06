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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
 * When the merchant opens History at closing time, ReadyProof scrolls the list by itself and
 * screenshots delayed rows only. Preparing-button presses are NOT stored as evidence.
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
                deduper.seed(startupRecords.filter { it.shopId == ShopStore.get(this)?.id })
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
            autoHistoryTargetDate = LocalDate.now()
            sweepShopId = ShopStore.get(this)?.id
            forcedHistorySweep = true
            forcedReadySweep = false
            returnToPreparingAfterReady = false
            resetSweepLoop()
            worker.post { startHistoryAtTop() }
            return
        }

        val now = System.currentTimeMillis()
        val isFoodReady = ClickMatcher.matches(info, cfg) && now - lastPressAt > PRESS_DEBOUNCE_MS
        if (!isFoodReady) return
        lastPressAt = now

        // Give Grab time to move the order from Preparing to Ready, then open Ready ourselves.
        worker.postDelayed({ openReadyTabForSweep(cfg) }, OPEN_READY_DELAY_MS)
    }

    private fun openReadyTabForSweep(cfg: Config) {
        main.post {
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
                    if (!autoHistoryInProgress && !returningReadyToTop) scheduleScan()
                }
            } finally {
                runCatching { worker.postDelayed(this, READY_WATCH_INTERVAL_MS) }
            }
        }
    }

    /**
     * Shop close workflow. The proof phone normally stays on Ready all day. At close + 15 minutes
     * ReadyProof opens History itself, sweeps the full list, counts terminal orders and captures
     * every delayed row. If Ready-vs-Completed or DELAY proof coverage is incomplete it returns to
     * Ready and retries History later; the user does not have to change tabs.
     */
    private fun maybeStartAutomaticHistory() {
        if (autoHistoryInProgress || forcedHistorySweep || forcedReadySweep || returningReadyToTop) return
        val now = LocalDateTime.now()
        val due = when (now.dayOfWeek) {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY -> LocalTime.of(19, 15)
            DayOfWeek.SATURDAY -> LocalTime.of(16, 15)
            DayOfWeek.SUNDAY -> null
        } ?: return
        if (now.toLocalTime().isBefore(due)) return
        if (System.currentTimeMillis() < nextAutoHistoryAttemptAt) return
        val day = now.toLocalDate()
        val completed = ConfigStore.prefs(this).getString(AUTO_HISTORY_PREF_COMPLETE_DATE, null)
        // Revisit completed passes too: Grab can add late terminal rows after the first close sweep.
        startHistorySweep(day)
    }

    /** Manual hook used by the UI for testing; automatic end-of-day scanning uses the same path. */
    fun requestHistorySweepNow() {
        if (!::worker.isInitialized) return
        worker.post { startHistorySweep(LocalDate.now(), force = true) }
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
                forcedHistorySweep = true
                forcedReadySweep = false
                returnToPreparingAfterReady = false
                resetSweepLoop()
                worker.post { startHistoryAtTop() }
            } else {
                autoHistoryInProgress = false
                autoHistoryTargetDate = null
                nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_CLICK_RETRY_MS
                toast("⚠️ เปิด History อัตโนมัติไม่สำเร็จ — จะลองใหม่")
            }
        }
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
        if (returningReadyToTop || returningHistoryToTop || captureInFlight) return
        val cfg = config
        if (!cfg.enabled) return
        val roots = targetRoots(cfg)
        val snaps = roots.map { NodeSnapshot.capture(it, MAX_NODES) }
        if (snaps.isEmpty()) return
        if (cfg.diagnostics) Diagnostics.dump(this, "SCAN", snaps, force = false)

        var analysis = ScreenAnalyzer.analyze(snaps, cfg)

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
        lastSweepSignature = ""
        repeatedSweepSignature = 0
        sweepScrolls = 0
        delayRepositionKey = null
        delayRepositionAttempts = 0
    }

    private fun continueSweep(readyMode: Boolean? = null) {
        worker.postDelayed({
            main.post {
                // Stop only when the viewport truly stops moving several times or the safety cap
                // is reached. The old GF-only signature could stop while the list was still moving.
                val canContinue = repeatedSweepSignature < 4 && sweepScrolls < MAX_SWEEP_SCROLLS
                val moved = canContinue && scrollOrderListForward()
                if (!moved && forcedHistorySweep) historyReachedEnd = historyAtTop && canContinue &&
                    scrollContainerFound && repeatedSweepSignature < 4
                if (moved) {
                    sweepScrolls++
                    lastScrollAt = SystemClock.uptimeMillis()
                    worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
                } else {
                    finishSweep(readyMode ?: forcedReadySweep)
                }
            }
        }, AUTO_SCROLL_DELAY_MS)
    }

    private fun finishSweep(wasReady: Boolean) {
        if (wasReady || forcedReadySweep) {
            forcedReadySweep = false
            resetSweepLoop()
            if (returnToPreparingAfterReady) {
                returnToPreparingAfterReady = false
                // Legacy same-device flow: return only when ReadyProof opened Ready itself.
                main.postDelayed({ clickTab(listOf("Preparing", "กำลังเตรียม")) }, 250L)
            } else {
                // Dedicated proof phone stays on Ready. Sweep back to the top so an order inserted
                // above the current viewport cannot be missed between Accessibility events.
                startReturnReadyToTop()
                return
            }
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
                lastCaptureText = result
                autoHistoryInProgress = false
                autoHistoryTargetDate = null
                main.post {
                    // Return to the dedicated Ready monitor after every History pass. If counts are
                    // incomplete the scheduled retry will revisit History automatically.
                    clickTab(config.readyTabLabels)
                    toast(if (complete) "✓ History ครบ: $result" else "⚠️ History ยังไม่ครบ: $result — จะลองใหม่")
                }
            } catch (e: Exception) {
                autoHistoryInProgress = false
                autoHistoryTargetDate = null
                nextAutoHistoryAttemptAt = System.currentTimeMillis() + AUTO_HISTORY_RETRY_MS
                Diagnostics.error(this, "finishAutomaticHistory", e)
                main.post { clickTab(config.readyTabLabels) }
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
        fun step() {
            main.postDelayed({
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
        if (returningReadyToTop) return
        returningReadyToTop = true
        returnToTopScrolls = 0
        continueReturnReadyToTop()
    }

    private fun continueReturnReadyToTop() {
        worker.postDelayed({
            main.post {
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
        val wanted = labels.map(TextNorm::key).filter { it.isNotEmpty() }
        for (root in roots) {
            for (node in root.walk()) {
                val hit = node.ownStrings().any { raw ->
                    val t = TextNorm.key(raw)
                    wanted.any { w -> t == w || t.startsWith("$w ") || t.startsWith("$w(") }
                }
                if (!hit) continue
                var cur: UiNode? = node
                repeat(4) {
                    val n = cur ?: return@repeat
                    if (n.selected) return true
                    cur = n.parent
                }
            }
        }
        return false
    }

    // ---- tab navigation -----------------------------------------------------------------------

    private fun clickTab(labels: List<String>): Boolean {
        for (root in targetRoots(config)) {
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
        val wanted = labels.map(TextNorm::key).filter { it.isNotEmpty() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val n = queue.removeFirst()
            val texts = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).map(TextNorm::key)
            if (texts.any { t -> wanted.any { w -> t == w || t.contains(w) } }) return n
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let(queue::addLast)
            }
        }
        return null
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
