from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
proof = ROOT / "readyproof/app/src/main/java/io/github/panuwattegif/readyproof/ProofService.kt"
capture = ROOT / "readyproof/app/src/main/java/io/github/panuwattegif/readyproof/CaptureManager.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"patch anchor missing: {label}")
    return text.replace(old, new, 1)


p = proof.read_text(encoding="utf-8")
p = replace_once(
    p,
    '        private const val SCAN_MIN_INTERVAL_MS = 900L\n',
    '        private const val SCAN_MIN_INTERVAL_MS = 900L\n        private const val READY_WATCH_INTERVAL_MS = 1_500L\n',
    'watch interval',
)
p = replace_once(
    p,
    '        private const val MAX_SWEEP_SCROLLS = 250\n',
    '        private const val MAX_SWEEP_SCROLLS = 250\n        private const val MAX_RETURN_TO_TOP_SCROLLS = 250\n',
    'return cap',
)
p = replace_once(
    p,
    '    private var sweepScrolls = 0\n    private var historyTabWasSelected = false\n',
    '    private var sweepScrolls = 0\n    @Volatile private var returningReadyToTop = false\n    private var returnToTopScrolls = 0\n    private var historyTabWasSelected = false\n',
    'return state',
)
p = replace_once(
    p,
    '        worker.post {\n            try {\n                val today = LocalDate.now()\n                deduper.seed(RecordStore.loadRange(this, today.minusDays(1), today))\n                Cleanup.runIfDue(this, config)\n            } catch (e: Exception) {\n                Diagnostics.error(this, "startup", e)\n            }\n        }\n',
    '        worker.post {\n            try {\n                val today = LocalDate.now()\n                deduper.seed(RecordStore.loadRange(this, today.minusDays(1), today))\n                Cleanup.runIfDue(this, config)\n            } catch (e: Exception) {\n                Diagnostics.error(this, "startup", e)\n            }\n            worker.postDelayed(readyWatchRunnable, READY_WATCH_INTERVAL_MS)\n        }\n',
    'start watcher',
)
p = replace_once(
    p,
    '    private fun scheduleScan() {\n',
    '''    /** Independent poller: the dedicated proof phone may receive Ready changes from another\n     * device without any useful Accessibility event. Never rely on a same-device Ready tap. */\n    private val readyWatchRunnable = object : Runnable {\n        override fun run() {\n            if (!::worker.isInitialized) return\n            try {\n                if (config.enabled && !returningReadyToTop) scheduleScan()\n            } finally {\n                runCatching { worker.postDelayed(this, READY_WATCH_INTERVAL_MS) }\n            }\n        }\n    }\n\n    private fun scheduleScan() {\n''',
    'watch runnable',
)
p = replace_once(
    p,
    '    private fun scan() {\n        val cfg = config\n',
    '    private fun scan() {\n        if (returningReadyToTop) return\n        val cfg = config\n',
    'pause while return',
)
old_capture_block = '''        val captureItems = fresh.filterNot { it.type == ObsType.DELAY && it in unproofableDelays }\n        val captureKeys = captureItems.mapNotNull { Deduper.keyOf(it) }\n        val ready = cfg.captureReady && captureItems.any { it.type == ObsType.READY }\n        val delay = cfg.captureDelay && captureItems.any { it.type == ObsType.DELAY }\n'''
new_capture_block = '''        val captureCandidates = fresh.filterNot { it.type == ObsType.DELAY && it in unproofableDelays }\n        // One evidence file = one target order. A page can contain many GFs, but an order is not\n        // considered captured until its own GF is visible and validated in its own screenshot job.\n        val targetItem = when {\n            cfg.captureReady -> captureCandidates.firstOrNull { it.type == ObsType.READY }\n            else -> null\n        } ?: when {\n            cfg.captureDelay -> captureCandidates.firstOrNull { it.type == ObsType.DELAY }\n            else -> null\n        }\n        val captureItems = listOfNotNull(targetItem)\n        val captureKeys = captureItems.mapNotNull { Deduper.keyOf(it) }\n        val ready = targetItem?.type == ObsType.READY\n        val delay = targetItem?.type == ObsType.DELAY\n'''
p = replace_once(p, old_capture_block, new_capture_block, 'single-target evidence')
p = replace_once(
    p,
    '''            if (pageKey.isNotEmpty() && pageCapturedRecently(pageKey, now)) {\n                // Same page after staff switches tabs: suppress a second identical screenshot.\n                if (captureKeys.isNotEmpty()) deduper.mark(captureKeys, now)\n                if (sweepMode) continueSweep(readyMode)\n                return\n            }\n''',
    '''            if (pageKey.isNotEmpty() && pageCapturedRecently(pageKey, now)) {\n                // Item-level dedupe owns correctness. Do not scroll away from an uncaptured target.\n                if (captureKeys.isNotEmpty()) deduper.mark(captureKeys, now)\n                scheduleScan()\n                return\n            }\n''',
    'duplicate hold viewport',
)
p = replace_once(
    p,
    '''                } else {\n                    finishSweep(readyMode ?: forcedReadySweep)\n                }\n''',
    '''                } else {\n                    finishSweep(readyMode ?: forcedReadySweep)\n                }\n''',
    'noop finish anchor',
)
old_finish = '''    private fun finishSweep(wasReady: Boolean) {\n        if (wasReady || forcedReadySweep) {\n            forcedReadySweep = false\n            resetSweepLoop()\n            if (returnToPreparingAfterReady) {\n                returnToPreparingAfterReady = false\n                // Return to the working tab so the automated proof collection does not interrupt staff.\n                main.postDelayed({ clickTab(listOf("Preparing", "กำลังเตรียม")) }, 250L)\n            }\n        }\n        if (forcedHistorySweep) {\n            forcedHistorySweep = false\n            resetSweepLoop()\n        }\n    }\n'''
new_finish = '''    private fun finishSweep(wasReady: Boolean) {\n        if (wasReady || forcedReadySweep) {\n            forcedReadySweep = false\n            resetSweepLoop()\n            if (returnToPreparingAfterReady) {\n                returnToPreparingAfterReady = false\n                // Legacy same-device flow: return only when ReadyProof opened Ready itself.\n                main.postDelayed({ clickTab(listOf("Preparing", "กำลังเตรียม")) }, 250L)\n            } else {\n                // Dedicated proof phone stays on Ready. Sweep back to the top so an order inserted\n                // above the current viewport cannot be missed between Accessibility events.\n                startReturnReadyToTop()\n                return\n            }\n        }\n        if (forcedHistorySweep) {\n            forcedHistorySweep = false\n            resetSweepLoop()\n        }\n    }\n\n    private fun startReturnReadyToTop() {\n        if (returningReadyToTop) return\n        returningReadyToTop = true\n        returnToTopScrolls = 0\n        continueReturnReadyToTop()\n    }\n\n    private fun continueReturnReadyToTop() {\n        worker.postDelayed({\n            main.post {\n                val canContinue = returnToTopScrolls < MAX_RETURN_TO_TOP_SCROLLS\n                val moved = canContinue && scrollOrderListBackward()\n                if (moved) {\n                    returnToTopScrolls++\n                    lastScrollAt = SystemClock.uptimeMillis()\n                    continueReturnReadyToTop()\n                } else {\n                    returningReadyToTop = false\n                    returnToTopScrolls = 0\n                    worker.postDelayed({ scheduleScan() }, READY_WATCH_INTERVAL_MS)\n                }\n            }\n        }, AUTO_SCROLL_DELAY_MS)\n    }\n'''
p = replace_once(p, old_finish, new_finish, 'return ready to top')
old_done = '''        if (record == null) {\n            deduper.forget(meta.dedupeKeys)\n            Diagnostics.error(this, "capture ${job.kind}", RuntimeException(error))\n            val now = SystemClock.uptimeMillis()\n            if (now - lastFailToastAt > 30_000L) {\n                lastFailToastAt = now\n                toast("⚠️ ${error ?: "แคปไม่สำเร็จ"} — ถ้าเป็นบ่อยให้ถ่ายหน้าจอเองไปก่อน")\n            }\n            if (forcedReadySweep || forcedHistorySweep) continueSweep(forcedReadySweep)\n            return\n        }\n'''
new_done = '''        if (record == null) {\n            deduper.forget(meta.dedupeKeys)\n            Diagnostics.error(this, "capture ${job.kind}", RuntimeException(error))\n            val now = SystemClock.uptimeMillis()\n            if (now - lastFailToastAt > 30_000L) {\n                lastFailToastAt = now\n                toast("⚠️ ${error ?: "แคปไม่สำเร็จ"} — ค้างเฟรมนี้และจะลองใหม่ ไม่ข้ามออเดอร์")\n            }\n            // Fail closed: never advance the list after a proof failure. Re-read the same viewport.\n            scheduleScan()\n            return\n        }\n'''
p = replace_once(p, old_done, new_done, 'failure does not scroll')
p = replace_once(
    p,
    '''        if (job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) {\n            if (forcedReadySweep || forcedHistorySweep) continueSweep(forcedReadySweep)\n            else scheduleScan()\n        }\n''',
    '''        if (job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) {\n            // Re-scan the SAME viewport first. This captures every visible order one-by-one before\n            // the sweep is allowed to move to the next page.\n            scheduleScan()\n        }\n''',
    'success rescan viewport',
)
proof.write_text(p, encoding="utf-8")

