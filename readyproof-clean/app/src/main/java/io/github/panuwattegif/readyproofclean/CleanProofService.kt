package io.github.panuwattegif.readyproofclean

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.panuwattegif.readyproofclean.core.*
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CleanProofService : AccessibilityService() {
    companion object {
        @Volatile var instance: CleanProofService? = null
            private set
        @Volatile var statusText: String = "ยังไม่เริ่ม"
            private set
        private const val PACKAGE = "com.grab.merchant"
        private const val MAX_NODES = 1800
        private const val POLL_MS = 1400L
        private const val SETTLE_MS = 650L
        private const val MAX_HISTORY_PASSES = 5
    }

    private val cfg = Config.DEFAULT
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val scanPending = AtomicBoolean(false)
    @Volatile private var captureBusy = false
    @Volatile private var lastScrollAt = 0L

    private var readyDay = LocalDate.now()
    private val readySeen = LinkedHashSet<String>()
    private val readyProof = LinkedHashSet<String>()
    private var readyForward = true
    private var readyLastSignature = ""
    private var readySameSignature = 0

    private enum class HistoryPhase { IDLE, TO_TOP, DOWN }
    private var historyPhase = HistoryPhase.IDLE
    private var historyTarget: LocalDate? = null
    private var historyPass = 0
    private var historyHeader: LocalDate? = null
    private var historyLastSignature = ""
    private var historySameSignature = 0
    private var targetSeen = false
    private var olderBoundarySeen = false
    private val historySeen = LinkedHashSet<String>()
    private val historyProof = LinkedHashSet<String>()
    private var headerTotal: Int? = null
    private var headerCompleted: Int? = null
    private var headerCancelled: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        thread = HandlerThread("readyproof-clean").also { it.start() }
        worker = Handler(thread.looper)
        serviceInfo = serviceInfo?.apply { packageNames = arrayOf(PACKAGE) }
        loadReadyState()
        statusText = "เฝ้า Ready"
        worker.post(poller)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == PACKAGE) scheduleScan()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::thread.isInitialized) thread.quitSafely()
        shotExecutor.shutdownNow()
        super.onDestroy()
    }

    private val poller = object : Runnable {
        override fun run() {
            scheduleScan()
            if (::worker.isInitialized) worker.postDelayed(this, POLL_MS)
        }
    }

    fun reloadShopState() {
        if (!::worker.isInitialized) return
        worker.post {
            readySeen.clear()
            readyProof.clear()
            loadReadyState()
            statusText = "เปลี่ยนร้านแล้ว · เฝ้า Ready"
            scheduleScan()
        }
    }

    fun startHistorySweep(day: LocalDate) {
        if (!::worker.isInitialized) return
        worker.post {
            historyTarget = day
            historyPass = 1
            historyPhase = HistoryPhase.TO_TOP
            historyHeader = null
            historyLastSignature = ""
            historySameSignature = 0
            targetSeen = false
            olderBoundarySeen = false
            historySeen.clear()
            historyProof.clear()
            headerTotal = null
            headerCompleted = null
            headerCancelled = null
            loadHistoryProofState(day)
            statusText = "กำลังเปิด History วันที่ $day"
            main.post {
                clickTab(listOf("History", "ประวัติ"))
                worker.postDelayed({ scheduleScan() }, 1200L)
            }
        }
    }

    fun returnToReady() {
        if (!::worker.isInitialized) return
        worker.post {
            historyPhase = HistoryPhase.IDLE
            historyTarget = null
            statusText = "เฝ้า Ready"
            main.post {
                clickTab(cfg.readyTabLabels)
                worker.postDelayed({ scheduleScan() }, 900L)
            }
        }
    }

    private fun scheduleScan() {
        if (!::worker.isInitialized || !scanPending.compareAndSet(false, true)) return
        worker.postDelayed({
            scanPending.set(false)
            if (SystemClock.uptimeMillis() - lastScrollAt < SETTLE_MS) {
                scheduleScan()
                return@postDelayed
            }
            runCatching { scan() }.onFailure { statusText = "ผิดพลาด: ${it.message ?: it.javaClass.simpleName}" }
        }, 180L)
    }

    private fun scan() {
        if (captureBusy) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PACKAGE) return
        val roots = listOf(NodeSnapshot.capture(root, MAX_NODES))
        if (historyPhase != HistoryPhase.IDLE) {
            scanHistory(roots)
            return
        }
        val analysis = ScreenAnalyzer.analyze(roots, cfg)
        if (analysis.readyTab == true) scanReady(analysis)
    }

    private fun scanReady(analysis: ScreenAnalysis) {
        val today = LocalDate.now()
        if (today != readyDay) {
            readyDay = today
            readySeen.clear()
            readyProof.clear()
            readyLastSignature = ""
            readySameSignature = 0
            loadReadyState()
        }
        val shop = CleanStore.selectedShop(this)
        val visibleReady = analysis.items.filter { it.type == ObsType.READY }.map { it.gf }.distinct()
        val newlySeen = visibleReady.filter { readySeen.add(it) }
        if (newlySeen.isNotEmpty()) {
            CleanStore.append(this, CleanEvent(
                kind = "READY_SEEN", t = System.currentTimeMillis(), date = today.toString(),
                shop = shop, gfs = newlySeen,
            ))
        }

        val signature = analysis.cards.filter { it.inList }.joinToString("|") {
            "${it.gf}@${it.node.top}:${it.node.bottom}:${it.texts.joinToString("~")}"
        }
        if (signature.isNotEmpty() && signature == readyLastSignature) readySameSignature++ else readySameSignature = 0
        readyLastSignature = signature
        if (readySameSignature >= 2) {
            readyForward = !readyForward
            readySameSignature = 0
            readyLastSignature = ""
        }

        val pending = visibleReady.filter { it !in readyProof }
        statusText = "Ready เห็น ${readySeen.size} / มีภาพ ${readyProof.size} / Pending ${(readySeen - readyProof).size}"
        if (pending.isNotEmpty()) {
            captureReady(analysis, pending)
            return
        }

        main.post {
            val action = if (readyForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val moved = scrollOrderList(action)
            if (!moved) readyForward = !readyForward
            lastScrollAt = SystemClock.uptimeMillis()
            worker.postDelayed({ scheduleScan() }, SETTLE_MS)
        }
    }

    private fun captureReady(before: ScreenAnalysis, requested: List<String>) {
        val beforeFp = readyFingerprints(before, requested)
        if (beforeFp.isEmpty()) return
        captureBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var bitmap: Bitmap? = null
                try {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (bitmap == null) return
                    val stable = runOnMainForResult {
                        val root = rootInActiveWindow ?: return@runOnMainForResult emptyList<String>()
                        if (root.packageName?.toString() != PACKAGE) return@runOnMainForResult emptyList<String>()
                        val after = ScreenAnalyzer.analyze(listOf(NodeSnapshot.capture(root, MAX_NODES)), cfg)
                        if (after.readyTab != true) return@runOnMainForResult emptyList<String>()
                        val afterFp = readyFingerprints(after, requested)
                        beforeFp.keys.filter { afterFp[it] == beforeFp[it] }
                    } ?: emptyList()
                    if (stable.isEmpty()) return
                    val now = System.currentTimeMillis()
                    val shop = CleanStore.selectedShop(this@CleanProofService)
                    val name = "READY_${readyDay}_${stamp(now)}_${stable.joinToString("_")}.jpg".take(180)
                    val uri = MediaSaver.saveJpeg(this@CleanProofService, bitmap, shop, readyDay.toString(), name, now)
                    CleanStore.append(this@CleanProofService, CleanEvent(
                        kind = "READY_PROOF", t = now, date = readyDay.toString(), shop = shop,
                        gfs = stable, uri = uri.toString(),
                    ))
                    readyProof.addAll(stable)
                } finally {
                    bitmap?.recycle()
                    captureBusy = false
                    worker.post { scheduleScan() }
                }
            }

            override fun onFailure(errorCode: Int) {
                captureBusy = false
                statusText = "แคป Ready ไม่สำเร็จรหัส $errorCode — จะลองใหม่"
                worker.postDelayed({ scheduleScan() }, 700L)
            }
        })
    }

    private fun readyFingerprints(analysis: ScreenAnalysis, gfs: List<String>): Map<String, String> {
        val wanted = gfs.toSet()
        return analysis.cards.filter { it.inList && it.gf in wanted }.groupBy { it.gf }.mapNotNull { (gf, cards) ->
            val c = cards.singleOrNull() ?: return@mapNotNull null
            gf to listOf(c.node.left, c.node.top, c.node.right, c.node.bottom, c.texts.joinToString("|")).joinToString(":")
        }.toMap()
    }

    private fun scanHistory(roots: List<UiNode>) {
        val target = historyTarget ?: return
        if (!historyTabSelected(roots)) {
            main.post {
                clickTab(listOf("History", "ประวัติ"))
                worker.postDelayed({ scheduleScan() }, 1000L)
            }
            return
        }

        var analysis = ScreenAnalyzer.analyze(roots, cfg, allowUnknownDelayed = true)
        val dated = HistoryDates.assign(analysis.items, analysis, roots, LocalDate.now(), historyHeader)
        analysis = analysis.copy(items = dated.first)
        historyHeader = dated.second

        if (target == LocalDate.now()) {
            HistoryTotalsParser.parseRoots(roots, cfg)?.let { totals ->
                if (headerTotal != totals.total || headerCompleted != totals.completed || headerCancelled != totals.cancelled) {
                    headerTotal = totals.total
                    headerCompleted = totals.completed
                    headerCancelled = totals.cancelled
                    CleanStore.append(this, CleanEvent(
                        kind = "HISTORY_TOTAL", t = System.currentTimeMillis(), date = target.toString(),
                        shop = CleanStore.selectedShop(this), completed = totals.completed,
                        cancelled = totals.cancelled, total = totals.total,
                    ))
                }
            }
        }

        val signature = analysis.cards.filter { it.inList }.joinToString("|") {
            "${it.gf}@${it.node.top}:${it.node.bottom}:${it.texts.joinToString("~")}"
        }
        if (signature.isNotEmpty() && signature == historyLastSignature) historySameSignature++ else historySameSignature = 0
        historyLastSignature = signature

        if (historyPhase == HistoryPhase.TO_TOP) {
            if (historySameSignature >= 2) {
                historyPhase = HistoryPhase.DOWN
                historySameSignature = 0
                historyLastSignature = ""
                statusText = "History $target: เริ่มกวาดรอบ $historyPass"
                scheduleScan()
            } else {
                main.post {
                    scrollOrderList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    lastScrollAt = SystemClock.uptimeMillis()
                    worker.postDelayed({ scheduleScan() }, SETTLE_MS)
                }
            }
            return
        }

        val instances = historyInstances(analysis, target)
        if (instances.isNotEmpty()) {
            targetSeen = true
            val newOnes = instances.filter { historySeen.add(it.key) }
            if (newOnes.isNotEmpty()) CleanStore.append(this, CleanEvent(
                kind = "HISTORY_SEEN", t = System.currentTimeMillis(), date = target.toString(),
                shop = CleanStore.selectedShop(this), instances = newOnes,
            ))
            val needProof = instances.filter { it.key !in historyProof }
            if (needProof.isNotEmpty()) {
                captureHistory(analysis, needProof)
                return
            }
        }

        val datedTerminal = analysis.items
            .filter { it.type in listOf(ObsType.DONE, ObsType.CANCELLED, ObsType.DELAY) }
            .mapNotNull { it.historyDate?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() } }
        if (targetSeen && datedTerminal.any { it.isBefore(target) }) olderBoundarySeen = true

        val expected = headerTotal
        val completeNow = when {
            expected != null -> historySeen.size >= expected && historyProof.containsAll(historySeen)
            else -> targetSeen && olderBoundarySeen && historyProof.containsAll(historySeen)
        }
        if (completeNow) {
            finishHistory(true, "กวาดครบ: ${historySeen.size}${expected?.let { "/$it" } ?: ""} ออเดอร์")
            return
        }
        if (olderBoundarySeen) {
            retryOrFinish("เลยวันที่ $target แล้ว แต่ได้ ${historySeen.size}${expected?.let { "/$it" } ?: ""}")
            return
        }
        if (historySameSignature >= 3) {
            retryOrFinish("ถึงปลายรายการ แต่ได้ ${historySeen.size}${expected?.let { "/$it" } ?: ""}")
            return
        }

        main.post {
            val moved = scrollOrderList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!moved) historySameSignature++ else lastScrollAt = SystemClock.uptimeMillis()
            worker.postDelayed({ scheduleScan() }, SETTLE_MS)
        }
        statusText = "History $target: อ่าน ${historySeen.size}${headerTotal?.let { "/$it" } ?: ""} / มีภาพ ${historyProof.size}"
    }

    private fun historyInstances(analysis: ScreenAnalysis, target: LocalDate): List<HistoryInstance> {
        val rules = StatusRules(cfg)
        return analysis.cards.filter { it.inList }.mapNotNull { card ->
            val observed = rules.evaluate(card, allowUnknownDelayed = true)
            val terminal = observed.firstOrNull { it.type == ObsType.DONE || it.type == ObsType.CANCELLED }
            val delay = observed.firstOrNull { it.type == ObsType.DELAY }
            val assigned = analysis.items.filter { it.gf == card.gf && it.card == card.texts }
            val date = assigned.firstNotNullOfOrNull { it.historyDate }
            if (date != target.toString()) return@mapNotNull null
            val doneAt = terminal?.doneAt ?: delay?.doneAt
            if (terminal == null && delay == null) return@mapNotNull null
            HistoryInstance(
                gf = card.gf,
                doneAt = doneAt,
                cancelled = terminal?.type == ObsType.CANCELLED,
                delayed = delay != null,
                delayMin = delay?.delayMin,
                date = date,
            )
        }.distinctBy { it.key }
    }

    private fun captureHistory(before: ScreenAnalysis, requested: List<HistoryInstance>) {
        val beforeFp = historyFingerprints(before, requested)
        if (beforeFp.isEmpty()) return
        captureBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                var bitmap: Bitmap? = null
                try {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (bitmap == null) return
                    val stable = runOnMainForResult {
                        val target = historyTarget ?: return@runOnMainForResult emptyList<HistoryInstance>()
                        val root = rootInActiveWindow ?: return@runOnMainForResult emptyList<HistoryInstance>()
                        if (root.packageName?.toString() != PACKAGE) return@runOnMainForResult emptyList<HistoryInstance>()
                        val roots = listOf(NodeSnapshot.capture(root, MAX_NODES))
                        if (!historyTabSelected(roots)) return@runOnMainForResult emptyList<HistoryInstance>()
                        var after = ScreenAnalyzer.analyze(roots, cfg, allowUnknownDelayed = true)
                        val dated = HistoryDates.assign(after.items, after, roots, LocalDate.now(), historyHeader)
                        after = after.copy(items = dated.first)
                        val afterInstances = historyInstances(after, target)
                        val afterFp = historyFingerprints(after, afterInstances)
                        requested.filter { beforeFp[it.key] != null && beforeFp[it.key] == afterFp[it.key] }
                    } ?: emptyList()
                    if (stable.isEmpty()) return
                    val now = System.currentTimeMillis()
                    val target = historyTarget ?: return
                    val shop = CleanStore.selectedShop(this@CleanProofService)
                    val delayed = stable.filter { it.delayed }.map { it.gf }.distinct()
                    val label = if (delayed.isNotEmpty()) "DELAY_${delayed.joinToString("_")}" else "HISTORY"
                    val name = "${label}_${target}_${stamp(now)}.jpg".take(180)
                    val uri = MediaSaver.saveJpeg(this@CleanProofService, bitmap, shop, target.toString(), name, now)
                    CleanStore.append(this@CleanProofService, CleanEvent(
                        kind = "HISTORY_PROOF", t = now, date = target.toString(), shop = shop,
                        uri = uri.toString(), instances = stable,
                    ))
                    stable.forEach { historyProof += it.key }
                } finally {
                    bitmap?.recycle()
                    captureBusy = false
                    worker.post { scheduleScan() }
                }
            }

            override fun onFailure(errorCode: Int) {
                captureBusy = false
                statusText = "แคป History ไม่สำเร็จรหัส $errorCode — จะลองใหม่"
                worker.postDelayed({ scheduleScan() }, 700L)
            }
        })
    }

    private fun historyFingerprints(analysis: ScreenAnalysis, instances: List<HistoryInstance>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        instances.forEach { i ->
            val cards = analysis.cards.filter { it.inList && it.gf == i.gf }
            val card = cards.singleOrNull() ?: return@forEach
            out[i.key] = listOf(card.node.left, card.node.top, card.node.right, card.node.bottom, card.texts.joinToString("|")).joinToString(":")
        }
        return out
    }

    private fun retryOrFinish(reason: String) {
        if (historyPass < MAX_HISTORY_PASSES) {
            historyPass++
            historyPhase = HistoryPhase.TO_TOP
            historyHeader = null
            historyLastSignature = ""
            historySameSignature = 0
            targetSeen = false
            olderBoundarySeen = false
            statusText = "$reason — เริ่มกวาดซ้ำรอบ $historyPass/$MAX_HISTORY_PASSES"
            worker.postDelayed({ scheduleScan() }, 900L)
        } else {
            finishHistory(false, "$reason หลัง $MAX_HISTORY_PASSES รอบ; เก็บภาพที่ได้ทั้งหมดไว้แล้ว")
        }
    }

    private fun finishHistory(complete: Boolean, note: String) {
        val target = historyTarget ?: return
        CleanStore.append(this, CleanEvent(
            kind = "SWEEP_STATUS", t = System.currentTimeMillis(), date = target.toString(),
            shop = CleanStore.selectedShop(this), complete = complete, note = note,
        ))
        statusText = if (complete) "History COMPLETE — $note" else "History INCOMPLETE — $note"
        historyPhase = HistoryPhase.IDLE
        historyTarget = null
        main.post {
            clickTab(cfg.readyTabLabels)
            worker.postDelayed({ scheduleScan() }, 1000L)
        }
    }

    private fun loadReadyState() {
        val shop = CleanStore.selectedShop(this)
        val events = CleanStore.load(this, shop, readyDay)
        readySeen.addAll(events.filter { it.kind == "READY_SEEN" }.flatMap { it.gfs })
        readyProof.addAll(events.filter { it.kind == "READY_PROOF" && it.uri != null }.flatMap { it.gfs })
    }

    private fun loadHistoryProofState(day: LocalDate) {
        val shop = CleanStore.selectedShop(this)
        val events = CleanStore.load(this, shop, day)
        events.filter { it.kind == "HISTORY_SEEN" || it.kind == "HISTORY_PROOF" }
            .flatMap { it.instances }.forEach { historySeen += it.key }
        events.filter { it.kind == "HISTORY_PROOF" && it.uri != null }
            .flatMap { it.instances }.forEach { historyProof += it.key }
        events.lastOrNull { it.kind == "HISTORY_TOTAL" }?.let {
            headerTotal = it.total
            headerCompleted = it.completed
            headerCancelled = it.cancelled
        }
    }

    private fun historyTabSelected(roots: List<UiNode>): Boolean = roots.any { root ->
        root.walk().any { n ->
            (n.selected || n.parent?.selected == true || n.parent?.parent?.selected == true) &&
                n.ownStrings().any { s -> listOf("History", "ประวัติ").any { TabDetector.isLabel(s, it) } }
        }
    }

    private fun clickTab(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val strings = listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            if (strings.any { s -> labels.any { TabDetector.isLabel(s, it) } }) {
                var c: AccessibilityNodeInfo? = n
                repeat(4) {
                    if (c?.isClickable == true && c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                    c = c?.parent
                }
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let(q::addLast)
        }
        return false
    }

    private fun scrollOrderList(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = ArrayList<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isScrollable) candidates += n
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let(q::addLast)
        }
        val gfx = cfg.gfExtractor()
        val ordered = candidates.sortedByDescending { node ->
            var count = 0
            val qq = ArrayDeque<AccessibilityNodeInfo>()
            qq.add(node)
            var guard = 0
            while (qq.isNotEmpty() && guard++ < 500) {
                val x = qq.removeFirst()
                count += gfx.extract(x.text?.toString()).size
                count += gfx.extract(x.contentDescription?.toString()).size
                for (i in 0 until x.childCount) runCatching { x.getChild(i) }.getOrNull()?.let(qq::addLast)
            }
            count
        }
        for (n in ordered) if (runCatching { n.performAction(action) }.getOrDefault(false)) return true
        return false
    }

    private fun <T> runOnMainForResult(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val monitor = java.lang.Object()
        var result: Any? = null
        var done = false
        main.post {
            synchronized(monitor) {
                result = block()
                done = true
                monitor.notifyAll()
            }
        }
        synchronized(monitor) {
            val end = System.currentTimeMillis() + 1800L
            while (!done && System.currentTimeMillis() < end) monitor.wait(100L)
        }
        @Suppress("UNCHECKED_CAST")
        return if (done) result as T? else null
    }

    private fun stamp(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalTime()
        return "%02d%02d%02d".format(java.util.Locale.US, t.hour, t.minute, t.second)
    }
}
