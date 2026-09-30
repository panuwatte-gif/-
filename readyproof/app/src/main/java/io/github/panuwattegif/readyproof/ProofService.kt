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
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.TextNorm
import io.github.panuwattegif.readyproof.core.UiNode
import java.time.LocalDate
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
        private const val SCROLL_SETTLE_MS = 650L
        private const val TEXT_ONLY_SCAN_INTERVAL_MS = 5_000L
        private const val PRESS_DEBOUNCE_MS = 650L
        private const val OPEN_READY_DELAY_MS = 750L
        private const val AUTO_SCROLL_DELAY_MS = 450L
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
                deduper.seed(RecordStore.loadRange(this, today.minusDays(1), today))
                Cleanup.runIfDue(this, config)
            } catch (e: Exception) {
                Diagnostics.error(this, "startup", e)
            }
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
            forcedHistorySweep = true
            forcedReadySweep = false
            returnToPreparingAfterReady = false
            resetSweepLoop()
            worker.postDelayed({ scheduleScan() }, SCROLL_SETTLE_MS)
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
        val cfg = config
        if (!cfg.enabled) return
        val roots = targetRoots(cfg)
        val snaps = roots.map { NodeSnapshot.capture(it, MAX_NODES) }
        if (snaps.isEmpty()) return
        if (cfg.diagnostics) Diagnostics.dump(this, "SCAN", snaps, force = false)

        var analysis = ScreenAnalyzer.analyze(snaps, cfg)

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

        val historyMode = forcedHistorySweep || analysis.items.any { it.type == ObsType.DONE || it.type == ObsType.DELAY }
        val readyMode = forcedReadySweep || analysis.readyTab == true
        val sweepMode = readyMode || historyMode

        if (sweepMode) updateSweepSignature(analysis.visible)

        val now = System.currentTimeMillis()
        val fresh = deduper.fresh(analysis.items, now, cfg)
        val keys = fresh.mapNotNull { Deduper.keyOf(it) }
        if (keys.isNotEmpty()) deduper.mark(keys, now)

        val ready = cfg.captureReady && fresh.any { it.type == ObsType.READY }
        val delay = cfg.captureDelay && fresh.any { it.type == ObsType.DELAY }

        if (ready || delay) {
            val kind = if (ready) RecordKind.READY else RecordKind.DELAY
            val job = CaptureJob(kind, now)
            job.setMeta(
                CaptureMeta(
                    items = fresh,
                    visible = analysis.visible,
                    dedupeKeys = keys,
                    toast = toastFor(kind, fresh),
                )
            )
            capture.submit(job)
            // Continue scrolling only after this screenshot finishes, so proof and metadata stay aligned.
            return
        }

        // History rows without a delay are kept as text-only observations. No screenshot.
        if (fresh.any { it.type == ObsType.DONE || it.type == ObsType.DELAY }) {
            RecordStore.append(
                this,
                Record(
                    id = "$now-s${seq.incrementAndGet()}",
                    t = now,
                    kind = RecordKind.SEEN,
                    items = fresh,
                    visible = analysis.visible,
                )
            )
        }

        if (sweepMode) continueSweep(readyMode)
    }

    private fun toastFor(kind: RecordKind, items: List<Item>): String = when (kind) {
        RecordKind.READY -> "📸 READY: " + items.filter { it.type == ObsType.READY }.joinToString(", ") { it.gf }
        else -> "📸 ล่าช้า: " + items.filter { it.type == ObsType.DELAY }
            .joinToString(", ") { it.gf + (it.delayMin?.let { m -> " ($m นาที)" } ?: "") }
    }

    // ---- automatic scrolling ------------------------------------------------------------------

    private fun updateSweepSignature(visible: List<String>) {
        val signature = visible.joinToString("|")
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
    }

    private fun continueSweep(readyMode: Boolean? = null) {
        worker.postDelayed({
            main.post {
                // A repeated viewport is a second safety net for apps that claim a scroll succeeded at the end.
                val moved = repeatedSweepSignature < 2 && scrollOrderListForward()
                if (moved) {
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
                // Return to the working tab so the automated proof collection does not interrupt staff.
                main.postDelayed({ clickTab(listOf("Preparing", "กำลังเตรียม")) }, 250L)
            }
        }
        if (forcedHistorySweep) {
            forcedHistorySweep = false
            resetSweepLoop()
        }
    }

    /** Scroll the most likely order-list widget by one page. */
    private fun scrollOrderListForward(): Boolean {
        val roots = targetRoots(config)
        val candidates = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) collectScrollable(root, candidates, 0)
        if (candidates.isEmpty()) return false

        // Prefer Recycler/List widgets over an outer ScrollView.
        val node = candidates.firstOrNull {
            val c = it.className?.toString().orEmpty()
            c.contains("Recycler", ignoreCase = true) || c.contains("ListView", ignoreCase = true)
        } ?: candidates.first()
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }.getOrDefault(false)
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 50 || out.size > 20) return
        if (node.isScrollable) out += node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectScrollable(child, out, depth + 1)
        }
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
        val job = CaptureJob(RecordKind.MANUAL, System.currentTimeMillis())
        capture.submit(job)
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
    }

    // ---- screenshot result --------------------------------------------------------------------

    private fun onCaptureDone(job: CaptureJob, record: Record?, error: String?) {
        val meta = job.awaitMeta(0)
        if (record == null) {
            deduper.forget(meta.dedupeKeys)
            Diagnostics.error(this, "capture ${job.kind}", RuntimeException(error))
            val now = SystemClock.uptimeMillis()
            if (now - lastFailToastAt > 30_000L) {
                lastFailToastAt = now
                toast("⚠️ ${error ?: "แคปไม่สำเร็จ"} — ถ้าเป็นบ่อยให้ถ่ายหน้าจอเองไปก่อน")
            }
            if (forcedReadySweep || forcedHistorySweep) continueSweep(forcedReadySweep)
            return
        }

        lastCaptureText = record.kind.label + " " + record.gfs.joinToString(", ").ifEmpty { "-" } +
            " · " + ReportText.time(record.t, ZoneId.systemDefault())
        if (config.showToast) toast(meta.toast ?: "📸 บันทึกแล้ว")

        if (job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) {
            if (forcedReadySweep || forcedHistorySweep) continueSweep(forcedReadySweep)
            else scheduleScan()
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
