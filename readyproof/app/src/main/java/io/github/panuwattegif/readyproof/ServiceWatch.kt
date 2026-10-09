package io.github.panuwattegif.readyproof

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The phone is left alone all day, so a stopped watcher must announce itself: every 15 minutes
 * this checks that the accessibility service is switched on and running. During opening hours a
 * stopped watcher sounds an alarm on every check until it runs again; outside opening hours it is
 * expected to be off (it switches itself off after the night's report so banking apps work).
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
            if (!cfg.inShopHours(LocalTime.now())) return Result.success()
            Notifier.status(
                ctx,
                "ร้านเปิดแล้ว แต่ ReadyProof ยังไม่ทำงาน — ไม่มีการแคปหลักฐาน",
                if (enabled) {
                    "เปิดแอป ReadyProof → หน้าแรก → ทำตามที่ขึ้นสีเหลือง/แดง (ปิด-เปิดสิทธิ์การช่วยเหลือพิเศษ 1 ครั้ง)"
                } else {
                    "กดปุ่มเพิ่มเสียงกับลดเสียงค้างไว้ 3 วินาที (ถ้าตั้งทางลัดไว้) หรือเปิดแอป ReadyProof → กด \"เปิดหน้าการช่วยเหลือพิเศษ\" → เปิดสวิตช์"
                },
                Notifier.ID_WATCH,
                alertAgain = true,
            )
        } catch (e: Exception) {
            Diagnostics.error(ctx, "serviceWatch", e)
        }
        return Result.success()
    }

    companion object {
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
