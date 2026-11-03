package io.github.panuwattegif.readyproof

import android.content.Context
import android.util.AtomicFile
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.NightlyUploads
import io.github.panuwattegif.readyproof.core.OrderDay
import io.github.panuwattegif.readyproof.core.OrderDayCodec
import io.github.panuwattegif.readyproof.core.OrderRows
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.Shop
import io.github.panuwattegif.readyproof.core.ShopWorkbook
import io.github.panuwattegif.readyproof.core.Xlsx
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Each day's orders and their times, kept on the phone for good: one small file per shop and day. */
object OrderStore {
    private val lock = Any()
    private fun dir(ctx: Context, shopId: String) = File(ctx.applicationContext.filesDir, "orders/$shopId").apply { mkdirs() }
    private fun file(ctx: Context, shopId: String, date: LocalDate) = File(dir(ctx, shopId), "$date.json")
    /** A past day whose records hold no History: not looked at again. */
    private fun none(ctx: Context, shopId: String, date: LocalDate) = File(dir(ctx, shopId), "$date.none")

    fun save(ctx: Context, day: OrderDay) {
        val shopId = day.shopId ?: return
        synchronized(lock) { write(ctx, shopId, day) }
    }

    private fun write(ctx: Context, shopId: String, day: OrderDay) {
        val af = AtomicFile(file(ctx, shopId, day.date))
        val out = af.startWrite()
        try {
            out.write(OrderDayCodec.encode(day).toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            throw e
        }
        none(ctx, shopId, day.date).delete()
    }

    fun known(ctx: Context, shopId: String, date: LocalDate): Boolean = synchronized(lock) {
        file(ctx, shopId, date).exists() || none(ctx, shopId, date).exists()
    }

    fun markNone(ctx: Context, shopId: String, date: LocalDate) {
        synchronized(lock) { none(ctx, shopId, date).createNewFile() }
    }

    fun all(ctx: Context, shopId: String): List<OrderDay> = synchronized(lock) {
        dir(ctx, shopId).listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
            runCatching { OrderDayCodec.decode(AtomicFile(f).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        } ?: emptyList()
    }
}

/**
 * The shop's Google Sheet (owner, 3 Nov): after a day's History is read its orders are kept, the
 * whole workbook is rebuilt from every day kept, and the Drive worker sends it to replace the
 * sheet's content (Drive turns the .xlsx into the sheet; its link and sharing stay the same).
 */
object SheetSync {
    private const val MAX_TRIES = 3
    private val lock = Any()
    private fun pending(ctx: Context) = File(ctx.applicationContext.filesDir, "sheet").apply { mkdirs() }.let { File(it, "pending.xlsx") }

    fun url(shop: Shop): String? = shop.sheetId?.let { "https://docs.google.com/spreadsheets/d/$it/edit" }

    /** With each day's report. Never throws: the day's own files come first. */
    fun offer(ctx: Context, report: DailyReport, zone: ZoneId) {
        val shop = Shop.fromId(report.shopId) ?: return
        try {
            if (report.orders.isNotEmpty()) OrderStore.save(ctx, OrderRows.day(report, zone))
            backfill(ctx, shop, zone)
            if (shop.sheetId == null || !NightlyUploads.canRelease(report.date, LocalDateTime.now(zone))) return
            val days = OrderStore.all(ctx, shop.id)
            if (days.isEmpty()) return
            val bytes = Xlsx.write(ShopWorkbook.sheets(days, shop.label, LocalDateTime.now(zone), zone))
            synchronized(lock) {
                val af = AtomicFile(pending(ctx))
                val out = af.startWrite()
                try {
                    out.write(bytes)
                    af.finishWrite(out)
                } catch (e: Exception) {
                    af.failWrite(out)
                    throw e
                }
            }
            ConfigStore.prefs(ctx).edit().putInt(PREF_TRIES, 0)
                .putString(PREF_STATUS, "เตรียมตารางของวันที่ ${ReportText.date(report.date)} แล้ว (${days.size} วัน) — รอส่งเข้า Google Sheet").apply()
            DriveSync.schedule(ctx)
        } catch (e: Exception) {
            Diagnostics.error(ctx, "sheet", e)
            status(ctx, "เตรียมตาราง Google Sheet ไม่ได้: ${e.javaClass.simpleName} — รายงานในเครื่องยังครบ")
        } catch (e: OutOfMemoryError) {
            // never takes the end of the day down with it
            Diagnostics.note(ctx, "sheet: out of memory")
            status(ctx, "ตาราง Google Sheet ใหญ่เกินหน่วยความจำเครื่อง — รายงานในเครื่องยังครบ")
        }
    }

    /**
     * Days logged before this version kept its day files: built once from their records (the
     * phone never deletes them), so the sheet goes back to the first day.
     */
    private fun backfill(ctx: Context, shop: Shop, zone: ZoneId) {
        val cfg = ConfigStore.get(ctx)
        val today = LocalDate.now(zone)
        for (date in RecordStore.dates(ctx)) {
            if (!date.isBefore(today) || OrderStore.known(ctx, shop.id, date)) continue
            val records = RecordStore.loadRange(ctx, date.minusDays(1), date.plusDays(1))
            val r = ReportBuilder.build(records, date, zone, cfg, shop.id, DailyExport.reachedEnd(ctx, date, shop.id))
            if (r.orders.isEmpty()) OrderStore.markNone(ctx, shop.id, date) else OrderStore.save(ctx, OrderRows.day(r, zone))
        }
    }

    fun hasPending(ctx: Context): Boolean = pending(ctx).exists()

    /** Drops the waiting workbook only if it is still the one that was tried (a newer one stays). */
    private fun dropIfSame(f: File, bytes: ByteArray) = synchronized(lock) {
        if (f.exists() && f.readBytes().contentEquals(bytes)) f.delete()
    }

    /**
     * Run by the Drive worker with its signed-in connection. False: try again later. A refusal from
     * Google is tried [MAX_TRIES] times, then the workbook waits for the next night's; a lost
     * connection is tried again like the photos, without counting.
     */
    fun upload(ctx: Context, api: DriveApi): Boolean {
        val f = pending(ctx)
        val prefs = ConfigStore.prefs(ctx)
        val sheetId = ShopStore.get(ctx)?.sheetId
        val bytes = synchronized(lock) { if (f.exists()) f.readBytes() else null } ?: return true
        if (sheetId == null) {
            dropIfSame(f, bytes)
            return true
        }
        val tries = prefs.getInt(PREF_TRIES, 0)
        return try {
            api.replaceSpreadsheet(sheetId, bytes)
            dropIfSame(f, bytes)
            val now = LocalDateTime.now()
            prefs.edit().putInt(PREF_TRIES, 0)
                .putString(PREF_STATUS, "อัปเดต Google Sheet แล้ว ${ReportText.date(now.toLocalDate())} ${Parsers.hhmm(now.toLocalTime())}").apply()
            true
        } catch (e: java.io.IOException) {
            Diagnostics.error(ctx, "sheet upload", e)
            status(ctx, "ส่งเข้า Google Sheet ไม่สำเร็จ (เน็ตหลุด) — จะลองใหม่")
            false
        } catch (e: Exception) {
            Diagnostics.error(ctx, "sheet upload", e)
            val giveUp = tries + 1 >= MAX_TRIES
            if (giveUp) dropIfSame(f, bytes)
            prefs.edit().putInt(PREF_TRIES, if (giveUp) 0 else tries + 1).putString(PREF_STATUS, "ส่งเข้า Google Sheet ไม่สำเร็จ (" +
                (if (e is DriveApi.HttpError) "HTTP ${e.code}" else e.javaClass.simpleName) +
                (if (giveUp) ") — จะส่งใหม่พร้อมตารางของคืนถัดไป" else ") — จะลองใหม่")).apply()
            if (e is DriveApi.HttpError && e.code == 401) throw e
            giveUp
        }
    }

    fun statusText(ctx: Context): String = ConfigStore.prefs(ctx).getString(PREF_STATUS, null) ?: "ยังไม่ได้ส่ง"

    private fun status(ctx: Context, text: String) = ConfigStore.prefs(ctx).edit().putString(PREF_STATUS, text).apply()

    private const val PREF_TRIES = "sheet_tries"
    private const val PREF_STATUS = "sheet_status"
}
