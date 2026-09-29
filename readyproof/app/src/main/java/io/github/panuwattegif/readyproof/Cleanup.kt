package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.Config
import java.time.LocalDate

/** Once a day: drop screenshots older than the retention setting so the phone never fills up. */
object Cleanup {
    private const val KEY = "last_cleanup_day"

    /** Log lines are tiny, so they are kept longer than images (the report still lists the case). */
    private const val MIN_LOG_DAYS = 90L

    fun runIfDue(ctx: Context, cfg: Config) {
        val today = LocalDate.now()
        val prefs = ConfigStore.prefs(ctx)
        if (prefs.getString(KEY, null) == today.toString()) return
        prefs.edit().putString(KEY, today.toString()).apply()
        try {
            MediaSaver.deleteOlderThan(ctx, System.currentTimeMillis() - cfg.retentionDays * 86_400_000L)
            RecordStore.deleteBefore(ctx, today.minusDays(maxOf(cfg.retentionDays.toLong(), MIN_LOG_DAYS)))
        } catch (e: Exception) {
            Diagnostics.error(ctx, "cleanup", e)
        }
    }
}
