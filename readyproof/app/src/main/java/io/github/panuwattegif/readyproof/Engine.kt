package io.github.panuwattegif.readyproof

import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.panuwattegif.readyproof.core.Box
import io.github.panuwattegif.readyproof.core.CardView
import io.github.panuwattegif.readyproof.core.Config
import io.github.panuwattegif.readyproof.core.Deduper
import io.github.panuwattegif.readyproof.core.HistoryHeader
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.OrderTab
import io.github.panuwattegif.readyproof.core.ReadyTracker
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.ScreenAnalysis
import io.github.panuwattegif.readyproof.core.TabDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Runs the dedicated Ready-tab phone:
 *  1. Ready tab: every order listed there gets one verified screenshot per stay; the list is
 *     swept top to bottom by itself when it is longer than the screen.
 *  2. Guard: brings Grab back to the Ready tab when it ends up elsewhere.
 *  3. End of day: after closing time, waits until the Ready tab is empty, reads the whole
 *     History list (photographing every delayed order) and builds the report.
 * Everything that touches the screen runs one job at a time (the "lane").
 */
class Engine(private val service: ProofService) {

    companion object {
        private const val TICK_MS = 10_000L
        private const val OBSERVE_DELAY_MS = 700L
        private const val TEXT_ONLY_MIN_GAP_MS = 3_000L
        private const val IDLE_OBSERVE_MS = 30_000L
        private const val MAX_PAGES = 60
        private const val MAX_HISTORY_PAGES = 150
        private const val MAX_FAILURES = 5
        private const val PENDING_SWEEP_GAP_MS = 60_000L
        private const val LAUNCH_GAP_MS = 5 * 60_000L
        private const val BACK_GAP_MS = 10 * 60_000L
        private const val PROBLEM_NOTE_GAP_MS = 30 * 60_000L
        private const val EOD_DONE_KEY = "eod_done_day"
        private const val BOX_TOLERANCE = 3
        private const val PERSON_GRACE_MS = 20_000L
        private const val SCROLL_BLOCK_MS = 30 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lane = Mutex()
    private val reader = ScreenReader(service)
    private val actor = Actor(service)
    val capture = CaptureManager(service)
    val tracker = ReadyTracker()
    private val historySeen = Deduper()
    private val seq = AtomicInteger()

    private val cfg: Config get() = ConfigStore.get(service)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ---- state (main thread only) --------------------------------------------------------------
    private var busyDepth = 0
    private var dirty = false
    private var observeJob: Job? = null
    private var lastObserveAt = 0L
    private var lastUserTouchAt = 0L
    private var lastBusyEndAt = 0L
    private var lastGrabSeenAt = 0L
    private var lastLaunchAt = 0L
    private var lastBackAt = 0L
    private var lastPendingSweepAt = 0L
    private var lastProblemAt = 0L
    private var scrollBlockedUntil = 0L
    private var fingerprint: String? = null
    private var historyDay: String? = null
    private var lastStats: String? = null
    private var awake: View? = null

    @Volatile
    var lastSweepAt = 0L
        private set

    @Volatile
    var lastShotText: String? = null
        private set

    @Volatile
    private var where: String = "เริ่มทำงาน"

    // end of day
    private enum class Eod { IDLE, WAITING, RUNNING, DONE }

    private var eod = Eod.IDLE
    private var eodNextAt = 0L
    private var eodStartedAt = 0L
    private var eodForced = false

    @Volatile
    var eodStatus: String? = null
        private set

    private val busy: Boolean get() = busyDepth > 0

    fun start() {
        scope.launch {
            try {
                val today = LocalDate.now()
                val recent = withContext(Dispatchers.IO) { RecordStore.loadRange(service, today.minusDays(1), today) }
                tracker.seed(recent, System.currentTimeMillis())
                historySeen.seed(recent)
                if (ConfigStore.prefs(service).getString(EOD_DONE_KEY, null) == today.toString()) eod = Eod.DONE
                withContext(Dispatchers.IO) { Cleanup.runIfDue(service, cfg) }
            } catch (e: Exception) {
                Diagnostics.error(service, "startup", e)
            }
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Diagnostics.error(service, "tick", e)
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        scope.cancel()
        removeAwake()
        capture.shutdown()
    }

    // ---- events ---------------------------------------------------------------------------------

    fun onEvent(type: Int, contentChangeTypes: Int) {
        val now = SystemClock.uptimeMillis()
        if (busy) {
            dirty = true
            return
        }
        if ((type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            now - lastBusyEndAt > 2_000L
        ) {
            lastUserTouchAt = now
        }
        val textOnly = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && contentChangeTypes != 0 &&
            (contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT.inv()) == 0
        if (textOnly && now - lastObserveAt < TEXT_ONLY_MIN_GAP_MS) return
        scheduleObserve()
    }

    private fun scheduleObserve() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            delay(OBSERVE_DELAY_MS)
            observe()
        }
    }

    /** One look at the screen; may take shots or start a sweep of the Ready list. */
    private suspend fun observe() {
        val c = cfg
        if (!c.enabled) return
        if (!lane.tryLock()) {
            dirty = true
            return
        }
        try {
            lastObserveAt = SystemClock.uptimeMillis()
            val s = reader.read(c) ?: return
            lastGrabSeenAt = SystemClock.uptimeMillis()
            if (c.diagnostics) Diagnostics.dump(service, "SCAN ${s.a.tab}", s.roots, force = false)
            when {
                isReady(s) -> onReady(s)
                s.a.tab == OrderTab.HISTORY -> onHistory(s)
                else -> {
                    historyDay = null
                    where = "Grab เปิดอยู่ แต่ไม่ใช่แท็บ Ready"
                }
            }
        } catch (e: Exception) {
            Diagnostics.error(service, "observe", e)
        } finally {
            lane.unlock()
            if (dirty) {
                dirty = false
                scheduleObserve()
            }
        }
    }

    private fun isReady(s: Screen): Boolean =
        s.a.tab == OrderTab.READY || (s.a.tab == null && s.a.readyGfs().isNotEmpty())

    // ---- Ready tab ------------------------------------------------------------------------------

    private suspend fun onReady(first: Screen) {
        val c = cfg
        val now = System.currentTimeMillis()
        var s = first
        val listed = s.a.readyGfs()
        tracker.seen(listed, now)
        // The whole list is on screen: whatever is not listed has been picked up.
        if (!s.a.canScroll) {
            tracker.complete(listed, now)
            lastSweepAt = now
        }
        if (s.dialog && tracker.pending().isNotEmpty()) problem("Grab มีหน้าต่างเด้งบังรายการ Ready อยู่ — แคปไม่ได้จนกว่าจะปิด")
        shootReady(s)?.let { s = it }
        val fp = fingerprintOf(s.a)
        val changed = fp != fingerprint
        fingerprint = fp
        val pending = retryable()
        val up = SystemClock.uptimeMillis()
        val sweepForPending = pending.isNotEmpty() && up - lastPendingSweepAt > PENDING_SWEEP_GAP_MS
        val personScrolling = up - lastUserTouchAt < PERSON_GRACE_MS
        if (c.autoScroll && s.a.canScroll && !personScrolling && up > scrollBlockedUntil &&
            (changed || sweepForPending || sweepDue())
        ) {
            if (sweepForPending) lastPendingSweepAt = up
            sweepReady()
        } else if (s.a.readyViews().any { !it.full && it.gf in pending }) {
            automate { fixPartials(s) }
        }
        where = "เฝ้าแท็บ Ready · รอไรเดอร์ ${tracker.present().size} ออเดอร์"
    }

    /** Orders still without a good shot that are worth another try. */
    private fun retryable(): Set<String> =
        tracker.pending().filter { (tracker.stay(it)?.failures ?: 0) < MAX_FAILURES }.toSet()

    private fun sweepDue(): Boolean = System.currentTimeMillis() - lastSweepAt > cfg.fullSweepMinutes * 60_000L

    private fun fingerprintOf(a: ScreenAnalysis): String =
        a.readyGfs().joinToString(",") + "|" + a.readyCount + "|" + a.canScrollForward + a.canScrollBackward

    /** Shoots the listed orders that still need a shot and are completely on screen now. */
    private suspend fun shootReady(s: Screen): Screen? {
        if (!cfg.captureReady) return null
        val pending = tracker.pending().toSet()
        val targets = s.a.readyViews().filter { it.full && it.gf in pending }
        if (targets.isEmpty()) return null
        val t = System.currentTimeMillis()
        val (ok, after) = shoot(RecordKind.READY, s, targets, null) { v -> v.items.filter { it.type == ObsType.READY } }
        tracker.shot(ok.map { it.gf }, t)
        tracker.failed(targets.filter { it !in ok }.map { it.gf })
        return after
    }

    /**
     * Takes one screenshot for [targets] and keeps it only for the orders whose number and status
     * line were still in exactly the same place right after the shot. Returns the orders proven
     * by the saved shot and the screen as read after it.
     */
    private suspend fun shoot(
        kind: RecordKind,
        before: Screen,
        targets: List<CardView>,
        day: String?,
        itemsOf: (CardView) -> List<Item>,
    ): Pair<List<CardView>, Screen?> {
        val t = System.currentTimeMillis()
        val bitmap = capture.take()
        if (bitmap == null) {
            problem("แคปหน้าจอไม่สำเร็จ: ${capture.lastError ?: "-"}")
            return emptyList<CardView>() to null
        }
        val after = reader.read(cfg)
        val ok = if (after == null) {
            emptyList()
        } else {
            targets.filter { tv ->
                after.a.views.any { v -> v.gf == tv.gf && v.full && sameBoxes(v.keyBoxes, tv.keyBoxes) }
            }
        }
        if (ok.isEmpty()) {
            bitmap.recycle()
            Diagnostics.note(service, "discarded $kind shot: screen moved (${targets.joinToString { it.gf }})")
            return emptyList<CardView>() to after
        }
        val items = ok.flatMap(itemsOf)
        val record = capture.save(bitmap, kind, t, CaptureMeta(items = items, visible = before.a.visible, day = day))
            ?: return emptyList<CardView>() to after
        lastShotText = record.kind.label + " " + record.gfs.joinToString(", ") + " · " + ReportText.time(record.t, zone)
        Diagnostics.note(service, "shot $kind ${record.gfs.joinToString(",")}")
        if (cfg.showToast) service.toast("📸 ${record.kind.label}: ${record.gfs.joinToString(", ")}")
        return ok to after
    }

    /**
     * Same position on screen: the top and left edges match. Widths may differ because a status
     * such as "Driver arriving in 3 mins" changes its text; that does not move anything.
     */
    private fun sameBoxes(a: List<Box>, b: List<Box>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> abs(x.top - y.top) <= BOX_TOLERANCE && abs(x.left - y.left) <= BOX_TOLERANCE }

    /** Goes through the whole Ready list from the top and stops at the bottom (where new orders arrive). */
    private suspend fun sweepReady(): Unit = automate {
        var s = reader.read(cfg) ?: return@automate
        if (!isReady(s)) return@automate
        val started = System.currentTimeMillis()
        var pages = 0
        while (s.a.canScrollBackward && pages++ < MAX_PAGES) {
            if (!actor.scroll(s.a.scroller, forward = false)) break
            s = settle() ?: return@automate
            if (!isReady(s)) {
                scrolledAway()
                return@automate
            }
        }
        val seen = LinkedHashSet<String>()
        pages = 0
        while (true) {
            val now = System.currentTimeMillis()
            seen += s.a.readyGfs()
            tracker.seen(s.a.readyGfs(), now)
            shootReady(s)?.let { s = it }
            fixPartials(s)?.let { s = it }
            if (!isReady(s)) return@automate
            seen += s.a.readyGfs()
            if (!s.a.canScrollForward || pages++ >= MAX_PAGES) break
            val before = s.a.views.map { it.gf to it.keyBoxes }
            if (!actor.scroll(s.a.scroller, forward = true) && !dragPage(s)) break
            s = settle() ?: return@automate
            if (!isReady(s)) {
                scrolledAway()
                return@automate
            }
            if (s.a.views.map { it.gf to it.keyBoxes } == before) break
        }
        val now = System.currentTimeMillis()
        tracker.complete(seen, now)
        lastSweepAt = now
        fingerprint = fingerprintOf(s.a)
        Diagnostics.note(
            service,
            "sweep Ready: ${seen.size} orders, ${pages + 1} pages, ${now - started} ms, waiting=${tracker.present().size}, unshot=${tracker.pending()}",
        )
    }

    /** Our own scroll left the Ready tab (the list was not what we scrolled): stop scrolling for a while. */
    private fun scrolledAway() {
        if (SystemClock.uptimeMillis() - lastUserTouchAt < PERSON_GRACE_MS) return
        scrollBlockedUntil = SystemClock.uptimeMillis() + SCROLL_BLOCK_MS
        problem("เลื่อนรายการแล้วแท็บเปลี่ยน — หยุดเลื่อนเอง 30 นาที (ส่งไฟล์ช่วยแก้ปัญหาให้ผู้ดูแล)")
    }

    /** Orders that need a shot but are cut off at an edge: scroll them fully into view, then shoot. */
    private suspend fun fixPartials(start: Screen): Screen? {
        var s = start
        var changed = false
        val tried = HashSet<String>()
        while (true) {
            val pending = retryable()
            val v = s.a.readyViews().firstOrNull { !it.full && it.gf in pending && it.gf !in tried } ?: break
            tried += v.gf
            val shown = bringIntoView(s, v) ?: break
            s = shown
            changed = true
            shootReady(s)?.let { s = it }
        }
        return if (changed) s else null
    }

    /** Scrolls the list just enough for [v] to be fully visible; returns the new screen. */
    private suspend fun bringIntoView(s: Screen, v: CardView): Screen? {
        fun fullNow(x: Screen) = x.a.views.any { it.gf == v.gf && it.full }
        if (actor.showOnScreen(v.card.node)) {
            val after = settle() ?: return null
            if (fullNow(after)) return after
            return nudge(after, after.a.views.firstOrNull { it.gf == v.gf } ?: return after)
        }
        return nudge(s, v)
    }

    /**
     * Fallback when the list ignores "show on screen": drag it by about a third of its height,
     * away from the edge (or the floating button) that cuts the order off.
     */
    private suspend fun nudge(s: Screen, v: CardView): Screen? {
        val vp = viewportOf(s) ?: return null
        val card = v.card.node.box().takeIf { !it.isEmpty } ?: v.card.gfNode.box()
        if (card.isEmpty) return null
        val step = vp.height * 0.35f
        val dy = when {
            card.top <= vp.top + 2 -> step
            card.bottom >= vp.bottom - 2 -> -step
            (card.top + card.bottom) / 2 > (vp.top + vp.bottom) / 2 -> -step
            else -> step
        }
        val x = vp.left + vp.width * 0.3f
        val from = if (dy < 0) vp.bottom - vp.height * 0.15f else vp.top + vp.height * 0.15f
        if (!actor.drag(x, from, dy)) return null
        return settle()
    }

    /** Page down by dragging (for lists that ignore the scroll command). */
    private suspend fun dragPage(s: Screen): Boolean {
        val vp = viewportOf(s) ?: return false
        return actor.drag(vp.left + vp.width * 0.3f, vp.bottom - vp.height * 0.15f, -vp.height * 0.6f)
    }

    private fun viewportOf(s: Screen): Box? {
        val b = s.a.scroller?.box() ?: return null
        return if (b.isEmpty) null else b
    }

    /** Waits until the list stops moving and returns that screen. */
    private suspend fun settle(): Screen? {
        delay(350)
        var prev = reader.read(cfg) ?: return null
        repeat(8) {
            delay(250)
            val cur = reader.read(cfg) ?: return null
            if (sameLayout(prev, cur)) return cur
            prev = cur
        }
        return prev
    }

    private fun sameLayout(a: Screen, b: Screen): Boolean {
        val x = a.a.views
        val y = b.a.views
        return a.a.tab == b.a.tab && x.size == y.size &&
            x.zip(y).all { (p, q) -> p.gf == q.gf && sameBoxes(p.keyBoxes, q.keyBoxes) }
    }

    /** Marks a stretch of our own actions so the events they cause are not mistaken for a person. */
    private suspend fun <T> automate(block: suspend () -> T): T {
        busyDepth++
        try {
            return block()
        } finally {
            busyDepth--
            if (busyDepth == 0) lastBusyEndAt = SystemClock.uptimeMillis()
        }
    }

    // ---- History (a person scrolling it, or the end-of-day sweep) -------------------------------

    private suspend fun onHistory(s: Screen) {
        val c = cfg
        s.a.header?.let { h -> h.date?.let { historyDay = it.toString() }; saveStats(h) }
        recordRows(s, historyDay)
        if (c.captureDelay) shootDelays(s, historyDay)
        where = "Grab เปิดอยู่ที่แท็บประวัติ"
    }

    private fun saveStats(h: HistoryHeader) {
        val day = h.date ?: return
        if (h.completed == null) return
        val key = "$day|${h.completed}|${h.cancelled}"
        if (key == lastStats) return
        lastStats = key
        val now = System.currentTimeMillis()
        RecordStore.append(
            service,
            Record("$now-t${seq.incrementAndGet()}", now, RecordKind.STATS, day = day.toString(), completed = h.completed, cancelled = h.cancelled),
        )
    }

    /** Finished orders on screen, written down once each (no screenshot). */
    private fun recordRows(s: Screen, day: String?): List<Item> {
        val rows = s.a.historyViews().flatMap { v -> v.items.filter { it.type == ObsType.DONE && it.doneAt != null } }
        val now = System.currentTimeMillis()
        val fresh = historySeen.fresh(rows, now, cfg)
        if (fresh.isNotEmpty()) {
            historySeen.mark(fresh.mapNotNull { Deduper.keyOf(it) }, now)
            RecordStore.append(service, Record("$now-s${seq.incrementAndGet()}", now, RecordKind.SEEN, items = fresh, visible = s.a.visible, day = day))
        }
        return rows
    }

    /** Photographs delayed rows that are fully on screen and not photographed yet. */
    private suspend fun shootDelays(s: Screen, day: String?): Screen? {
        val now = System.currentTimeMillis()
        val targets = s.a.historyViews().filter { v ->
            v.full && v.items.any { it.type == ObsType.DELAY && historySeen.isFresh(Deduper.keyOf(it)!!, now, 36L * 3600_000) }
        }
        if (targets.isEmpty()) return null
        val (ok, after) = shoot(RecordKind.DELAY, s, targets, day) { v -> v.items.filter { it.type == ObsType.DELAY } }
        historySeen.mark(ok.flatMap { v -> v.items.filter { it.type == ObsType.DELAY }.mapNotNull { Deduper.keyOf(it) } }, now)
        return after
    }

    private fun delayPending(s: Screen): List<CardView> {
        val now = System.currentTimeMillis()
        return s.a.historyViews().filter { v ->
            v.items.any { it.type == ObsType.DELAY && historySeen.isFresh(Deduper.keyOf(it)!!, now, 36L * 3600_000) }
        }
    }

    private class HistoryResult(val day: String, val header: HistoryHeader?, val rows: Int, val delayed: Int, val unshot: List<String>)

    /** Reads the whole History list of the day shown, photographing every delayed order. */
    private suspend fun sweepHistory(): HistoryResult? = automate {
        val c = cfg
        if (!openTab(c.historyTabLabels, OrderTab.HISTORY)) return@automate null
        var s = settle() ?: return@automate null
        var pages = 0
        while (s.a.canScrollBackward && pages++ < MAX_HISTORY_PAGES) {
            if (!actor.scroll(s.a.scroller, forward = false)) break
            s = settle() ?: return@automate null
        }
        var header = s.a.header
        if (header?.completed == null) {
            // the list may still be loading
            delay(2_000)
            s = settle() ?: return@automate null
            header = s.a.header
        }
        val day = (header?.date ?: LocalDate.now()).toString()
        historyDay = day
        header?.let { saveStats(it) }
        val rows = LinkedHashMap<String, Item>()
        val delayed = LinkedHashSet<String>()

        suspend fun pass(step: suspend (Screen) -> Boolean) {
            pages = 0
            var still = 0
            while (true) {
                val before = rows.size
                recordRows(s, day).forEach { rows[Deduper.keyOf(it)!!] = it }
                s.a.historyViews().filter { it.has(ObsType.DELAY) }.forEach { delayed += it.gf }
                shootDelays(s, day)?.let { s = it }
                // delayed rows cut off at an edge
                for (v in delayPending(s).filter { !it.full }) {
                    val shown = bringIntoView(s, v) ?: continue
                    s = shown
                    shootDelays(s, day)?.let { s = it }
                }
                still = if (rows.size == before) still + 1 else 0
                if (pages++ >= MAX_HISTORY_PAGES) break
                if (!s.a.canScrollForward) {
                    // more rows may load at the end of the list
                    delay(1_500)
                    s = settle() ?: return
                    if (!s.a.canScrollForward) break
                }
                if (still >= 3) break
                if (!step(s)) break
                s = settle() ?: return
                if (s.a.tab != OrderTab.HISTORY) return
            }
        }

        pass { cur -> actor.scroll(cur.a.scroller, forward = true) || dragPage(cur) }
        val want = header?.completed
        if (want != null && rows.size < want) {
            // Second, slower pass with overlapping drags for rows the page jumps skipped.
            Diagnostics.note(service, "history: ${rows.size}/$want rows after first pass, second pass")
            pages = 0
            while (s.a.canScrollBackward && pages++ < MAX_HISTORY_PAGES) {
                if (!actor.scroll(s.a.scroller, forward = false)) break
                s = settle() ?: break
            }
            pass { cur -> dragPage(cur) || actor.scroll(cur.a.scroller, forward = true) }
        }
        // back to the top for whoever looks next
        pages = 0
        while (s.a.canScrollBackward && pages++ < MAX_HISTORY_PAGES) {
            if (!actor.scroll(s.a.scroller, forward = false)) break
            s = settle() ?: break
        }
        val now = System.currentTimeMillis()
        val unshot = delayPending(s).map { it.gf }
        Diagnostics.note(service, "history $day: rows=${rows.size}/${want ?: "?"} delayed=${delayed.size}")
        HistoryResult(day, header, rows.size, delayed.size, unshot).also { lastSweepAt = maxOf(lastSweepAt, now - 1) }
    }

    // ---- moving around inside Grab ---------------------------------------------------------------

    /** Opens the wanted order tab by tapping the tab bar (or "Orders" in the bottom bar first). */
    private suspend fun openTab(labels: List<String>, want: OrderTab): Boolean = automate {
        val c = cfg
        val gfx = c.gfExtractor()
        repeat(4) {
            val s = reader.read(c) ?: return@automate false
            if (s.a.tab == want) return@automate true
            if (s.dialog) return@automate false
            val tab = TabDetector.tapTarget(s.roots, labels, c.tabLabels, gfx)
            if (tab != null) {
                actor.click(tab)
                val after = settle() ?: return@automate false
                if (after.a.tab == want) return@automate true
                return@repeat
            }
            val nav = TabDetector.tapTarget(s.roots, c.ordersNavLabels, c.navLabels, gfx)
            if (nav != null) {
                actor.click(nav)
                settle()
                return@repeat
            }
            // A page without tabs or bottom bar (order details ...): one step back.
            val up = SystemClock.uptimeMillis()
            if (up - lastBackAt > BACK_GAP_MS) {
                lastBackAt = up
                actor.back()
                settle()
                return@repeat
            }
            return@automate false
        }
        reader.read(c)?.a?.tab == want
    }

    // ---- the 10-second heartbeat ----------------------------------------------------------------

    private suspend fun tick() {
        val c = cfg
        if (!c.enabled) {
            updateAwake(false)
            where = "ปิดการแคปอัตโนมัติอยู่"
            return
        }
        val w = reader.windows(c)
        if (w.grabVisible) lastGrabSeenAt = SystemClock.uptimeMillis() else where = "Grab ไม่ได้เปิดอยู่บนจอ"
        updateAwake(w.grabVisible)
        eodTick()
        guard(w)
        if (w.grabVisible && SystemClock.uptimeMillis() - lastObserveAt > IDLE_OBSERVE_MS) scheduleObserve()
        if (w.grabVisible && c.autoScroll && sweepDue()) scheduleObserve()
    }

    private suspend fun guard(w: WindowsState) {
        val c = cfg
        if (!c.guardReadyTab || eod == Eod.RUNNING) return
        val now = SystemClock.uptimeMillis()
        val idleMs = c.guardIdleMinutes * 60_000L
        if (!w.grabVisible) {
            if (w.ownAppVisible) {
                lastGrabSeenAt = now
                return
            }
            if (!screenUsable()) return
            if (now - lastGrabSeenAt > idleMs && now - lastLaunchAt > LAUNCH_GAP_MS) {
                lastLaunchAt = now
                Diagnostics.note(service, "guard: Grab not on screen, opening it")
                actor.launch(c.targetPackages.first())
            }
            return
        }
        if (now - lastUserTouchAt < idleMs) return
        if (!lane.tryLock()) return
        try {
            val s = reader.read(c) ?: return
            if (isReady(s)) return
            if (s.dialog) {
                problem("Grab มีหน้าต่างเด้งค้างอยู่ — กรุณาปิดเอง แล้วเปิดแท็บ Ready")
                return
            }
            Diagnostics.note(service, "guard: back to the Ready tab from ${s.a.tab}")
            if (!openTab(c.readyTabLabels, OrderTab.READY)) problem("พากลับแท็บ Ready ไม่สำเร็จ — กรุณาเปิด Grab ที่แท็บ Ready เอง")
        } finally {
            lane.unlock()
        }
    }

    private fun screenUsable(): Boolean {
        val pm = service.getSystemService(PowerManager::class.java)
        val km = service.getSystemService(KeyguardManager::class.java)
        return (pm?.isInteractive ?: false) && !(km?.isKeyguardLocked ?: true)
    }

    private fun problem(text: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProblemAt < PROBLEM_NOTE_GAP_MS && lastProblemAt != 0L) return
        lastProblemAt = now
        Diagnostics.note(service, "problem: $text")
        Notifier.status(service, "ReadyProof ต้องการความช่วยเหลือ", text, Notifier.ID_PROBLEM)
    }

