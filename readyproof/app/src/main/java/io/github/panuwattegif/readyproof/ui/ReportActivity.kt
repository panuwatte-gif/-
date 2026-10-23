package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.ShopStore
import io.github.panuwattegif.readyproof.DailyExport
import io.github.panuwattegif.readyproof.Diagnostics
import io.github.panuwattegif.readyproof.DriveSync
import io.github.panuwattegif.readyproof.EvidenceProvider
import io.github.panuwattegif.readyproof.GrabPack
import io.github.panuwattegif.readyproof.MediaSaver
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.DelayCase
import io.github.panuwattegif.readyproof.core.EvidenceSet
import io.github.panuwattegif.readyproof.core.GrabLimits
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.Verdict
import java.time.LocalDate
import java.time.ZoneId

/**
 * End of day: Grab's delayed orders (read from the History list) matched with the Ready tab
 * screenshots. Delayed orders without a READY match remain first-class report cases and their
 * DELAY screenshot is still exported, so a real shop delay can never disappear from the report.
 */
class ReportActivity : Activity() {
    private var legacy = false
    private var date: LocalDate = LocalDate.now()
    private lateinit var thumbs: Thumbs
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs = Thumbs(this)
    }

    override fun onResume() {
        super.onResume()
        RecordStore.addListener(refresh)
        render()
    }

    override fun onPause() {
        RecordStore.removeListener(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        thumbs.close()
        super.onDestroy()
    }

    private fun render() {
        val zone = ZoneId.systemDefault()
        val cfg = ConfigStore.get(this)
        val records = RecordStore.loadRange(this, date.minusDays(1), date.plusDays(1))
        val shopId = if (legacy) null else ShopStore.get(this)?.id
        val report = ReportBuilder.build(records, date, zone, cfg, shopId, DailyExport.reachedEnd(this, date, shopId))
        val col = Ui.page(this, "รายงานออเดอร์ล่าช้า", "จับคู่กับภาพหลักฐานให้อัตโนมัติ")

        val nav = Ui.row(this)
        nav.addView(Ui.smallButton(this, "◀ วันก่อน") {
            date = date.minusDays(1)
            render()
        })
        nav.addView(TextView(this).apply {
            text = ReportText.date(date)
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(Ui.smallButton(this, "วันถัดไป ▶") {
            if (date.isBefore(LocalDate.now())) {
                date = date.plusDays(1)
                render()
            }
        })
        col.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@ReportActivity, 10)
        })

        Ui.button(col, if (legacy) "ดูร้านที่เลือก" else "ดูข้อมูลเดิมที่ยังไม่ระบุร้าน", filled = false) {
            legacy = !legacy
            render()
        }
        summaryCard(col, report, zone)
        if (report.cases.isEmpty()) {
            val c = Ui.card(col)
            Ui.text(c, if (!report.complete) "ยังตรวจ History ไม่ครบ — ยังสรุปว่าไม่มีออเดอร์ล่าช้าไม่ได้"
                else "ไม่มีออเดอร์ล่าช้า", 15f, bold = true)
        } else {
            val sets = report.sets().associateBy { it.case }
            for (v in Verdict.entries) {
                val cases = report.cases.filter { it.verdict == v }
                if (cases.isEmpty()) continue
                Ui.text(col, groupTitle(v) + " ${cases.size} เคส", 17f, colorOf(v), bold = true, topDp = 6)
                explanation(v)?.let { Ui.text(col, it, 13f, Ui.MUTED) }
                cases.forEach { caseCard(col, it, sets[it], report, zone) }
            }
        }
    }

    private fun groupTitle(v: Verdict): String = when (v) {
        Verdict.IN_TIME -> "✅ " + v.label + " — ส่ง Grab ได้"
        Verdict.LATE -> "❌ " + v.label + " — ห้ามส่ง Grab"
        Verdict.UNCHECKED -> "⚠️ " + v.label + " — ตรวจเองก่อนส่ง"
        Verdict.NO_EVIDENCE -> "❓ " + v.label
    }

    private fun explanation(v: Verdict): String? = when (v) {
        Verdict.IN_TIME -> null
        Verdict.LATE -> "เวลานับถอยหลังในแท็บกำลังเตรียมหมด (0:00) ก่อนออเดอร์เข้าแท็บ Ready — ร้านกดช้าจริง แม้จะมีภาพ Ready"
        Verdict.UNCHECKED -> "มีภาพในแท็บ Ready แต่แอปไม่ได้อ่านเวลานับถอยหลังของออเดอร์นี้ หรือไม่ได้ดูจออยู่ตอนเวลาหมด " +
            "(เช่นช่วงที่แอปปิดหรือ Grab ไม่อยู่บนจอ) จึงไม่ตัดสินจากการเดา"
        Verdict.NO_EVIDENCE -> "ไม่ได้แปลว่าร้านช้าจริง: ไม่มีภาพในแท็บ Ready ของออเดอร์นี้ (ไม่ได้ผ่านแท็บ Ready หรือระบบจับไม่ได้)"
    }

    private fun colorOf(v: Verdict): Int = when (v) {
        Verdict.IN_TIME -> Ui.GREEN
        Verdict.LATE -> Ui.RED
        Verdict.UNCHECKED -> Ui.AMBER
        Verdict.NO_EVIDENCE -> Ui.MUTED
    }

    private fun summaryCard(col: LinearLayout, r: DailyReport, zone: ZoneId) {
        val s = Ui.card(col)
        Ui.text(s, "ร้าน: " + (io.github.panuwattegif.readyproof.core.Shop.fromId(r.shopId)?.label ?: "UNKNOWN / ข้อมูลเดิม"), 16f, bold = true)
        Ui.text(s, if (r.complete) "COMPLETE (ตามรายการที่อ่านได้)" else "INCOMPLETE / PROVISIONAL — จำนวนทั้งวันยัง UNKNOWN", 14f, Ui.AMBER)
        Ui.text(s, "History ขาดภาพ ${r.missingHistoryInstances.size} · Instance UNKNOWN ${r.unknownHistoryInstances.size} · วันที่ " +
            (if (r.historyDateVerified) "ยืนยันจากหน้าจอ" else "UNKNOWN"), 13f, Ui.MUTED)
        Ui.button(s, "ดูรายละเอียดความครบของหลักฐาน", filled = false) {
            Ui.alert(this, "ข้อมูลการเก็บภาพ — ไม่ใช่รายการล่าช้า",
                "Ready รอภาพ: " + r.pendingReadyGfs.joinToString().ifEmpty { "-" } +
                    "\nCompleted ขาด Ready: " + r.missingReadyInstances.joinToString().ifEmpty { "-" } +
                    "\nHistory ขาดภาพ: " + r.missingHistoryInstances.joinToString().ifEmpty { "-" })
        }
        Ui.text(
            s,
            "History: ${r.historyOrders} ออเดอร์ · เสร็จ ${r.completedSeen} · ยกเลิก ${r.cancelledSeen}",
            15f,
            bold = true,
        )
        if (r.grabCompleted != null) {
            Ui.text(
                s,
                "ยอดจาก Grab (หัวหน้าประวัติ): เสร็จ ${r.grabCompleted} · ยกเลิก ${r.grabCancelled ?: 0} → " +
                    if (r.historyMatchesGrab == true) "อ่านครบ ✓" else "⚠ อ่านได้ไม่ครบ",
                14f, if (r.historyMatchesGrab == true) Ui.GREEN else Ui.AMBER,
            )
        } else {
            Ui.text(s, "ยอดจาก Grab (หัวหน้าประวัติ): ยังอ่านไม่ได้", 13f, Ui.MUTED)
        }
        Ui.text(
            s,
            "Ready เห็น ${r.readySeenOrders} · มีภาพ ${r.readyOrders} · Pending ${r.pendingReadyProof} · " +
                "Completed ${r.completedSeen}: " + (if (r.readyVsCompletedMatch) "MATCH" else "MISMATCH"),
            14f,
            if (r.readyVsCompletedMatch) Ui.GREEN else Ui.AMBER,
            topDp = 2,
        )
        Ui.text(s, "ล่าช้าที่อ่านพบ: ${r.delayed}" + if (!r.complete) " · ยังไม่ยืนยันทั้งวัน" else "", 16f, bold = true, topDp = 6)
        Ui.text(s, "✅ ${Verdict.IN_TIME.label}: ${r.inTime.size}", 15f, Ui.GREEN, topDp = 2)
        Ui.text(s, "❌ ${Verdict.LATE.label}: ${r.late.size}", 15f, Ui.RED)
        Ui.text(s, "⚠️ ${Verdict.UNCHECKED.label}: ${r.unchecked.size}", 15f, Ui.AMBER)
        Ui.text(s, "❓ ${Verdict.NO_EVIDENCE.label}: ${r.noEvidence.size}", 15f, Ui.MUTED)
        Ui.text(s, "% ล่าช้าแบบ Grab = ${r.delayed} / ${r.base} = ${ReportText.pct(r.grabPct)}", 15f, topDp = 4)
        Ui.text(
            s,
            "% ที่ร้านควรได้ = (${r.delayed} − ${r.inTime.size}) / ${r.base} = ${ReportText.pct(r.realPct)}",
            16f, bold = true,
        )
        if (r.historyOrders == 0) {
            Ui.text(
                s,
                "ยังไม่มีข้อมูลจาก History ของวันนี้ — ระบบจะเข้า History เองหลังปิดร้าน หรือกด \"กวาด History ตอนนี้\" ที่หน้าหลักเพื่อทดสอบ",
                13f, Ui.AMBER, topDp = 8,
            )
        } else if (!r.readyVsCompletedMatch) {
            Ui.text(
                s,
                "⚠ จำนวน Ready กับ Completed ต่างกัน — ใช้รายออเดอร์ตรวจความครบ รูปที่มีส่งได้เสมอ",
                13f, Ui.AMBER, topDp = 6,
            )
        }

        // One image per order (Ready photo + History row), at the size Grab accepts.
        val inTime = r.sets(Verdict.IN_TIME)
        Ui.button(s, "📤 ส่งหลักฐานกดทัน ${inTime.size} ออเดอร์ ให้ Grab") {
            sendToGrab(r, inTime, emptyList(), "ส่งหลักฐานกดทัน ${inTime.size} ออเดอร์")
        }
        val sets = r.sets()
        val delayOnly = r.withoutEvidence.filter { it.delayShot != null }
        Ui.button(s, "แชร์ทุกเคสล่าช้า ${r.cases.size} เคส (รวมเคสไม่มีหลักฐาน)", filled = false) {
            sendToGrab(r, sets, delayOnly, "ส่งหลักฐาน ${r.cases.size} เคส")
        }
        Ui.text(s, "ภาพละ 1 ออเดอร์ (หน้าพร้อมจัดส่งคู่กับหน้าประวัติ) ไม่เกิน 250 KB · ส่งครั้งละไม่เกิน 15 ภาพ / 3 MB", 12f, Ui.MUTED, topDp = 2)

        if (r.withoutEvidence.any { it.delayShot != null }) {
            Ui.text(
                s,
                "⚠ เคสที่ไม่มีภาพ Ready จะส่งภาพ DELAY ขาเดียวไปด้วย และยังนับเป็น Grab Delayed ตามเดิม",
                13f, Ui.AMBER, topDp = 4,
            )
        }
        if (r.cases.any { it.delayShot == null }) {
            Ui.text(s, "⚠ บางเคสยังไม่มีภาพ DELAY ที่ใช้ได้ — ระบบจะกวาด History ซ้ำอัตโนมัติ", 13f, Ui.AMBER, topDp = 4)
        }
        Ui.button(s, "📋 คัดลอกสรุป (ไว้วางใน LINE)", filled = false) { Share.copy(this, ReportText.summary(r, zone)) }
        Ui.button(s, "📊 ส่งออกตาราง CSV", filled = false) { exportCsv(r, zone) }
        Ui.button(s, "🕒 ส่งออกเวลาทุกออเดอร์ (CSV)", filled = false) { exportTimes(r, zone) }
        Ui.button(s, "📄 แชร์ไฟล์สรุป + ไฟล์รายละเอียดของวัน (4 ไฟล์)", filled = false) { shareDayFiles(r, zone) }
        Ui.button(s, "📤 แชร์ READY + HISTORY ทุกภาพของวัน (แม้ยังไม่ครบ)", filled = false) {
            val shots = RecordStore.load(this, r.date).filter { it.shopId == r.shopId && it.uri != null }
            Share.images(this, shots.mapNotNull { shot -> shot.uri?.let(Uri::parse) })
        }
    }

    /**
     * Grab-ready images ([GrabPack]) shared in sends that fit what Grab accepted ([GrabLimits]);
     * more than one send: the owner picks them one after another.
     */
    private fun sendToGrab(report: DailyReport, sets: List<EvidenceSet>, delayOnly: List<DelayCase>, title: String) {
        val files = try {
            GrabPack.build(this, report.date, sets, delayOnly, ZoneId.systemDefault())
        } catch (e: Exception) {
            Ui.alert(this, "ทำภาพสำหรับส่ง Grab ไม่สำเร็จ", e.message ?: "")
            return
        }
        if (files.isEmpty()) {
            Ui.toast(this, "ไม่มีภาพให้ส่ง")
            return
        }
        val uris = files.map { FileProvider.getUriForFile(this, "$packageName.exports", it) }
        val batches = GrabLimits.batches(files.map { it.length() })
        if (batches.size == 1) {
            Share.images(this, uris, title)
            return
        }
        val labels = batches.mapIndexed { i, b ->
            "ชุดที่ ${i + 1}: ${b.size} ภาพ (" + String.format(java.util.Locale.ROOT, "%.1f", b.sumOf { files[it].length() } / 1_000_000.0) + " MB)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ส่งทีละชุด (Grab รับครั้งละไม่เกิน ${GrabLimits.MAX_FILES_PER_SEND} ภาพ)")
            .setItems(labels) { _, i -> Share.images(this, batches[i].map { uris[it] }, "$title ชุดที่ ${i + 1}") }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun delayName(report: DailyReport, c: DelayCase): String {
        val repeated = report.cases.count { it.gf == c.gf } > 1
        val doneAt = c.doneAt
        val tag = if (repeated && doneAt != null) {
            c.gf + "_" + Parsers.pad2(doneAt.hour) + Parsers.pad2(doneAt.minute)
        } else {
            c.gf
        }
        return Naming.setName(tag, "DELAY")
    }

    private fun evidenceUri(r: Record, name: String): Uri? = try {
        r.uri?.let { EvidenceProvider.uriFor(Uri.parse(it), name) }
    } catch (e: Exception) {
        null
    }

    private fun caseCard(col: LinearLayout, c: DelayCase, set: EvidenceSet?, report: DailyReport, zone: ZoneId) {
        val card = Ui.card(col)
        val head = c.gf + " ล่าช้า " + (c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        Ui.text(card, head, 16f, colorOf(c.verdict), bold = true)
        c.doneAt?.let { Ui.text(card, "เสร็จสมบูรณ์ " + Parsers.hhmm(it.toLocalTime()), 14f, Ui.MUTED) }
        c.readyShot?.let {
            Ui.text(card, "อยู่ในแท็บ Ready ตั้งแต่ " + ReportText.timeSec(c.readyAt ?: it.t, zone) + (it.status?.let { s -> " · $s" } ?: ""), 14f, topDp = 4)
        }
        val p = c.prep
        when {
            c.readyShot == null -> {}
            p == null -> Ui.text(card, "ไม่มีข้อมูลหน้ากำลังเตรียมของออเดอร์นี้", 13f, Ui.AMBER)
            p.ranOut -> Ui.text(card, "หน้ากำลังเตรียมขึ้น 0:00 (หมดเวลา) ตอน " + ReportText.timeSec(p.readAt, zone) +
                " — ยังไม่ได้กด", 14f, Ui.RED)
            else -> Ui.text(
                card,
                "ต้องกดภายใน " + ReportText.timeSec(p.deadline, zone) + " (หน้ากำลังเตรียมเหลือ " + p.countdown + " ตอน " +
                    ReportText.timeSec(p.readAt, zone) + ")",
                14f, colorOf(c.verdict),
            )
        }
        c.pressedAt?.let { Ui.text(card, "กด Ready เวลา " + ReportText.time(it, zone), 13f, Ui.MUTED) }
        if (!c.timeline.empty) Ui.text(card, "⏱ " + ReportText.timelineText(c.timeline, c.doneAt, zone), 13f, Ui.MUTED, topDp = 2)
        if (c.matchStatus == "UNKNOWN_INSTANCE") Ui.text(card, "UNKNOWN: ยังยืนยัน instance ไม่ได้ ห้ามใช้ GF อย่างเดียวจับคู่", 13f, Ui.AMBER)
        if (!c.hasEvidence) Ui.text(card, "ไม่พบภาพในแท็บ Ready ของเลขนี้ก่อนเวลาเสร็จ — เก็บเคสนี้ไว้ ไม่ตัดออก", 13f, Ui.RED, topDp = 2)
        if (c.delayShot == null) Ui.text(card, "ยังไม่มีภาพหน้า History ของออเดอร์นี้", 13f, Ui.AMBER, topDp = 2)
        val shots = ArrayList<Pair<Record, String>>()
        c.prep?.overdueShot?.let { shots += it to "กำลังเตรียม 0:00" }
        c.readyShot?.let { shots += it.record to (set?.readyName ?: "READY") }
        c.delayShot?.let { shots += it to (set?.delayName ?: delayName(report, c)) }
        thumbRow(card, shots)
    }

    private fun thumbRow(parent: LinearLayout, shots: List<Pair<Record, String>>) {
        if (shots.isEmpty()) return
        val scroll = HorizontalScrollView(this)
        val row = Ui.row(this)
        for ((r, caption) in shots) {
            val cell = Ui.column(this)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = Ui.rounded(this@ReportActivity, Ui.FIELD, 6f, Ui.LINE)
                contentDescription = caption
                setOnClickListener { r.uri?.let { Share.view(this@ReportActivity, Uri.parse(it)) } }
            }
            cell.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 190)))
            cell.addView(TextView(this).apply {
                text = caption.removeSuffix(".jpg")
                textSize = 11f
                setTextColor(Ui.MUTED)
            })
            row.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = Ui.dp(this@ReportActivity, 10) })
            thumbs.into(iv, r.uri)
        }
        scroll.addView(row)
        parent.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@ReportActivity, 8)
        })
    }

    private fun exportCsv(r: DailyReport, zone: ZoneId) {
        try {
            val bytes = ReportText.csv(r, zone).toByteArray(Charsets.UTF_8)
            val uri = MediaSaver.saveDownload(this, "readyproof-${r.date}.csv", "text/csv", bytes)
            Ui.toast(this, "บันทึกไว้ที่ Download/ReadyProof แล้ว")
            Share.file(this, uri, "text/csv", "ส่งไฟล์ตาราง")
        } catch (e: Exception) {
            Ui.alert(this, "ส่งออกไม่สำเร็จ", e.message ?: "")
        }
    }

    /** Every order's times (Preparing, pressed, driver at the shop, picked up, done), to compare with Grab's. */
    private fun exportTimes(r: DailyReport, zone: ZoneId) {
        try {
            val bytes = ReportText.timelineCsv(r, zone).toByteArray(Charsets.UTF_8)
            val uri = MediaSaver.saveDownload(this, "${r.shopId ?: "UNKNOWN"}_times-${r.date}.csv", "text/csv", bytes)
            Ui.toast(this, "บันทึกไว้ที่ Download/ReadyProof แล้ว")
            Share.file(this, uri, "text/csv", "ส่งไฟล์เวลาทุกออเดอร์")
        } catch (e: Exception) {
            Ui.alert(this, "ส่งออกไม่สำเร็จ", e.message ?: "")
        }
    }

    /**
     * The day's summary plus the two files needed to find out why an order has no photo: the
     * manifest (every order seen / photographed, with times) and the diagnostics (what the phone
     * did that day). One tap, so the owner can put all three on Drive by hand.
     */
    private fun shareDayFiles(r: DailyReport, zone: ZoneId) {
        try {
            val shop = r.shopId ?: "UNKNOWN"
            val uris = ArrayList<Uri>()
            uris += MediaSaver.saveDownload(this, "${shop}_summary-${r.date}.txt", "text/plain",
                ReportText.summary(r, zone).toByteArray(Charsets.UTF_8))
            // Written by the closing History pass; missing until the first pass of that day.
            val manifest = java.io.File(java.io.File(filesDir, "reports"), "${shop}_manifest-${r.date}.json")
            if (manifest.exists()) {
                uris += MediaSaver.saveDownload(this, manifest.name, "application/json", manifest.readBytes())
            }
            uris += MediaSaver.saveDownload(this, "${shop}_diagnostics-${r.date}.txt", "text/plain",
                Diagnostics.daily(this, r.date).toByteArray(Charsets.UTF_8))
            uris += MediaSaver.saveDownload(this, "${shop}_times-${r.date}.csv", "text/csv",
                ReportText.timelineCsv(r, zone).toByteArray(Charsets.UTF_8))
            Ui.toast(this, "บันทึกไว้ที่ Download/ReadyProof แล้ว ${uris.size} ไฟล์")
            Share.files(this, uris, "ส่งไฟล์สรุปและรายละเอียด")
        } catch (e: Exception) {
            Ui.alert(this, "แชร์ไม่ได้", e.message ?: "")
        }
    }
}
