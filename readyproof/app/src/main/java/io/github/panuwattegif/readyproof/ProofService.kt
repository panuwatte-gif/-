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
import io.github.panuwattegif.readyproof.core.UiNode
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches the target app (GrabMerchant) and takes proof screenshots:
 *  - PRESS  : the "พร้อมจัดส่ง" button is tapped (shot + which order + countdown left)
 *  - READY  : an order card shows a waiting-for/with-driver status (once per order)
 *  - DELAY  : the history list shows "ล่าช้าไป X นาที" (once per order)
 *  - MANUAL : the accessibility shortcut button, or the test button in the app
 * It only reads the screen; it never taps anything in the target app.
 */
class ProofService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ProofService? = null
            private set

        /** Last time an event arrived from the target app; the home screen shows it as a heartbeat. */
        @Volatile
        var lastTargetEventAt = 0L
            private set

        @Volatile
        var lastCaptureText: String? = null
            private set

        private const val MAX_NODES = 1500
        private const val SCAN_DELAY_MS = 500L
        private const val SCAN_MIN_INTERVAL_MS = 1200L
        private const val SCROLL_SETTLE_MS = 600L
        private const val TEXT_ONLY_SCAN_INTERVAL_MS = 5_000L
        private const val PRESS_DEBOUNCE_MS = 600L
        private const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var capture: CaptureManager
    private val deduper = Deduper()
    private val scanPending = AtomicBoolean(false)
    private val seq = AtomicInteger()

    @Volatile
    private var config: Config = Config.DEFAULT

    @Volatile
    private var lastScanAt = 0L

    @Volatile
    private var lastScrollAt = 0L
    private var lastPressAt = 0L

    @Volatile
    private var lastFailToastAt = 0L
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

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

    /** Limits delivered events to the target apps chosen in Settings. */
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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event, cfg)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollAt = SystemClock.uptimeMillis()
                scheduleScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Countdown timers change text every second; those alone only need an occasional look.
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

    // ---- PRESS: the "food ready" button -------------------------------------------------------

    private fun onClick(event: AccessibilityEvent, cfg: Config) {
        val src = event.source
        val info = ClickInfo(
            ownText = src?.text?.toString(),
            desc = src?.contentDescription?.toString() ?: event.contentDescription?.toString(),
            eventTexts = event.text.map { it.toString() },
            className = (src?.className ?: event.className)?.toString(),
            viewId = src?.viewIdResourceName,
            roleDesc = src?.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString(),
        )
        val now = System.currentTimeMillis()
        val matched = cfg.capturePress && ClickMatcher.matches(info, cfg) && now - lastPressAt > PRESS_DEBOUNCE_MS
        worker.post { ClickLog.add(this, ClickLog.Entry(now, info.label(), info.className, info.viewId, matched)) }
        if (!matched) return
        lastPressAt = now

        val job = CaptureJob(RecordKind.PRESS, now)
        capture.submit(job) // shoot first; work out which order it was while the shot is taken
        worker.post {
            val label = info.label()
            val meta = try {
                val roots = listOfNotNull(src?.window?.root ?: rootInActiveWindow)
                val snaps = roots.map { NodeSnapshot.capture(it, MAX_NODES, src) }
                if (cfg.diagnostics) Diagnostics.dump(this, "PRESS $label", snaps, force = true)
                val p = ScreenAnalyzer.analyzePress(snaps, cfg)
                val items = p.gf?.let { listOf(Item(it, ObsType.PRESS, status = label, countdown = p.countdown, card = p.card)) }
                    ?: emptyList()
                CaptureMeta(
                    items = items,
                    visible = p.visible,
                    click = label,
                    toast = "📸 ${p.gf ?: "ไม่พบเลข GF"} กดพร้อมจัดส่ง" + (p.countdown?.let { " (เหลือ $it)" } ?: ""),
                )
            } catch (e: Exception) {
                Diagnostics.error(this, "press", e)
                CaptureMeta(click = label, toast = "📸 กดพร้อมจัดส่ง (อ่านเลข GF ไม่ได้)")
            }
            job.setMeta(meta)
        }
    }

    // ---- READY / DELAY / DONE: watching what is on screen -------------------------------------

    private fun scheduleScan() {
        if (!scanPending.compareAndSet(false, true)) return
        val sinceLast = SystemClock.uptimeMillis() - lastScanAt
        worker.postDelayed(scanRunnable, maxOf(SCAN_DELAY_MS, SCAN_MIN_INTERVAL_MS - sinceLast))
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            // Wait until scrolling stops so the shot shows the rows that were read.
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
        val snaps = targetRoots(cfg).map { NodeSnapshot.capture(it, MAX_NODES) }
        if (snaps.isEmpty()) return
        if (cfg.diagnostics) Diagnostics.dump(this, "SCAN", snaps, force = false)
        val analysis = ScreenAnalyzer.analyze(snaps, cfg)
        if (analysis.items.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = deduper.fresh(analysis.items, now, cfg)
        if (fresh.isEmpty()) return
        val keys = fresh.mapNotNull { Deduper.keyOf(it) }
        deduper.mark(keys, now)

        val ready = cfg.captureReady && fresh.any { it.type == ObsType.READY }
        val delay = cfg.captureDelay && fresh.any { it.type == ObsType.DELAY }
        if (ready || delay) {
            val kind = if (ready) RecordKind.READY else RecordKind.DELAY
            val job = CaptureJob(kind, now)
            job.setMeta(CaptureMeta(items = fresh, visible = analysis.visible, dedupeKeys = keys, toast = toastFor(kind, fresh)))
            capture.submit(job)
        } else {
            // Finished orders without a delay: remembered for the daily totals, no screenshot.
            RecordStore.append(this, Record(id = "$now-s${seq.incrementAndGet()}", t = now, kind = RecordKind.SEEN, items = fresh, visible = analysis.visible))
        }
    }

    private fun toastFor(kind: RecordKind, items: List<Item>): String = when (kind) {
        RecordKind.READY -> "📸 READY: " + items.filter { it.type == ObsType.READY }.joinToString(", ") { it.gf }
        else -> "📸 ล่าช้า: " + items.filter { it.type == ObsType.DELAY }
            .joinToString(", ") { it.gf + (it.delayMin?.let { m -> " ($m นาที)" } ?: "") }
    }

    /** Roots of the target app's windows (list + any dialog on top). */
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

    // ---- MANUAL: accessibility shortcut button and the in-app test --------------------------

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

    /** Takes a screenshot after [delayMs] (lets the user switch to Grab when testing). */
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
                items = ScreenAnalyzer.visibleItems(analysis, cfg),
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

    // ---- results ------------------------------------------------------------------------------

    private fun onCaptureDone(job: CaptureJob, record: Record?, error: String?) {
        val meta = job.awaitMeta(0)
        if (record == null) {
            deduper.forget(meta.dedupeKeys)
            Diagnostics.error(this, "capture ${job.kind}", RuntimeException(error))
            // Failures always show (even with toasts off), but at most every 30 s.
            val now = SystemClock.uptimeMillis()
            if (now - lastFailToastAt > 30_000L) {
                lastFailToastAt = now
                toast("⚠️ ${error ?: "แคปไม่สำเร็จ"} — ถ้าเป็นบ่อยให้ถ่ายหน้าจอเองไปก่อน")
            }
            return
        }
        lastCaptureText = record.kind.label + " " + record.gfs.joinToString(", ").ifEmpty { "-" } +
            " · " + ReportText.time(record.t, ZoneId.systemDefault())
        if (config.showToast) toast(meta.toast ?: "📸 บันทึกแล้ว")
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