    // ---- end of day -----------------------------------------------------------------------------

    /** The button "สรุปสิ้นวันตอนนี้": reads History now without waiting for the Ready tab to empty. */
    fun runEndOfDayNow() {
        scope.launch {
            eod = Eod.WAITING
            eodForced = true
            eodStartedAt = System.currentTimeMillis()
            eodNextAt = 0
            eodStep()
        }
    }

    private suspend fun eodTick() {
        val c = cfg
        val today = LocalDate.now()
        val doneDay = ConfigStore.prefs(service).getString(EOD_DONE_KEY, null)
        if (eod == Eod.DONE && doneDay != today.toString()) {
            eod = Eod.IDLE
            eodStatus = null
        }
        if (eod == Eod.IDLE) {
            val at = c.endOfDayAt() ?: return
            if (!c.autoEndOfDay || doneDay == today.toString() || LocalTime.now().isBefore(at)) return
            eod = Eod.WAITING
            eodForced = false
            eodStartedAt = System.currentTimeMillis()
            eodNextAt = 0
            Diagnostics.note(service, "end of day: started")
        }
        if (eod == Eod.WAITING && System.currentTimeMillis() >= eodNextAt) eodStep()
    }

    private suspend fun eodStep() {
        val c = cfg
        if (!lane.tryLock()) {
            eodNextAt = System.currentTimeMillis() + 15_000L
            return
        }
        eod = Eod.RUNNING
        try {
            val retryAt = System.currentTimeMillis() + c.recheckMinutes * 60_000L
            if (!reader.windows(c).grabVisible) {
                if (screenUsable()) {
                    actor.launch(c.targetPackages.first())
                    delay(4_000)
                }
                if (!reader.windows(c).grabVisible) {
                    later(retryAt, "เปิด Grab ไม่ได้ (จอดับหรือล็อกอยู่?)")
                    return
                }
            }
            val waited = System.currentTimeMillis() - eodStartedAt
            val giveUp = waited >= c.endMaxWaitMinutes * 60_000L
            if (!eodForced) {
                if (openTab(c.readyTabLabels, OrderTab.READY)) {
                    sweepReady()
                    val left = tracker.present()
                    if (left.isNotEmpty() && !giveUp) {
                        later(retryAt, "ยังมี ${left.size} ออเดอร์รอไรเดอร์ในแท็บ Ready (${left.take(5).joinToString(", ")})")
                        return
                    }
                } else if (!giveUp) {
                    later(retryAt, "เปิดแท็บ Ready ไม่สำเร็จ")
                    return
                }
            }
            eodStatus = "สิ้นวัน: กำลังอ่านหน้าประวัติทั้งหมด…"
            val result = sweepHistory()
            if (result == null) {
                later(retryAt, "เปิดหน้าประวัติไม่สำเร็จ")
                return
            }
            openTab(c.readyTabLabels, OrderTab.READY)
            finishDay(result)
        } catch (e: Exception) {
            Diagnostics.error(service, "eod", e)
            later(System.currentTimeMillis() + c.recheckMinutes * 60_000L, "เกิดข้อผิดพลาด จะลองใหม่")
        } finally {
            if (eod == Eod.RUNNING) eod = Eod.WAITING
            lane.unlock()
        }
    }

