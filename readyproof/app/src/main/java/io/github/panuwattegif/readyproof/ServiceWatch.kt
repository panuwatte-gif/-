package io.github.panuwattegif.readyproof

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The phone is left alone all day, so a stopped watcher must announce itself: every 15 minutes
 * this checks that the accessibility service is switched on and running, and otherwise posts a
 * notification (at most every 2 hours) telling whoever sees the phone what to do.
 */
class ServiceWatchWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        try {
            val cfg = ConfigStore.get(ctx)
            if (!cfg.enabled) return Result.success()
            val enabled = ServiceStatus.isEnabled(ctx)
            if (enabled && ProofService.instance != null) {
                Notifier.cancel(ctx, Notifier.ID_WATCH)
                return Result.success()
            }
            val prefs = ConfigStore.prefs(ctx)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(KEY_LAST, 0L) < NOTIFY_GAP_MS) return Result.success()
            prefs.edit().putLong(KEY_LAST, now).apply()
            Notifier.status(
                ctx,
                "ReadyProof หยุดทำงาน — ไม่มีการแคปหลักฐาน",
                if (enabled) {
                    "มือถือปิดระบบแคปไว้ เปิดแอป ReadyProof → หน้าแรก → ทำตามที่ขึ้นสีเหลือง/แดง (ปิด-เปิดสิทธิ์การช่วยเหลือพิเศษ 1 ครั้ง)"
                } else {
                    "สิทธิ์ \"การช่วยเหลือพิเศษ\" ของ ReadyProof ถูกปิด เปิดแอป ReadyProof → กด \"เปิดหน้าการช่วยเหลือพิเศษ\" → เปิดสวิตช์"
                },
                Notifier.ID_WATCH,
            )
        } catch (e: Exception) {
            Diagnostics.error(ctx, "serviceWatch", e)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_LAST = "service_watch_last_notice"
        private const val NOTIFY_GAP_MS = 2 * 60 * 60_000L

        fun schedule(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "readyproof-service-watch",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ServiceWatchWorker>(15, TimeUnit.MINUTES).build(),
                )
            } catch (e: Exception) {
                Diagnostics.error(ctx, "serviceWatchSchedule", e)
            }
        }
    }
}
