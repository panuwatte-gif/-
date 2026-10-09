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
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.Deduper
import io.github.panuwattegif.readyproof.core.HistoryDates
import io.github.panuwattegif.readyproof.core.HistoryHeader
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.OrderTab
import io.github.panuwattegif.readyproof.core.ReadyTracker
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.ScreenAnalysis
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.TabDetector
import io.github.panuwattegif.readyproof.core.TextNorm
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
 * Runs the dedicated Ready-tab phone (orders are accepted and marked ready on the shop's own
 * device; this phone only watches):
 *  1. Ready tab: every order listed there gets one verified screenshot per stay; the list is
 *     swept top to bottom by itself when it is longer than the screen.
 *  2. Guard: during opening hours, brings Grab back to the Ready tab when it ends up elsewhere.
 *  3. End of day: after closing time + buffer, waits until Ready and Preparing are empty, reads
 *     the whole History list (Grab's totals, every row, a photo of every delayed row), saves the
 *     report/manifest (which releases the Drive batch), notifies and switches itself off for the
 *     night (banking apps refuse to run next to an accessibility service).
 * Lists are moved with the scroll command or a finger drag, and "the end" is where a move no
 * longer changes anything: Grab does not reliably say whether its lists can scroll.
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
        private const val BOX_TOLERANCE = 3
        private const val PERSON_GRACE_MS = 20_000L
        private const val SCROLL_BLOCK_MS = 30 * 60_000L
        private const val MAX_HISTORY_ATTEMPTS = 3
        /** An order without a fully checked photo this long gets a backup photo (number checked only). */
        private const val BACKUP_AFTER_MS = 30_000L
        /** An order without any photo this long raises a warning notification. */
        private const val WARN_AFTER_MS = 2 * 60_000L
        /** A burst of new Ready orders is over when no new one came for this long. */
        private const val BATCH_QUIET_MS = 4_000L
        /** Time for the nightly Drive batch to be staged before the watcher switches itself off. */
        private const val SELF_OFF_DELAY_MS = 60_000L
        /** One move of a list: this share of its height (the slow pass uses smaller steps). */
        private const val PAGE = 0.6f
        /** Minutes after [Config.endMaxWaitMinutes] when the day is closed even without History. */
        private const val HARD_STOP_EXTRA_MIN = 60
        private const val SLOW_PAGE = 0.4f

        // Shared with the home screen and the troubleshooting export.
        const val PREF_EOD_DONE = "eod_done_day"
        const val PREF_MONITOR_STATUS = "monitor_status"
        const val PREF_MONITOR_POLL = "monitor_last_poll"
        const val PREF_CLOSING_STATUS = "closing_history_status"
        const val PREF_LAST_RESULT = "auto_history_last_result"
        const val PREF_AUTO_NAVIGATION = "auto_navigation_enabled"
        /** Day the watcher switched itself off after the report (read by the service watch). */
        const val PREF_SELF_OFF_DAY = "self_off_day"
        private const val PREF_NAV_RESET = "auto_navigation_reset_v5"

        /** Words meaning the page is still loading or failed: never read as "no orders". */
        private val NOT_READY_WORDS = listOf(
            "loading", "กำลังโหลด", "try again", "something went wrong", "ลองใหม่", "เกิดข้อผิดพลาด",
        )
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
    private val prefs get() = ConfigStore.prefs(service)

    private fun shop(): String? = ShopStore.get(service)?.id

    /** Settings switch "เปิดหน้า Ready / History อัตโนมัติ": off = only photograph what is shown. */
    private fun autoNavigation(): Boolean = prefs.getBoolean(PREF_AUTO_NAVIGATION, true)

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
    private val problemAt = HashMap<String, Long>()
    private var scrollBlockedUntil = 0L
    private var fingerprint: String? = null
    private var historyDay: String? = null
    private var lastStats: String? = null
    private var awake: View? = null
    /** What a non-Ready tab showed last time: when it changes by itself, a person is scrolling it. */
    private var foreignKey: String? = null
    private var holdJob: Job? = null
    /** Tabs already saved as a reference screen dump today ("2026-10-09|HISTORY"). */
    private val dumpedTabs = HashSet<String>()
    // how lists actually moved (for the daily diagnostics)
    private var scrollMoves = 0
    private var dragMoves = 0
    private var stuckMoves = 0
    /** The last [move] could not even try (gesture refused, screen unreadable): not the end of a list. */
    private var moveFailed = false
    /**
     * The tab we opened by tapping it, for Grab builds that mark no tab as selected (the shop's
     * phone). Trusted until a person uses the phone or the screen shows another tab.
     */
    private var assumedTab: OrderTab? = null
    private var assumedAt = 0L

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
    private var historyAttempts = 0

    @Volatile
    var eodStatus: String? = null
        private set

    private val busy: Boolean get() = busyDepth > 0

    fun start() {
        scope.launch {
            try {
                seed()
                DriveSync.recover(service)
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

    /** Remembers today's shots after a restart so waiting orders are not photographed again. */
    private suspend fun seed() {
        val today = LocalDate.now()
        val shopId = shop()
        val recent = withContext(Dispatchers.IO) {
            RecordStore.loadRange(service, today.minusDays(1), today).filter { it.shopId == shopId }
        }
        tracker.clear()
        tracker.seed(recent, System.currentTimeMillis())
        historySeen.clear()
        historySeen.seed(recent)
        if (prefs.getString(PREF_EOD_DONE, null) == today.toString()) eod = Eod.DONE
        // Older apps used the same switch with another meaning; this version needs it on once.
        if (!prefs.getBoolean(PREF_NAV_RESET, false)) {
            prefs.edit().putBoolean(PREF_AUTO_NAVIGATION, true).putBoolean(PREF_NAV_RESET, true).apply()
        }
        Notifier.cancel(service, Notifier.ID_OFF)
    }

    fun stop() {
        scope.cancel()
        removeAwake()
        capture.shutdown()
    }

    /** The phone was bound to a shop: proof taken before belongs to no shop, start fresh. */
    fun onShopBound() {
        scope.launch {
            seed()
            fingerprint = null
            prefs.edit().remove(PREF_EOD_DONE).apply()
            if (eod == Eod.DONE) eod = Eod.IDLE
        }
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
            referenceDump(s)
            noticePerson(s)
            when {
                isReady(s) -> onReady(s)
                s.a.tab == OrderTab.HISTORY -> onHistory(s)
                else -> {
                    historyDay = null
                    setWhere("Grab เปิดอยู่ แต่ไม่ใช่แท็บ Ready")
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
        s.a.tab == OrderTab.READY ||
            (s.a.tab == null && tabOf(s).let { it == null || it == OrderTab.READY } && s.a.readyGfs().isNotEmpty())

    /** The open tab: what Grab reports or shows, else the tab we tapped ourselves (see [assumedTab]). */
    private fun tabOf(s: Screen): OrderTab? {
        s.a.tab?.let { return it }
        val t = assumedTab ?: return null
        return if (s.a.tabsVisible && lastUserTouchAt <= assumedAt) t else null
    }

    private fun stillHistory(s: Screen): Boolean =
        s.a.tab == OrderTab.HISTORY || (s.a.tab == null && s.a.historyViews().isNotEmpty())

    /** The first screen of each tab every day goes into the diagnostics sent with the night batch. */
    private fun referenceDump(s: Screen) {
        val key = LocalDate.now().toString() + "|" + s.a.tab
        if (key in dumpedTabs) return
        if (Diagnostics.dump(service, "DAILY ${s.a.tab}", s.roots, force = true)) dumpedTabs += key
    }

    /**
     * Grab does not always report a person scrolling, so on any tab but Ready a list that moved
     * by itself (not by us) counts as somebody using the phone: the guard leaves them alone.
     */
    private fun noticePerson(s: Screen) {
        if (isReady(s)) {
            foreignKey = null
            return
        }
        val key = s.a.motionKey()
        val now = SystemClock.uptimeMillis()
        if (foreignKey != null && key != foreignKey && now - lastBusyEndAt > 2_000L) lastUserTouchAt = now
        foreignKey = key
    }

    private fun setWhere(text: String) {
        where = text
        val now = System.currentTimeMillis()
        if (prefs.getString(PREF_MONITOR_STATUS, null) != text || now - prefs.getLong(PREF_MONITOR_POLL, 0) > 60_000L) {
            prefs.edit().putString(PREF_MONITOR_STATUS, text).putLong(PREF_MONITOR_POLL, now).apply()
        }
    }

    // ---- Ready tab ------------------------------------------------------------------------------

    private suspend fun onReady(first: Screen) {
        val c = cfg
        val now = System.currentTimeMillis()
        var s = first
        val listed = s.a.readyGfs()
        noteSeen(s, now)
        // The whole list is on screen: whatever is not listed has been picked up.
        if (!mayOverflow(s)) {
            tracker.complete(listed, now)
            lastSweepAt = now
        }
        if (s.dialog && tracker.pending().isNotEmpty()) problem("Grab มีหน้าต่างเด้งบังรายการ Ready อยู่ — แคปไม่ได้จนกว่าจะปิด")
        // More new orders may be arriving right behind these: one photo for the whole burst.
        val hold = tracker.batchHold(
            s.a.readyViews().filter { it.full }.map { it.gf }, now, BATCH_QUIET_MS, c.readyBatchSeconds * 1000L,
        )
        if (hold > 0) {
            observeIn(hold)
            setWhere("เฝ้าแท็บ Ready · รอรวบออเดอร์ใหม่ที่เข้ามาติดกันไว้ในภาพเดียว")
            return
        }
        shootReady(s)?.let { s = it }
        backupReady(s)?.let { s = it }
        warnUnphotographed()
        val fp = fingerprintOf(s.a)
        val changed = fp != fingerprint
        fingerprint = fp
        val pending = retryable()
        val up = SystemClock.uptimeMillis()
        val sweepForPending = pending.isNotEmpty() && up - lastPendingSweepAt > PENDING_SWEEP_GAP_MS
        val personScrolling = up - lastUserTouchAt < PERSON_GRACE_MS
        if (c.autoScroll && autoNavigation() && mayOverflow(s) && !personScrolling && up > scrollBlockedUntil &&
            (changed || sweepForPending || sweepDue())
        ) {
            if (sweepForPending) lastPendingSweepAt = up
            sweepReady()
        } else if (autoNavigation() && s.a.readyViews().any { !it.full && it.gf in pending }) {
            automate { fixPartials(s) }
        }
        setWhere("เฝ้าแท็บ Ready · รอไรเดอร์ ${tracker.present().size} ออเดอร์")
    }

    /**
     * Orders listed in the Ready tab, written down as soon as they are seen (before any shot), so
     * the report can tell "seen but no photo yet" apart from "never reached the Ready tab".
     */
    private fun noteSeen(s: Screen, now: Long) {
        val views = s.a.readyViews()
        val fresh = views.filter { tracker.stay(it.gf) == null }
        tracker.seen(views.map { it.gf }, now)
        if (fresh.isEmpty()) return
        RecordStore.append(
            service,
            Record(
                id = "$now-rs${seq.incrementAndGet()}", t = now, kind = RecordKind.SEEN,
                items = fresh.flatMap { v -> v.items.filter { it.type == ObsType.READY } },
                visible = s.a.visible, note = "READY_SEEN_PENDING_UNTIL_IMAGE", shopId = shop(),
            ),
        )
    }

    /** Orders still without a good shot that are worth another try. */
    private fun retryable(): Set<String> =
        tracker.pending().filter { (tracker.stay(it)?.failures ?: 0) < MAX_FAILURES }.toSet()

    private fun sweepDue(): Boolean = System.currentTimeMillis() - lastSweepAt > cfg.fullSweepMinutes * 60_000L

    /**
     * The Ready list may go on beyond the screen: Grab says so, an order is cut off at an edge, or
     * the last order reaches the bottom of the list. Without a list element on screen, three or
     * more orders are enough to check (a sweep that finds nothing to move costs two drags).
     */
    private fun mayOverflow(s: Screen): Boolean {
        if (s.a.canScroll) return true
        val views = s.a.readyViews()
        if (views.isEmpty()) return false
        if (views.any { !it.full }) return true
        val list = s.a.scroller?.box()?.takeIf { !it.isEmpty } ?: return views.size >= 3
        val boxes = views.map { it.card.node.box() }.filter { !it.isEmpty }
        if (boxes.isEmpty()) return true
        val tallest = boxes.maxOf { it.height }
        return boxes.maxOf { it.bottom } >= list.bottom - tallest / 2
    }

    /** Looks again after [ms] (a burst of new orders is still coming in). */
    private fun observeIn(ms: Long) {
        if (holdJob?.isActive == true) return
        holdJob = scope.launch {
            delay(ms + 100)
            scheduleObserve()
        }
    }

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
     * Safety net: an order that could not get a fully checked photo within [BACKUP_AFTER_MS] gets
     * a backup photo as soon as at least its order number is completely inside the list and not
     * covered by another window. Better a photo with the number than no evidence at all; the
     * app keeps trying for a fully checked one.
     */
    private suspend fun backupReady(s: Screen): Screen? {
        if (!cfg.captureReady) return null
        val t = System.currentTimeMillis()
        val due = tracker.needBackup(t, BACKUP_AFTER_MS).toSet()
        if (due.isEmpty()) return null
        val targets = s.a.readyViews().filter { it.gf in due && it.gfClear }
        if (targets.isEmpty()) return null
        val (ok, after) = shoot(
            RecordKind.READY, s, targets, null, note = ReadyTracker.BACKUP_NOTE,
            stillGood = { b, a -> a.gfClear && sameBoxes(listOf(a.card.gfNode.box()), listOf(b.card.gfNode.box())) },
        ) { v -> v.items.filter { it.type == ObsType.READY } }
        tracker.backup(ok.map { it.gf }, t)
        if (ok.isNotEmpty()) Diagnostics.note(service, "backup Ready photo ${ok.joinToString { it.gf }}")
        return after
    }

    /** Warns (notification) about any order that has sat in the Ready tab without a photo. */
    private fun warnUnphotographed() {
        val missing = tracker.unphotographed(System.currentTimeMillis(), WARN_AFTER_MS)
        if (missing.isEmpty()) return
        problem(
            "ออเดอร์ ${missing.joinToString(", ")} อยู่ในแท็บ Ready เกิน 2 นาทีแต่ยังแคปไม่ได้ — ดูหน้าจอมือถือเครื่องเฝ้า หรือแคปเองด้วยปุ่มลอย",
            kind = "unphotographed:" + missing.joinToString(","),
        )
    }

    /**
     * Takes one screenshot for [targets] and keeps it only for the orders whose number and status
     * line were still in the same place right after the shot. Returns the orders proven by the
     * saved shot and the screen as read after it.
     */
    private suspend fun shoot(
        kind: RecordKind,
        before: Screen,
        targets: List<CardView>,
        day: String?,
        note: String? = null,
        stillGood: (before: CardView, after: CardView) -> Boolean = { b, a -> a.full && sameBoxes(a.keyBoxes, b.keyBoxes) },
        itemsOf: (CardView) -> List<Item>,
    ): Pair<List<CardView>, Screen?> {
        val t = System.currentTimeMillis()
        val bitmap = capture.take()
        if (bitmap == null) {
            problem("แคปหน้าจอไม่สำเร็จ: ${capture.lastError ?: "-"}", kind = "capture")
            return emptyList<CardView>() to null
        }
        val after = reader.read(cfg)
        val ok = if (after == null) {
            emptyList()
        } else {
            targets.filter { tv -> after.a.views.any { v -> v.gf == tv.gf && stillGood(tv, v) } }
        }
        if (ok.isEmpty()) {
            bitmap.recycle()
            Diagnostics.note(service, "discarded $kind shot: screen moved (${targets.joinToString { it.gf }})")
            return emptyList<CardView>() to after
        }
        val items = ok.flatMap(itemsOf).map { if (day != null) it.copy(historyDate = day) else it }
        val record = capture.save(
            bitmap, kind, t,
            CaptureMeta(items = items, visible = before.a.visible, note = note, historyDate = day, shopId = shop()),
        ) ?: return emptyList<CardView>() to after
        lastShotText = record.kind.label + " " + record.gfs.joinToString(", ") + " · " + ReportText.time(record.t, zone)
        ProofService.lastCaptureText = lastShotText
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
        while (pages++ < MAX_PAGES) {
            s = move(s, forward = false) ?: break
            if (!isReady(s)) {
                scrolledAway()
                return@automate
            }
        }
        val seen = LinkedHashSet<String>()
        var reachedBottom = false
        pages = 0
        while (true) {
            seen += s.a.readyGfs()
            noteSeen(s, System.currentTimeMillis())
            shootReady(s)?.let { s = it }
            fixPartials(s)?.let { s = it }
            backupReady(s)?.let { s = it }
            if (!isReady(s)) return@automate
            seen += s.a.readyGfs()
            if (pages++ >= MAX_PAGES) break
            val next = move(s, forward = true)
            if (next == null) {
                reachedBottom = !moveFailed
                if (pages == 1 && s.a.readyViews().any { !it.full }) {
                    Diagnostics.dump(service, "READY list does not move", s.roots, force = true)
                }
                break
            }
            s = next
            if (!isReady(s)) {
                scrolledAway()
                return@automate
            }
        }
        val now = System.currentTimeMillis()
        // Only a sweep that reached the bottom knows the whole list: then missing orders have left.
        // An empty list must stay empty on a second look (a list reloading after a pull is empty too).
        if (reachedBottom && (seen.isNotEmpty() || queueEmpty(OrderTab.READY) == true)) tracker.complete(seen, now)
        lastSweepAt = now
        fingerprint = fingerprintOf(s.a)
        Diagnostics.note(
            service,
            "sweep Ready: ${seen.size} orders, $pages pages, bottom=$reachedBottom, ${now - started} ms, " +
                "waiting=${tracker.present().size}, unshot=${tracker.pending()}, moves scroll/drag/none=$scrollMoves/$dragMoves/$stuckMoves",
        )
    }

    /**
     * Moves the list by about [page] of its height (forward = towards the bottom) with the scroll
     * command, else with a finger drag. Returns the new screen if anything moved, null if nothing
     * did (the end of the list, or a list that cannot move).
     */
    private suspend fun move(s: Screen, forward: Boolean, page: Float = PAGE): Screen? {
        moveFailed = false
        val before = s.a.motionKey()
        if (before.isEmpty()) return null
        if (s.a.scroller != null && actor.scroll(s.a.scroller, forward)) {
            val after = settle()
            if (after == null) {
                moveFailed = true
                return null
            }
            if (after.a.motionKey() != before) {
                scrollMoves++
                return after
            }
        }
        val vp = viewportOf(s) ?: return null
        val dy = vp.height * page
        val x = vp.left + vp.width * 0.3f
        val dragged = if (forward) {
            actor.drag(x, vp.bottom - vp.height * 0.15f, -dy)
        } else {
            actor.drag(x, vp.top + vp.height * 0.15f, dy)
        }
        if (!dragged) {
            moveFailed = true
            stuckMoves++
            return null
        }
        val after = settle()
        if (after == null) {
            moveFailed = true
            return null
        }
        if (after.a.motionKey() == before) {
            stuckMoves++
            return null
        }
        dragMoves++
        return after
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

    /**
     * Where the order list is on screen: the list element if Grab reports one, otherwise from the
     * first order card down to near the bottom of the screen (above the bottom bar).
     */
    private fun viewportOf(s: Screen): Box? {
        s.a.scroller?.box()?.takeIf { !it.isEmpty }?.let { return it }
        val cards = s.a.views.map { it.card.node.box() }.filter { !it.isEmpty }
        if (cards.isEmpty()) return null
        val dm = service.resources.displayMetrics
        val top = cards.minOf { it.top }.coerceAtLeast(0)
        val bottom = maxOf(cards.maxOf { it.bottom }, (dm.heightPixels * 0.85f).toInt()).coerceAtMost(dm.heightPixels)
        return if (bottom - top < dm.heightPixels / 5) null else Box(0, top, dm.widthPixels, bottom)
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
        setWhere("Grab เปิดอยู่ที่แท็บประวัติ")
    }

    private fun saveStats(h: HistoryHeader) {
        val day = h.date ?: return
        if (h.completed == null) return
        val key = "${shop()}|$day|${h.completed}|${h.cancelled}"
        if (key == lastStats) return
        lastStats = key
        val now = System.currentTimeMillis()
        RecordStore.append(
            service,
            Record(
                "$now-t${seq.incrementAndGet()}", now, RecordKind.STATS, shopId = shop(),
                historyDate = day.toString(), completed = h.completed, cancelled = h.cancelled,
            ),
        )
    }

    /** History rows on screen with their day: from a date header above them, else [day]. */
    private fun rowsOf(s: Screen, day: String?): List<Item> {
        val raw = s.a.historyViews().flatMap { v ->
            v.items.filter { (it.type == ObsType.DONE || it.type == ObsType.CANCELLED || it.type == ObsType.DELAY) && it.doneAt != null }
        }
        val fallback = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val (dated, _) = HistoryDates.assign(raw, s.a, s.roots, LocalDate.now(), fallback)
        return dated
    }

    /** Finished / cancelled orders on screen, written down once each (no screenshot). */
    private fun recordRows(s: Screen, day: String?): List<Item> {
        val rows = rowsOf(s, day)
        val terminal = rows.filter { it.type == ObsType.DONE || it.type == ObsType.CANCELLED }
        val now = System.currentTimeMillis()
        val fresh = historySeen.fresh(terminal, now, cfg)
        if (fresh.isNotEmpty()) {
            historySeen.mark(fresh.mapNotNull { Deduper.keyOf(it) }, now)
            RecordStore.append(
                service,
                Record("$now-s${seq.incrementAndGet()}", now, RecordKind.SEEN, items = fresh, visible = s.a.visible, shopId = shop(), historyDate = day),
            )
        }
        return rows
    }

    private fun delayKey(v: CardView, day: String?): String? =
        v.items.firstOrNull { it.type == ObsType.DELAY }?.let { Deduper.keyOf(if (day != null) it.copy(historyDate = day) else it) }

    /** Photographs delayed rows that are fully on screen and not photographed yet. */
    private suspend fun shootDelays(s: Screen, day: String?): Screen? {
        val now = System.currentTimeMillis()
        val targets = delayPending(s, day).filter { it.full }
        if (targets.isEmpty()) return null
        val (ok, after) = shoot(RecordKind.DELAY, s, targets, day) { v -> v.items.filter { it.type == ObsType.DELAY } }
        historySeen.mark(ok.mapNotNull { delayKey(it, day) }, now)
        return after
    }

    /** Delayed rows on screen still without a photo (only rows whose finish time is readable). */
    private fun delayPending(s: Screen, day: String?): List<CardView> {
        val now = System.currentTimeMillis()
        return s.a.historyViews().filter { v ->
            v.items.any { it.type == ObsType.DELAY && it.doneAt != null } &&
                delayKey(v, day)?.let { historySeen.isFresh(it, now, 36L * 3600_000) } == true
        }
    }

    private class HistoryResult(
        val day: String,
        val header: HistoryHeader?,
        val rows: Int,
        val reachedEnd: Boolean,
        val unshot: List<String>,
    )

    /** Reads the whole History list of the day shown, photographing every delayed order. */
    private suspend fun sweepHistory(): HistoryResult? = automate {
        val c = cfg
        if (!openTab(c.historyTabLabels, OrderTab.HISTORY)) return@automate null
        var s = settle() ?: return@automate null
        var pages = 0
        while (pages++ < MAX_HISTORY_PAGES) {
            s = move(s, forward = false) ?: break
        }
        var header = s.a.header
        if (header?.completed == null) {
            // the list may still be loading
            delay(2_000)
            s = settle() ?: return@automate null
            header = s.a.header ?: header
        }
        Diagnostics.dump(service, "HISTORY top", s.roots, force = true)
        val dayDate = header?.date ?: LocalDate.now()
        val day = dayDate.toString()
        historyDay = day
        header?.let { saveStats(it) }
        val rows = LinkedHashMap<String, Item>()
        // Every delayed row of the day met during the sweep (key -> order number), shot or not.
        val delayedRows = LinkedHashMap<String, String>()
        // Every order number seen on the way down (also rows whose time could not be read).
        val passed = HashSet<String>()
        var reachedEnd = false
        val movesBefore = Triple(scrollMoves, dragMoves, stuckMoves)

        // One pass down the list, [page] of its height per move. Stops where the list no longer
        // moves (the end), at an older day's rows, or when nothing new appears for three moves.
        suspend fun pass(page: Float) {
            pages = 0
            var still = 0
            reachedEnd = false
            while (true) {
                val before = rows.size + passed.size
                val onPage = recordRows(s, day)
                passed += s.a.historyViews().map { it.gf }
                onPage.filter { it.historyDate == day && it.type != ObsType.DELAY }.forEach { rows[Deduper.keyOf(it)!!] = it }
                onPage.filter { it.historyDate == day && it.type == ObsType.DELAY }
                    .forEach { d -> Deduper.keyOf(d.copy(historyDate = day))?.let { delayedRows[it] = d.gf } }
                if (onPage.any { row -> row.historyDate?.let { it < day } == true }) {
                    reachedEnd = true
                    break
                }
                shootDelays(s, day)?.let { s = it }
                // delayed rows cut off at an edge
                for (v in delayPending(s, day).filter { !it.full }) {
                    val shown = bringIntoView(s, v) ?: continue
                    s = shown
                    shootDelays(s, day)?.let { s = it }
                }
                still = if (rows.size + passed.size == before) still + 1 else 0
                if (pages++ >= MAX_HISTORY_PAGES || still >= 3) break
                var next = move(s, forward = true, page = page)
                if (next == null) {
                    // more rows may still be loading at the end of the list
                    delay(1_500)
                    s = settle() ?: return
                    next = move(s, forward = true, page = page)
                    if (next == null) {
                        reachedEnd = !moveFailed
                        if (pages == 1) Diagnostics.dump(service, "HISTORY list does not move", s.roots, force = true)
                        break
                    }
                }
                s = next
                if (!stillHistory(s)) return
            }
        }

        pass(PAGE)
        val want = header?.completed?.let { it + (header.cancelled ?: 0) }
        if (want != null && rows.size < want) {
            // Second, slower pass with smaller steps for rows a fast move may have skipped.
            Diagnostics.note(service, "history: ${rows.size}/$want rows after first pass, second pass")
            Diagnostics.dump(service, "HISTORY short ${rows.size}/$want", s.roots, force = true)
            pages = 0
            while (pages++ < MAX_HISTORY_PAGES) {
                s = move(s, forward = false) ?: break
            }
            pass(SLOW_PAGE)
        }
        // back to the top for whoever looks next
        pages = 0
        while (pages++ < MAX_HISTORY_PAGES) {
            s = move(s, forward = false) ?: break
        }
        val checkedAt = System.currentTimeMillis()
        val unshot = delayedRows.filterKeys { historySeen.isFresh(it, checkedAt, 36L * 3600_000) }.values.toList()
        Diagnostics.note(
            service,
            "history $day: rows=${rows.size}/${want ?: "?"} passed=${passed.size} end=$reachedEnd delayed=${delayedRows.size} unshot=$unshot " +
                "moves scroll/drag/none=${scrollMoves - movesBefore.first}/${dragMoves - movesBefore.second}/${stuckMoves - movesBefore.third}",
        )
        HistoryResult(day, header, rows.size, reachedEnd, unshot)
    }

    // ---- moving around inside Grab ---------------------------------------------------------------

    /** Opens the wanted order tab by tapping the tab bar (or "Orders" in the bottom bar first). */
    private suspend fun openTab(labels: List<String>, want: OrderTab): Boolean = automate {
        val c = cfg
        val gfx = c.gfExtractor()
        repeat(4) {
            val s = reader.read(c) ?: return@automate false
            if (tabOf(s) == want) return@automate true
            if (s.dialog) return@automate false
            val tab = TabDetector.tapTarget(s.roots, labels, c.tabLabels, gfx)
            if (tab != null) {
                val tapped = actor.click(tab)
                val after = settle() ?: return@automate false
                if (after.a.tab == want) {
                    assumedTab = want
                    assumedAt = SystemClock.uptimeMillis()
                    return@automate true
                }
                // Grab marks no tab as selected here: trust the tap, unless the screen plainly shows
                // another tab (History is recognised by its content; it is never just assumed).
                if (tapped && after.a.tab == null && want != OrderTab.HISTORY && after.a.tabsVisible) {
                    assumedTab = want
                    assumedAt = SystemClock.uptimeMillis()
                    Diagnostics.note(service, "tab $want opened by tap (Grab marks no selected tab)")
                    return@automate true
                }
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
        reader.read(c)?.let { tabOf(it) } == want
    }

    /**
     * The open queue tab shows no orders: no order card in its list on two readings 1.5 s apart and
     * nothing saying it is still loading. Null = could not tell (wrong tab, no screen).
     */
    private suspend fun queueEmpty(want: OrderTab): Boolean? {
        repeat(2) { i ->
            if (i > 0) delay(1_500)
            val s = reader.read(cfg) ?: return null
            if (tabOf(s) != want) return null
            if (s.a.views.any { it.card.inList }) return false
            val texts = s.roots.flatMap { r -> r.walk().filter { it.shown }.flatMap { it.ownStrings().asSequence() }.toList() }
            if (texts.any { t -> NOT_READY_WORDS.any { TextNorm.key(t).contains(it) } }) return null
        }
        return true
    }

    // ---- the 10-second heartbeat ----------------------------------------------------------------

    private suspend fun tick() {
        val c = cfg
        if (!c.enabled) {
            updateAwake(false)
            setWhere("ปิดการแคปอัตโนมัติอยู่")
            return
        }
        val w = reader.windows(c)
        if (w.grabVisible) lastGrabSeenAt = SystemClock.uptimeMillis() else setWhere("Grab ไม่ได้เปิดอยู่บนจอ")
        updateAwake(w.grabVisible)
        eodTick()
        guard(w)
        if (w.grabVisible && SystemClock.uptimeMillis() - lastObserveAt > IDLE_OBSERVE_MS) scheduleObserve()
        if (w.grabVisible && c.autoScroll && sweepDue()) scheduleObserve()
    }

    private suspend fun guard(w: WindowsState) {
        val c = cfg
        if (!c.guardReadyTab || !autoNavigation() || eod == Eod.RUNNING || eod == Eod.DONE) return
        // Outside opening hours the phone is the owner's (the closing wait still guards).
        if (eod == Eod.IDLE && !c.inShopHours(LocalTime.now())) return
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
            if (isReady(s) || tabOf(s) == OrderTab.READY) return
            if (s.dialog) {
                problem("Grab มีหน้าต่างเด้งค้างอยู่ — กรุณาปิดเอง แล้วเปิดแท็บ Ready")
                return
            }
            Diagnostics.note(service, "guard: back to the Ready tab from ${s.a.tab}")
            setWhere("กำลังกลับหน้า Ready อัตโนมัติ")
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

    /** Notifies a problem; the same kind of problem at most every 30 minutes. */
    private fun problem(text: String, kind: String = text) {
        val now = SystemClock.uptimeMillis()
        val last = problemAt[kind]
        if (last != null && now - last < PROBLEM_NOTE_GAP_MS) return
        problemAt[kind] = now
        Diagnostics.note(service, "problem: $text")
        Notifier.status(service, "ReadyProof ต้องการความช่วยเหลือ", text, Notifier.ID_PROBLEM)
    }

    // ---- end of day -----------------------------------------------------------------------------

    /**
     * The buttons "สรุปสิ้นวันตอนนี้" / "กวาด History ตอนนี้": reads History now without waiting for
     * the queues to empty. Before closing time it does not replace the evening's automatic run.
     */
    fun runEndOfDayNow() {
        scope.launch {
            eod = Eod.WAITING
            eodForced = true
            historyAttempts = 0
            eodStartedAt = System.currentTimeMillis()
            eodNextAt = 0
            eodStep()
        }
    }

    /** "กลับไปเฝ้า Ready": stop any end-of-day wait started by hand and go back to the Ready tab. */
    fun resumeReadyMonitor() {
        scope.launch {
            if (eodForced && eod == Eod.WAITING) eod = Eod.IDLE
            eodForced = false
            if (!lane.tryLock()) return@launch
            try {
                openTab(cfg.readyTabLabels, OrderTab.READY)
            } finally {
                lane.unlock()
            }
            scheduleObserve()
        }
    }

    private suspend fun eodTick() {
        val c = cfg
        val today = LocalDate.now()
        val doneDay = prefs.getString(PREF_EOD_DONE, null)
        if (eod == Eod.DONE && doneDay != today.toString()) {
            eod = Eod.IDLE
            eodStatus = null
        }
        if (eod == Eod.IDLE) {
            val at = c.endOfDayAt() ?: return
            if (!c.autoEndOfDay || !autoNavigation() || doneDay == today.toString() || LocalTime.now().isBefore(at)) return
            eod = Eod.WAITING
            eodForced = false
            historyAttempts = 0
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
            val waited = System.currentTimeMillis() - eodStartedAt
            if (!eodForced && waited >= (c.endMaxWaitMinutes + HARD_STOP_EXTRA_MIN) * 60_000L) {
                closeDayUnread("อ่านหน้าประวัติไม่สำเร็จภายใน ${c.endMaxWaitMinutes + HARD_STOP_EXTRA_MIN} นาทีหลังเวลาสรุป")
                return
            }
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
            val giveUp = waited >= c.endMaxWaitMinutes * 60_000L
            if (!eodForced && !giveUp) {
                // Ready: sweep it (photographing anything new), then make sure it is really empty.
                if (!openTab(c.readyTabLabels, OrderTab.READY)) {
                    later(retryAt, "เปิดแท็บ Ready ไม่สำเร็จ")
                    return
                }
                sweepReady()
                val emptyNow = queueEmpty(OrderTab.READY) == true
                // Seen empty twice: every order the tracker still remembers has been picked up.
                if (emptyNow) tracker.complete(emptyList(), System.currentTimeMillis())
                val left = tracker.present()
                val readyEmpty = left.isEmpty() && emptyNow
                if (!readyEmpty) {
                    later(retryAt, if (left.isNotEmpty()) {
                        "ยังมี ${left.size} ออเดอร์รอไรเดอร์ในแท็บ Ready (${left.take(5).joinToString(", ")})"
                    } else {
                        "ยังยืนยันไม่ได้ว่าแท็บ Ready ว่าง"
                    })
                    return
                }
                // Preparing: an order still cooking will finish later and must be in the report.
                val preparing = if (openTab(c.preparingTabLabels, OrderTab.PREPARING)) queueEmpty(OrderTab.PREPARING) else null
                openTab(c.readyTabLabels, OrderTab.READY)
                if (preparing != true) {
                    later(retryAt, if (preparing == false) "ยังมีออเดอร์ในแท็บกำลังเตรียม" else "ยังยืนยันไม่ได้ว่าแท็บกำลังเตรียมว่าง")
                    return
                }
            }
            setEodStatus("สิ้นวัน: กำลังอ่านหน้าประวัติทั้งหมด…")
            historyAttempts++
            val result = sweepHistory()
            if (result == null) {
                openTab(c.readyTabLabels, OrderTab.READY)
                later(retryAt, "เปิดหน้าประวัติไม่สำเร็จ")
                return
            }
            openTab(c.readyTabLabels, OrderTab.READY)
            finishDay(result, retryAt)
        } catch (e: Exception) {
            Diagnostics.error(service, "eod", e)
            later(System.currentTimeMillis() + c.recheckMinutes * 60_000L, "เกิดข้อผิดพลาด จะลองใหม่")
        } finally {
            if (eod == Eod.RUNNING) eod = Eod.WAITING
            lane.unlock()
        }
    }

    private fun setEodStatus(text: String) {
        eodStatus = text
        prefs.edit().putString(PREF_CLOSING_STATUS, text).apply()
    }

    private fun later(at: Long, why: String) {
        eod = Eod.WAITING
        eodNextAt = at
        setEodStatus("สิ้นวัน: $why · ตรวจใหม่ ${ReportText.time(at, zone)}")
        Diagnostics.note(service, "end of day: $why")
        Notifier.status(service, "ReadyProof: รอสรุปสิ้นวัน", "$why\nจะตรวจใหม่เวลา ${ReportText.time(at, zone)}")
    }

    private suspend fun finishDay(result: HistoryResult, retryAt: Long) {
        val date = LocalDate.parse(result.day)
        val shopId = shop()
        val failure = if (result.reachedEnd) null else "อ่านไม่ถึงท้ายรายการ"
        // Saves the summary + manifest and, after closing time, releases the Drive batch.
        val report = withContext(Dispatchers.IO) {
            DailyExport.save(service, date, shopId, result.reachedEnd, failure, Diagnostics.daily(service, date))
        }
        val incomplete = report.historyMatchesGrab == false || !result.reachedEnd || result.unshot.isNotEmpty()
        val resultText = resultLine(report, result)
        prefs.edit().putString(PREF_LAST_RESULT, resultText).apply()
        if (incomplete && historyAttempts < MAX_HISTORY_ATTEMPTS) {
            later(retryAt, "อ่านประวัติได้ยังไม่ครบ ($resultText) — จะอ่านใหม่")
            return
        }
        // A run started by hand before closing time does not replace tonight's automatic run.
        val afterClose = cfg.endOfDayAt()?.let { !LocalTime.now().isBefore(it) } ?: true
        val dayDone = !eodForced || afterClose
        if (dayDone) {
            prefs.edit().putString(PREF_EOD_DONE, LocalDate.now().toString()).apply()
            eod = Eod.DONE
        } else {
            eod = Eod.IDLE
        }
        eodForced = false
        val text = "อ่านประวัติได้ " + (report.grabCompleted?.let { "${report.completedSeen}/$it" } ?: "${report.completedSeen}") +
            " ออเดอร์ · ล่าช้าตาม Grab ${report.delayed} (${ReportText.pct(report.grabPct)})\n" +
            "กดทัน ${report.inTime.size} · ช้าจริง ${report.late.size} · ไม่มีหลักฐาน ${report.noEvidence.size}\n" +
            "% ที่ร้านควรได้ ${ReportText.pct(report.realPct)}" +
            (if (incomplete) "\n⚠ ยังไม่ครบ: ดูรายละเอียดในรายงาน" else "")
        setEodStatus(
            "สรุปวันที่ ${ReportText.date(date)} เสร็จ ${ReportText.time(System.currentTimeMillis(), zone)} · " +
                "ล่าช้าตาม Grab ${ReportText.pct(report.grabPct)} → ที่ควรได้ ${ReportText.pct(report.realPct)}",
        )
        Notifier.cancel(service, Notifier.ID_STATUS)
        Notifier.report(service, "สรุปสิ้นวัน ${ReportText.date(date)} พร้อมแล้ว", text)
        Diagnostics.note(service, "end of day: done $resultText")
        if (dayDone && afterClose) switchOffForTheNight()
    }

    /** History could not be read all evening: keep what exists, close the day, free the phone. */
    private suspend fun closeDayUnread(why: String) {
        val today = LocalDate.now()
        Diagnostics.note(service, "end of day: closing without History ($why)")
        withContext(Dispatchers.IO) { DailyExport.save(service, today, shop(), false, why, Diagnostics.daily(service, today)) }
        prefs.edit().putString(PREF_EOD_DONE, today.toString()).apply()
        eod = Eod.DONE
        eodForced = false
        setEodStatus("สิ้นวัน: $why — บันทึกเท่าที่มี")
        Notifier.report(service, "สรุปสิ้นวัน ${ReportText.date(today)} ไม่ครบ", "$why\nภาพ Ready ของวันนี้เก็บไว้แล้ว แต่ยังจับคู่กับรายการล่าช้าไม่ได้")
        switchOffForTheNight()
    }

    /**
     * Banking apps refuse to run while an accessibility service is on, so after tonight's report
     * the watcher switches itself off (Android lets an app switch itself off, never on). The
     * service watch raises the alarm from opening time if nobody switched it on again.
     */
    private fun switchOffForTheNight() {
        val c = cfg
        if (!c.autoOffAfterClose) return
        prefs.edit().putString(PREF_SELF_OFF_DAY, LocalDate.now().toString()).apply()
        Notifier.status(
            service,
            "ReadyProof ปิดตัวเองแล้ว — แอปธนาคารใช้ได้",
            "สรุปของวันนี้เสร็จแล้ว ก่อนร้านเปิด ${c.openTime} ให้เปิดกลับ: กดปุ่มเพิ่มเสียงกับลดเสียงค้างไว้ 3 วินาที " +
                "(ถ้าตั้งทางลัดไว้) หรือเปิดแอป ReadyProof → เปิดสิทธิ์การช่วยเหลือพิเศษ",
            Notifier.ID_OFF,
        )
        Diagnostics.note(service, "switching off for the night in ${SELF_OFF_DELAY_MS / 1000} s")
        scope.launch {
            // the night batch is being staged for Drive meanwhile
            delay(SELF_OFF_DELAY_MS)
            try {
                service.disableSelf()
            } catch (e: Exception) {
                Diagnostics.error(service, "disableSelf", e)
            }
        }
    }

    private fun resultLine(r: DailyReport, h: HistoryResult): String = buildString {
        append("Ready เห็น ").append(r.readySeenOrders).append(" / มีภาพ ").append(r.readyOrders)
        append(" / Completed ").append(r.completedSeen)
        r.grabCompleted?.let { append(" (Grab ").append(it).append(')') }
        append(" / Cancelled ").append(r.cancelledSeen)
        append(" / Delayed ").append(r.delayed)
        append(" / กดทัน ").append(r.inTime.size).append(" ช้าจริง ").append(r.late.size).append(" ไม่มีหลักฐาน ").append(r.noEvidence.size)
        if (!h.reachedEnd) append(" / ไม่ถึงท้ายรายการ")
        if (h.unshot.isNotEmpty()) append(" / ขาดภาพ DELAY ").append(h.unshot.joinToString(","))
        append(if (r.complete) " / MATCH" else " / INCOMPLETE")
    }

    // ---- keep the screen on while watching --------------------------------------------------------

    private fun updateAwake(grabVisible: Boolean) {
        val c = cfg
        // Always on while Grab is shown: the phone is left on the Ready tab day and night, so the
        // next morning needs nobody to wake it.
        val want = c.enabled && c.keepScreenOn && grabVisible
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
        if (!autoNavigation()) out += "⚠ ปิดการเลื่อน/แตะแท็บอัตโนมัติอยู่ (ตั้งค่า) — จะไม่กวาดรายการ ไม่พากลับแท็บ Ready และไม่สรุปสิ้นวันเอง"
        if (lastSweepAt > 0) out += "กวาดรายการ Ready ล่าสุด " + ReportText.time(lastSweepAt, zone)
        val unshot = tracker.pending()
        if (unshot.isNotEmpty()) out += "⚠ ยังแคปไม่ได้: " + unshot.joinToString(", ")
        eodStatus?.let { out += it }
        return out
    }

    /**
     * Manual shot (accessibility button / test button): whatever is on screen. Orders count as
     * Ready evidence only when they are fully visible in the Ready tab; the rest are kept as
     * "visible" so the photo can still be found by order number.
     */
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
            val fullGfs = s?.a?.views?.filter { it.full }?.map { it.gf }?.toSet() ?: emptySet()
            val fullReady = s?.a?.readyViews()?.filter { it.full }?.map { it.gf }?.toSet() ?: emptySet()
            val items = s?.let { ScreenAnalyzer.manualItems(it.a, c) }?.map { i ->
                if (i.type != ObsType.VISIBLE && i.gf !in fullGfs) i.copy(type = ObsType.VISIBLE) else i
            } ?: emptyList()
            val record = capture.save(
                bitmap, RecordKind.MANUAL, t,
                CaptureMeta(items = items, visible = s?.a?.visible ?: emptyList(), note = note, shopId = shop()),
            )
            if (record != null) {
                if (fullReady.isNotEmpty()) tracker.shot(fullReady, t)
                lastShotText = record.kind.label + " " + record.gfs.joinToString(", ").ifEmpty { "-" } + " · " + ReportText.time(t, zone)
                ProofService.lastCaptureText = lastShotText
                service.toast("📸 แคปแล้ว " + record.gfs.take(3).joinToString(", "))
            }
        }
    }
}