    private fun later(at: Long, why: String) {
        eod = Eod.WAITING
        eodNextAt = at
        eodStatus = "สิ้นวัน: $why · ตรวจใหม่ ${ReportText.time(at, zone)}"
        Diagnostics.note(service, "end of day: $why")
        Notifier.status(service, "ReadyProof: รอสรุปสิ้นวัน", "$why\nจะตรวจใหม่เวลา ${ReportText.time(at, zone)}")
    }

    private suspend fun finishDay(result: HistoryResult) {
        val date = LocalDate.parse(result.day)
        val report = withContext(Dispatchers.IO) {
            ReportBuilder.build(RecordStore.loadRange(service, date.minusDays(1), date.plusDays(1)), date, zone, cfg)
        }
        // A run started by hand before closing time does not replace tonight's automatic run.
        val afterClose = cfg.endOfDayAt()?.let { !LocalTime.now().isBefore(it) } ?: true
        if (!eodForced || afterClose) {
            ConfigStore.prefs(service).edit().putString(EOD_DONE_KEY, LocalDate.now().toString()).apply()
            eod = Eod.DONE
        } else {
            eod = Eod.IDLE
        }
        eodForced = false
        val count = if (report.grabCompleted != null) "${report.historyRows}/${report.grabCompleted}" else "${report.historyRows}"
        val text = "อ่านประวัติได้ $count ออเดอร์ · ล่าช้าตาม Grab ${report.delayed} (${ReportText.pct(report.grabPct)})\n" +
            "กดทัน ${report.inTime.size} · ช้าจริง ${report.late.size} · ไม่มีหลักฐาน ${report.noEvidence.size}\n" +
            "% ที่ร้านควรได้ ${ReportText.pct(report.realPct)}" +
            (if (result.unshot.isNotEmpty()) "\n⚠ แคปหน้าประวัติไม่ได้: ${result.unshot.joinToString(", ")}" else "")
        eodStatus = "สรุปวันที่ ${ReportText.date(date)} เสร็จแล้ว ${ReportText.time(System.currentTimeMillis(), zone)} · " +
            "ล่าช้าตาม Grab ${ReportText.pct(report.grabPct)} → ที่ควรได้ ${ReportText.pct(report.realPct)}"
        Notifier.cancel(service, Notifier.ID_STATUS)
        Notifier.report(service, "สรุปสิ้นวัน ${ReportText.date(date)} พร้อมแล้ว", text)
        Diagnostics.note(service, "end of day: done ${report.historyRows} rows, ${report.delayed} delayed, in time ${report.inTime.size}")
        if (eod == Eod.DONE) updateAwake(false)
    }

