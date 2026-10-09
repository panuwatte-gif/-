package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import io.github.panuwattegif.readyproof.core.Item
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** What gets stored next to a screenshot. */
data class CaptureMeta(
    val items: List<Item> = emptyList(),
    val visible: List<String> = emptyList(),
    val note: String? = null,
    /** Day the History tab showed, for History shots. */
    val historyDate: String? = null,
    /** Shop the phone is bound to; never changed afterwards (Drive routing depends on it). */
    val shopId: String? = null,
)

/**
 * Silent screenshots through the accessibility service (no flash, no sound, no prompt).
 * Taking a shot and saving it are separate steps so the caller can check, between the two,
 * that the screen did not move while the shot was taken (a shot that might miss the order
 * number is thrown away instead of saved).
 */
class CaptureManager(private val service: AccessibilityService) {
    private val mutex = Mutex()
    private val worker = Executors.newSingleThreadExecutor()
    private val seq = AtomicInteger()
    private var lastShotAt = 0L

    @Volatile
    var lastError: String? = null
        private set

    private sealed class Shot {
        class Ok(val bitmap: Bitmap) : Shot()
        class Fail(val code: Int, val message: String) : Shot()
    }

    /** A screenshot of the whole screen as it is now; null when Android refused (see [lastError]). */
    suspend fun take(): Bitmap? = mutex.withLock {
        for (attempt in 1..MAX_ATTEMPTS) {
            val wait = lastShotAt + MIN_GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            lastShotAt = SystemClock.uptimeMillis()
            when (val s = shootOnce()) {
                is Shot.Ok -> return@withLock s.bitmap
                is Shot.Fail -> {
                    lastError = s.message
                    if (s.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return@withLock null
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        null
    }

    private suspend fun shootOnce(): Shot = suspendCancellableCoroutine { cont ->
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val result = try {
                        val buffer = screenshot.hardwareBuffer
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        buffer.close()
                        if (copy != null) Shot.Ok(copy) else Shot.Fail(-1, "แปลงภาพหน้าจอไม่สำเร็จ")
                    } catch (e: Exception) {
                        Shot.Fail(-1, "แปลงภาพหน้าจอไม่สำเร็จ: ${e.message}")
                    }
                    if (cont.isActive) cont.resume(result) else (result as? Shot.Ok)?.bitmap?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Shot.Fail(errorCode, "แคปหน้าจอไม่สำเร็จ (รหัส $errorCode)"))
                }
            })
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Shot.Fail(-1, "แคปหน้าจอไม่สำเร็จ: ${e.message}"))
        }
    }

    /** Saves [bitmap] as a JPEG plus its log record, then recycles it. */
    suspend fun save(bitmap: Bitmap, kind: RecordKind, t: Long, meta: CaptureMeta): Record? = withContext(Dispatchers.IO) {
        try {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault())
            // A running number keeps names unique (Drive copies are keyed by name).
            val name = Naming.fileName(kind, meta.items, meta.visible, at).removeSuffix(".jpg") + "_${seq.incrementAndGet()}.jpg"
            val uri = MediaSaver.saveJpeg(service, bitmap, name, t, ConfigStore.get(service).jpegQuality)
            val record = Record(
                id = "$t-${seq.incrementAndGet()}",
                t = t,
                kind = kind,
                items = meta.items,
                visible = meta.visible,
                uri = uri.toString(),
                file = name,
                note = meta.note,
                shopId = meta.shopId,
                historyDate = meta.historyDate,
            )
            RecordStore.append(service, record)
            // Optional Drive copy; it never blocks or fails the local capture.
            DriveSync.offerRecord(service, record)
            record
        } catch (e: Exception) {
            lastError = "บันทึกภาพไม่สำเร็จ: ${e.message}"
            Diagnostics.error(service, "save", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun shutdown() {
        worker.shutdown()
    }

    companion object {
        private const val MIN_GAP_MS = 400L
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_ATTEMPTS = 4
    }
}
