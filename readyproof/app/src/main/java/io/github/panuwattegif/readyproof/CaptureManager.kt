package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * prompt) and saves them with a log record. Requests are serialised because Android refuses
 * screenshots requested too close together.
 */
class CaptureManager(
    private val service: AccessibilityService,
    private val onDone: (job: CaptureJob, record: Record?, error: String?) -> Unit,
) {
    private val requests = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val seq = AtomicInteger()

    @Volatile
    private var lastRequestAt = 0L

    fun submit(job: CaptureJob) {
        try {
            requests.execute { request(job, 1) }
        } catch (e: Exception) {
            onDone(job, null, "ระบบกำลังปิด")
        }
    }

    private fun request(job: CaptureJob, attempt: Int) {
        val wait = lastRequestAt + MIN_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = SystemClock.uptimeMillis()
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, io, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) = save(job, screenshot)

                override fun onFailure(errorCode: Int) {
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_ATTEMPTS) {
                        retryLater(job, attempt + 1)
                    } else {
                        onDone(job, null, "แคปหน้าจอไม่สำเร็จ (รหัส $errorCode)")
                    }
                }
            })
        } catch (e: Exception) {
            onDone(job, null, "แคปหน้าจอไม่สำเร็จ: ${e.message}")
        }
    }

    private fun retryLater(job: CaptureJob, attempt: Int) {
        try {
            requests.execute {
                Thread.sleep(RETRY_DELAY_MS)
                request(job, attempt)
            }
        } catch (e: Exception) {
            onDone(job, null, "ระบบกำลังปิด")
        }
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
        private const val MAX_ATTEMPTS = 4
        private const val META_TIMEOUT_MS = 3_000L
    }
}
