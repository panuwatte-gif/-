package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.StatusRules
import io.github.panuwattegif.readyproof.core.TextNorm
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What gets stored next to a screenshot; for taps it is filled in while the shot is being taken. */
data class CaptureMeta(
    val items: List<Item> = emptyList(),
    val visible: List<String> = emptyList(),
    val click: String? = null,
    val note: String? = null,
    /** Dedupe keys to release again if the shot fails, so the next scan retries. */
    val dedupeKeys: List<String> = emptyList(),
    val toast: String? = null,
)

class CaptureJob(val kind: RecordKind, val t: Long) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var meta: CaptureMeta? = null

    fun setMeta(m: CaptureMeta) {
        meta = m
        latch.countDown()
    }

    fun awaitMeta(timeoutMs: Long): CaptureMeta {
        if (timeoutMs > 0) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return meta ?: CaptureMeta()
    }
}

/**
 * Takes silent screenshots through the accessibility service (no flash, no sound, no user
 * prompt) and saves them with a log record.
 *
 * DELAY evidence is fail-closed: immediately before AND immediately after Android captures the
 * screen, the target order must still expose both GF-xxx and Delayed/ล่าช้า in the same visible
 * History card. If the list moved while a screenshot was queued, that stale screenshot is thrown
 * away instead of being saved under the old GF filename.
 */
