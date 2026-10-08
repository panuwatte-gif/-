package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import android.util.AtomicFile
import java.io.File

object DailyExport {
    fun captureTotals(ctx: Context, day: LocalDate, shopId: String?, roots: List<UiNode>) {
        val totals = HistoryTotalsParser.parseRoots(roots, ConfigStore.get(ctx)) ?: return
        ConfigStore.prefs(ctx).edit().putString("history_totals_${shopId ?: "UNKNOWN"}_$day",
            Json.write(linkedMapOf("completed" to totals.completed, "cancelled" to totals.cancelled, "total" to totals.total))).apply()
    }

    fun headerSummary(ctx: Context, report: DailyReport): String {
        val raw = ConfigStore.prefs(ctx).getString("history_totals_${report.shopId ?: "UNKNOWN"}_${report.date}", null)
            ?: return "ยอดหัวหน้า History: ยังอ่านไม่ได้ — ไม่ใช้จำนวนภาพแทนจำนวนออเดอร์"
        val m = Json.parseObject(raw)
        val completed = (m["completed"] as Number).toInt()
        val cancelled = (m["cancelled"] as Number).toInt()
        val total = (m["total"] as Number).toInt()
        val match = completed == report.completedSeen && cancelled == report.cancelledSeen && completed + cancelled == total
        return "ยอดหัวหน้า History: ทั้งหมด $total / สำเร็จ $completed / ยกเลิก $cancelled\n" +
            "อ่านรายออเดอร์: สำเร็จ ${report.completedSeen} / ยกเลิก ${report.cancelledSeen} — " +
            (if (match) "จำนวนตรงกัน" else "จำนวนยังไม่ตรง: อย่าถือว่ากวาดครบ")
    }

    fun summary(ctx: Context, report: DailyReport, zone: ZoneId) = ReportText.summary(report, zone) + "\n" + headerSummary(ctx, report) + "\n"
    fun reachedEnd(ctx: Context, date: LocalDate, shopId: String?) =
        ConfigStore.prefs(ctx).getBoolean("history_end_${shopId ?: "UNKNOWN"}_$date", false)

    /** Every pass exports what exists, including incomplete passes; never gate on proof equality. */
    fun save(ctx: Context, day: LocalDate, shopId: String?, reachedEnd: Boolean, failureReason: String? = null): DailyReport {
        ConfigStore.prefs(ctx).edit().putBoolean("history_end_${shopId ?: "UNKNOWN"}_$day", reachedEnd).apply()
        val zone = ZoneId.of("Asia/Bangkok")
        val records = RecordStore.loadRange(ctx, day.minusDays(1), day.plusDays(1))
        val report = ReportBuilder.build(records, day, zone, ConfigStore.get(ctx), shopId, reachedEnd)
        val text = summary(ctx, report, zone) + (failureReason?.let { "\nปัญหาการตรวจ History: $it\n" } ?: "")
        saveLocal(ctx, "${shopId ?: "UNKNOWN"}_summary-$day.txt", text)
        // Content-addressed outbox keeps each report revision; no stale report can overwrite a newer one.
        val manifest = Json.write(linkedMapOf(
            "schema" to 2, "shopId" to shopId, "date" to day.toString(),
            "historyFailure" to failureReason,
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
        if (ManualWorkflow.autoUpload && Shop.fromId(shopId) != null && NightlyUploads.canRelease(day, LocalDateTime.now(zone))) {
            // Durable immutable intent also recovers a process death before the outbox task starts.
            saveLocal(ctx, "${shopId}_batch-request-$day.json", Json.write(linkedMapOf(
                "shopId" to shopId, "date" to day.toString(), "text" to text, "manifest" to manifest)))
        }
        DriveSync.offerDailyBatch(ctx, day, shopId, records, text, manifest)
        return report
    }

    fun recoverBatches(ctx: Context) {
        val shop = ShopStore.get(ctx) ?: return
        File(ctx.filesDir, "reports").listFiles()?.filter {
            it.name.startsWith("${shop.id}_batch-request-") && it.name.endsWith(".json")
        }?.forEach { f -> runCatching {
            val request = Json.parseObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
            if (request.str("shopId") != shop.id) return@runCatching
            val day = LocalDate.parse(request.str("date"))
            val manifest = request.str("manifest") ?: return@runCatching
            val text = request.str("text") ?: return@runCatching
            val records = Json.parseObject(manifest).objList("records").mapNotNull { RecordCodec.decode(Json.write(it)) }
            DriveSync.offerDailyBatch(ctx, day, shop.id, records, text, manifest)
        } }
    }

    private fun saveLocal(ctx: Context, name: String, text: String) {
        val folder = File(ctx.filesDir, "reports").apply { mkdirs() }
        val af = AtomicFile(File(folder, name))
        val out = af.startWrite()
        try { out.write(text.toByteArray()); af.finishWrite(out) }
        catch (e: Exception) { af.failWrite(out); throw e }
    }
}