    // ---- keep the screen on while watching --------------------------------------------------------

    private fun updateAwake(grabVisible: Boolean) {
        val c = cfg
        val want = c.enabled && c.keepScreenOn && grabVisible && eod != Eod.DONE
        if (want && awake == null) addAwake() else if (!want && awake != null) removeAwake()
    }

    private fun addAwake() {
        try {
            val wm = service.getSystemService(WindowManager::class.java) ?: return
            val v = View(service)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp)
            awake = v
        } catch (e: Exception) {
            Diagnostics.error(service, "awake", e)
        }
    }

    private fun removeAwake() {
        val v = awake ?: return
        awake = null
        try {
            service.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (e: Exception) {
            Diagnostics.error(service, "awake", e)
        }
    }

    // ---- for the home screen --------------------------------------------------------------------

    fun statusLines(): List<String> {
        val out = ArrayList<String>()
        out += where
        if (lastSweepAt > 0) out += "กวาดรายการ Ready ล่าสุด " + ReportText.time(lastSweepAt, zone)
        val unshot = tracker.pending()
        if (unshot.isNotEmpty()) out += "⚠ ยังแคปไม่ได้: " + unshot.joinToString(", ")
        eodStatus?.let { out += it }
        return out
    }

    /** Manual shot (accessibility button / test button): whatever is on screen, no checks. */
    fun manualCapture(note: String?) {
        scope.launch {
            val c = cfg
            val s = try {
                reader.read(c)
            } catch (e: Exception) {
                null
            }
            if (s != null && c.diagnostics) Diagnostics.dump(service, "MANUAL", s.roots, force = true)
            val t = System.currentTimeMillis()
            val bitmap = capture.take()
            if (bitmap == null) {
                service.toast("⚠️ ${capture.lastError ?: "แคปไม่สำเร็จ"}")
                return@launch
            }
            val items = s?.let { io.github.panuwattegif.readyproof.core.ScreenAnalyzer.manualItems(it.a, c) } ?: emptyList()
            val record = capture.save(bitmap, RecordKind.MANUAL, t, CaptureMeta(items = items, visible = s?.a?.visible ?: emptyList(), note = note))
            if (record != null) {
                if (items.any { it.type == ObsType.READY }) tracker.shot(items.filter { it.type == ObsType.READY }.map { it.gf }, t)
                lastShotText = record.kind.label + " " + record.gfs.joinToString(", ").ifEmpty { "-" } + " · " + ReportText.time(t, zone)
                service.toast("📸 แคปแล้ว " + record.gfs.take(3).joinToString(", "))
            }
        }
    }
}