class CaptureManager(
    private val service: AccessibilityService,
    private val onDone: (job: CaptureJob, record: Record?, error: String?) -> Unit,
) {
    /** Only one capture job may be in the Android screenshot API at a time. */
    private val requests = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()

    @Volatile
    private var lastRequestAt = 0L

    fun submit(job: CaptureJob) {
        try {
            requests.execute { request(job) }
        } catch (e: Exception) {
            onDone(job, null, "ระบบกำลังปิด")
        }
    }

    /**
     * Keep the request executor occupied until this screenshot has actually completed. The old
     * implementation only serialised the calls to takeScreenshot(); callbacks could still finish
     * later, leaving an old metadata job in the queue after History had already scrolled.
     */
    private fun request(job: CaptureJob) {
        var attempt = 1
        while (attempt <= MAX_ATTEMPTS) {
            if ((job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) && !evidenceTargetsStillVisible(job)) {
                if (attempt >= MAX_ATTEMPTS) {
                    onDone(job, null, "ไม่บันทึกภาพ ${job.kind}: GF/หลักฐานเป้าหมายไม่อยู่ในเฟรม")
                    return
                }
                Thread.sleep(TARGET_RETRY_DELAY_MS)
                attempt++
                continue
            }

            val wait = lastRequestAt + MIN_GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = SystemClock.uptimeMillis()

            val finished = CountDownLatch(1)
            val retry = AtomicBoolean(false)
            val callbackDelivered = AtomicBoolean(false)
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, io, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            // Validate again after Android produced the bitmap. If Grab moved the
                            // row during the request, do not attach the old GF metadata to this shot.
                            if ((job.kind == RecordKind.READY || job.kind == RecordKind.DELAY) && !evidenceTargetsStillVisible(job)) {
                                runCatching { screenshot.hardwareBuffer.close() }
                                retry.set(true)
                                return
                            }
                            save(job, screenshot)
                        } finally {
                            callbackDelivered.set(true)
                            finished.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        try {
                            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_ATTEMPTS) {
                                retry.set(true)
                            } else {
                                onDone(job, null, "แคปหน้าจอไม่สำเร็จ (รหัส $errorCode)")
                            }
                        } finally {
                            callbackDelivered.set(true)
                            finished.countDown()
                        }
                    }
                })
            } catch (e: Exception) {
                onDone(job, null, "แคปหน้าจอไม่สำเร็จ: ${e.message}")
                return
            }

            if (!finished.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (!callbackDelivered.get()) onDone(job, null, "แคปหน้าจอไม่ตอบสนองภายในเวลาที่กำหนด")
                return
            }
            if (!retry.get()) return

            if (attempt >= MAX_ATTEMPTS) {
                onDone(job, null, "ไม่บันทึกภาพ ${job.kind}: หน้าจอเปลี่ยนระหว่างแคปหลายครั้ง")
                return
            }
            Thread.sleep(RETRY_DELAY_MS)
            attempt++
        }
    }

    /**
     * Strict proof check for DELAY screenshots. NodeSnapshot contains only nodes Android says are
     * visible, and we additionally require positive on-screen bounds for both the GF label and the
     * delayed label inside the same order card.
     */
    private fun evidenceTargetsStillVisible(job: CaptureJob): Boolean {
        val meta = job.awaitMeta(0)
        val wantedType = if (job.kind == RecordKind.READY) ObsType.READY else ObsType.DELAY
        val targets = meta.items.filter { it.type == wantedType }
        if (targets.size != 1) return false

        val ok = AtomicBoolean(false)
        val done = CountDownLatch(1)
        main.post {
            try {
                val cfg = ConfigStore.get(service)
                val roots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
                runCatching {
                    for (w in service.windows) {
                        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                        val root = w.root ?: continue
                        val pkg = root.packageName?.toString()
                        if (pkg != null && pkg in cfg.targetPackages) roots += root
                    }
                }
                if (roots.isEmpty()) {
                    service.rootInActiveWindow?.let { root ->
                        val pkg = root.packageName?.toString()
                        if (pkg == null || pkg in cfg.targetPackages) roots += root
                    }
                }
                if (roots.isEmpty()) return@post

                val snaps = roots.map { NodeSnapshot.capture(it, VALIDATION_MAX_NODES) }
                val analysis = ScreenAnalyzer.analyze(snaps, cfg)
                val rules = StatusRules(cfg)
                val extractor = cfg.gfExtractor()
                val target = targets.single()
                val card = analysis.cards.firstOrNull { card -> card.inList && card.gf == target.gf }
                    ?: return@post

                val gfVisible = card.node.walk().any { n ->
                    n.top >= 0 && n.bottom > n.top && n.ownStrings().any { s -> target.gf in extractor.extract(s) }
                }
                if (!gfVisible) return@post

                if (wantedType == ObsType.READY) {
                    // The selected Ready tab itself is the READY proof. Do not depend on wording
                    // inside the card; simply reject History/Delayed cards and require this GF onscreen.
                    val historyLike = rules.evaluate(card).any { seen ->
                        seen.type == ObsType.DONE || seen.type == ObsType.DELAY
                    }
                    ok.set(!historyLike && analysis.readyTab != false)
                } else {
                    val delayMatch = rules.evaluate(card).any { seen ->
                        seen.type == ObsType.DELAY &&
                            (target.doneAt == null || seen.doneAt == target.doneAt)
                    }
                    val delayVisible = card.node.walk().any { n ->
                        n.top >= 0 && n.bottom > n.top && n.ownStrings().any { s -> TextNorm.containsAny(s, cfg.delayAny) }
                    }
                    ok.set(delayMatch && delayVisible)
                }
            } catch (_: Exception) {
                ok.set(false)
            } finally {
                done.countDown()
            }
        }
        return done.await(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok.get()
    }

    private fun save(job: CaptureJob, shot: AccessibilityService.ScreenshotResult) {
        var bitmap: Bitmap? = null
        try {
            val buffer = shot.hardwareBuffer
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
            bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
            hardware?.recycle()
            buffer.close()
            if (bitmap == null) {
                onDone(job, null, "แปลงภาพหน้าจอไม่สำเร็จ")
                return
            }
            val meta = job.awaitMeta(META_TIMEOUT_MS)
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(job.t), ZoneId.systemDefault())
            val name = Naming.fileName(job.kind, meta.items, meta.visible, at)
            val uri = MediaSaver.saveJpeg(service, bitmap, name, job.t, ConfigStore.get(service).jpegQuality)
            val record = Record(
                id = "${job.t}-${seq.incrementAndGet()}",
                t = job.t,
                kind = job.kind,
                items = meta.items,
                visible = meta.visible,
                uri = uri.toString(),
                file = name,
                click = meta.click,
                note = meta.note,
            )
            RecordStore.append(service, record)
            onDone(job, record, null)
        } catch (e: Exception) {
            onDone(job, null, "บันทึกภาพไม่สำเร็จ: ${e.message}")
        } finally {
            bitmap?.recycle()
        }
    }

    fun shutdown() {
        requests.shutdownNow()
        io.shutdown()
    }

    companion object {
        private const val MIN_GAP_MS = 400L
        private const val RETRY_DELAY_MS = 700L
        private const val TARGET_RETRY_DELAY_MS = 250L
        private const val MAX_ATTEMPTS = 4
        private const val META_TIMEOUT_MS = 3_000L
        private const val CALLBACK_TIMEOUT_MS = 12_000L
        private const val VALIDATION_TIMEOUT_MS = 1_500L
        private const val VALIDATION_MAX_NODES = 1500
    }
}