c = capture.read_text(encoding="utf-8")
c = replace_once(
    c,
    '            if (job.kind == RecordKind.DELAY && !delayTargetsStillVisible(job)) {\n',
    '            if ((job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) && !evidenceTargetsStillVisible(job)) {\n',
    'pre validation',
)
c = replace_once(
    c,
    '                    onDone(job, null, "ไม่บันทึกภาพ DELAY: GF/ข้อความล่าช้าไม่อยู่ในเฟรมเดียวกัน")\n',
    '                    onDone(job, null, "ไม่บันทึกภาพ ${job.kind}: GF/หลักฐานเป้าหมายไม่อยู่ในเฟรม")\n',
    'pre error',
)
c = replace_once(
    c,
    '                            if (job.kind == RecordKind.DELAY && !delayTargetsStillVisible(job)) {\n',
    '                            if ((job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) && !evidenceTargetsStillVisible(job)) {\n',
    'post validation',
)
c = replace_once(
    c,
    '                onDone(job, null, "ไม่บันทึกภาพ DELAY: หน้าจอเปลี่ยนระหว่างแคปหลายครั้ง")\n',
    '                onDone(job, null, "ไม่บันทึกภาพ ${job.kind}: หน้าจอเปลี่ยนระหว่างแคปหลายครั้ง")\n',
    'retry error',
)
start = c.index('    private fun delayTargetsStillVisible(job: CaptureJob): Boolean {')
end = c.index('\n    private fun save(job: CaptureJob, shot: AccessibilityService.ScreenshotResult) {', start)
new_validator = '''    private fun evidenceTargetsStillVisible(job: CaptureJob): Boolean {\n        val meta = job.awaitMeta(0)\n        val wantedType = if (job.kind == RecordKind.READY) ObsType.READY else ObsType.DELAY\n        val targets = meta.items.filter { it.type == wantedType }\n        if (targets.size != 1) return false\n\n        val ok = AtomicBoolean(false)\n        val done = CountDownLatch(1)\n        main.post {\n            try {\n                val cfg = ConfigStore.get(service)\n                val roots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()\n                runCatching {\n                    for (w in service.windows) {\n                        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue\n                        val root = w.root ?: continue\n                        val pkg = root.packageName?.toString()\n                        if (pkg != null && pkg in cfg.targetPackages) roots += root\n                    }\n                }\n                if (roots.isEmpty()) {\n                    service.rootInActiveWindow?.let { root ->\n                        val pkg = root.packageName?.toString()\n                        if (pkg == null || pkg in cfg.targetPackages) roots += root\n                    }\n                }\n                if (roots.isEmpty()) return@post\n\n                val snaps = roots.map { NodeSnapshot.capture(it, VALIDATION_MAX_NODES) }\n                val analysis = ScreenAnalyzer.analyze(snaps, cfg)\n                val rules = StatusRules(cfg)\n                val extractor = cfg.gfExtractor()\n                val target = targets.single()\n                val card = analysis.cards.firstOrNull { card -> card.inList && card.gf == target.gf }\n                    ?: return@post\n\n                val gfVisible = card.node.walk().any { n ->\n                    n.top >= 0 && n.bottom > n.top && n.ownStrings().any { s -> target.gf in extractor.extract(s) }\n                }\n                if (!gfVisible) return@post\n\n                if (wantedType == ObsType.READY) {\n                    // The selected Ready tab itself is the READY proof. Do not depend on wording\n                    // inside the card; simply reject History/Delayed cards and require this GF onscreen.\n                    val historyLike = rules.evaluate(card).any { seen ->\n                        seen.type == ObsType.DONE || seen.type == ObsType.DELAY\n                    }\n                    ok.set(!historyLike && analysis.readyTab != false)\n                } else {\n                    val delayMatch = rules.evaluate(card).any { seen ->\n                        seen.type == ObsType.DELAY &&\n                            (target.doneAt == null || seen.doneAt == target.doneAt)\n                    }\n                    val delayVisible = card.node.walk().any { n ->\n                        n.top >= 0 && n.bottom > n.top && n.ownStrings().any { s -> TextNorm.containsAny(s, cfg.delayAny) }\n                    }\n                    ok.set(delayMatch && delayVisible)\n                }\n            } catch (_: Exception) {\n                ok.set(false)\n            } finally {\n                done.countDown()\n            }\n        }\n        return done.await(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok.get()\n    }\n'''
c = c[:start] + new_validator + c[end:]
capture.write_text(c, encoding="utf-8")

print("patched", proof)
print("patched", capture)
