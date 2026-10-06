package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.*
import java.time.LocalDate
import java.time.ZoneId
import android.util.AtomicFile
import java.io.File

object DailyExport {
    fun reachedEnd(ctx: Context, date: LocalDate, shopId: String?) =
        ConfigStore.prefs(ctx).getBoolean("history_end_${shopId ?: "UNKNOWN"}_$date", false)

    /** Every pass exports what exists, including incomplete passes; never gate on proof equality. */
    fun save(ctx: Context, day: LocalDate, shopId: String?, reachedEnd: Boolean): DailyReport {
        ConfigStore.prefs(ctx).edit().putBoolean("history_end_${shopId ?: "UNKNOWN"}_$day", reachedEnd).apply()
        val zone = ZoneId.of("Asia/Bangkok")
        val records = RecordStore.loadRange(ctx, day.minusDays(1), day.plusDays(1))
        val report = ReportBuilder.build(records, day, zone, ConfigStore.get(ctx), shopId, reachedEnd)
        val text = ReportText.summary(report, zone)
        saveLocal(ctx, "${shopId ?: "UNKNOWN"}_summary-$day.txt", text)
        // Content-addressed outbox keeps each report revision; no stale report can overwrite a newer one.
        DriveSync.offerBytes(ctx, shopId, "summary-$day.txt", "text/plain", text.toByteArray())
        val manifest = Json.write(linkedMapOf(
            "schema" to 2, "shopId" to shopId, "date" to day.toString(),
            "complete" to report.complete, "historyDateVerified" to report.historyDateVerified,
            "sweepReachedEnd" to reachedEnd, "observedTotalOrders" to report.historyOrders,
            "totalOrders" to if (report.complete) report.historyOrders else null,
            "grabDelayed" to report.delayed, "readyEvidenceCount" to report.withEvidence.size,
            "actualDelayed" to report.actualDelayed, "grabPercentProvisional" to report.pct(report.delayed),
            "actualPercentProvisional" to report.pct(report.actualDelayed),
            "pendingReadyGF" to report.pendingReadyGfs,
            "missingReadyInstances" to report.missingReadyInstances,
            "missingHistoryInstances" to report.missingHistoryInstances,
            "unknownHistoryInstances" to report.unknownHistoryInstances,
            "cases" to report.cases.map { c -> linkedMapOf(
                "gf" to c.gf, "finishedAt" to c.doneAt?.toString(), "matchStatus" to c.matchStatus,
                "delayMinutes" to c.delayMin, "readyRecordIds" to c.evidence.map { it.record.id },
                "delayRecordIds" to c.delayShots.map { it.id }) },
            "records" to records.filter { it.shopId == shopId && RecordStore.dateOf(it.t) == day }
                .map { Json.parseObject(RecordCodec.encode(it)) }
        ))
        saveLocal(ctx, "${shopId ?: "UNKNOWN"}_manifest-$day.json", manifest)
        DriveSync.offerBytes(ctx, shopId, "manifest-$day.json", "application/json", manifest.toByteArray())
        return report
    }

    private fun saveLocal(ctx: Context, name: String, text: String) {
        val folder = File(ctx.filesDir, "reports").apply { mkdirs() }
        val af = AtomicFile(File(folder, name))
        val out = af.startWrite()
        try { out.write(text.toByteArray()); af.finishWrite(out) }
        catch (e: Exception) { af.failWrite(out); throw e }
    }
}
