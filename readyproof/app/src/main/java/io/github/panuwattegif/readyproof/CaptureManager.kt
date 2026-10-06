package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import io.github.panuwattegif.readyproof.core.Deduper
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.StatusRules
import io.github.panuwattegif.readyproof.core.TextNorm
import io.github.panuwattegif.readyproof.core.ProofValidation
import io.github.panuwattegif.readyproof.core.ValidatedTarget
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

class CaptureJob(val kind: RecordKind, val t: Long, val shopId: String? = null, val historyDate: String? = null) {
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
            val meta = job.awaitMeta(0)
            val requestedTargets = meta.items
            val before = if (job.kind in listOf(RecordKind.READY, RecordKind.DELAY, RecordKind.HISTORY)) {
                visibleEvidenceTargets(job.kind, requestedTargets)
            } else {
                requestedTargets.map { ValidatedTarget(it, "manual") }
            }

            if ((job.kind in listOf(RecordKind.READY, RecordKind.DELAY, RecordKind.HISTORY)) && before.isEmpty()) {
                if (attempt >= MAX_ATTEMPTS) {
                    onDone(job, null, "ยังถ่ายหลักฐาน ${job.kind} ไม่ได้ — จะคงออเดอร์ไว้เพื่อสแกนซ้ำ")
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
            val expired = AtomicBoolean(false)
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, io, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            if (expired.get()) {
                                screenshot.hardwareBuffer.close()
                                return
                            }
                            if (job.kind in listOf(RecordKind.READY, RecordKind.DELAY, RecordKind.HISTORY)) {
                                // One bitmap may prove many orders. Keep only targets that are still
                                // visibly present after Android produced the bitmap. Any target that
                                // dropped out remains pending in ProofService and is retried; it is
                                // never silently marked as having proof.
                                val after = ProofValidation.stableSubset(before, visibleEvidenceTargets(job.kind, before.map { it.item }))
                                if (after.isEmpty()) {
                                    runCatching { screenshot.hardwareBuffer.close() }
                                    retry.set(true)
                                    return
                                }
                                val afterKeys = after.mapNotNull { Deduper.keyOf(it) }
                                save(
                                    job,
                                    screenshot,
                                    meta.copy(
                                        items = after,
                                        dedupeKeys = afterKeys,
                                        toast = toastForValidated(job.kind, after),
                                    ),
                                )
                            } else {
                                save(job, screenshot, meta)
                            }
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
                expired.set(true)
                if (!callbackDelivered.get()) onDone(job, null, "แคปหน้าจอไม่ตอบสนองภายในเวลาที่กำหนด")
                return
            }
            if (!retry.get()) return

            if (attempt >= MAX_ATTEMPTS) {
                onDone(job, null, "หน้าจอเปลี่ยนระหว่างแคป — จะคงออเดอร์ไว้เพื่อสแกนซ้ำ")
                return
            }
            Thread.sleep(RETRY_DELAY_MS)
            attempt++
        }
    }

    /**
     * Return the subset of [targets] that are visibly provable on the current Grab screen.
     * READY only requires the target GF to be visible in the selected Ready tab. DELAY requires
     * the same card to show both the target GF and Grab's delayed text.
     */
    private fun visibleEvidenceTargets(kind: RecordKind, targets: List<Item>): List<ValidatedTarget> {
        if (targets.isEmpty()) return emptyList()
        val result = AtomicReference<List<ValidatedTarget>>(emptyList())
        val done = CountDownLatch(1)
        main.post {
            try {
                val cfg = ConfigStore.get(service)
                // Never validate a background Grab window while another app is on the screenshot.
                val root = service.rootInActiveWindow ?: return@post
                if (root.packageName?.toString() !in cfg.targetPackages) return@post
                result.set(ProofValidation.targets(kind, targets,
                    listOf(NodeSnapshot.capture(root, VALIDATION_MAX_NODES)), cfg))
            } catch (_: Exception) {
                result.set(emptyList())
            } finally {
                done.countDown()
            }
        }
        return if (done.await(VALIDATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result.get() else emptyList()
    }

    private fun toastForValidated(kind: RecordKind, items: List<Item>): String = when (kind) {
        RecordKind.READY -> "📸 READY: " + items.joinToString(", ") { it.gf }
        RecordKind.DELAY -> "📸 ล่าช้า: " + items.joinToString(", ") {
            it.gf + (it.delayMin?.let { m -> " ($m นาที)" } ?: "")
        }
        else -> "📸 บันทึกแล้ว"
    }

    private fun save(job: CaptureJob, shot: AccessibilityService.ScreenshotResult, validatedMeta: CaptureMeta? = null) {
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
            val meta = validatedMeta ?: job.awaitMeta(META_TIMEOUT_MS)
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(job.t), ZoneId.systemDefault())
            val name = (job.shopId?.let { "${it}_" } ?: "UNKNOWN_") + Naming.fileName(job.kind, meta.items, meta.visible, at).removeSuffix(".jpg") + "_${seq.incrementAndGet()}.jpg"
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
                shopId = job.shopId,
                historyDate = job.historyDate,
            )
            RecordStore.append(service, record)
            DriveSync.offerRecord(service, record)
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
