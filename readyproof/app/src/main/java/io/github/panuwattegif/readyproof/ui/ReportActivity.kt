package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.ShopStore
import io.github.panuwattegif.readyproof.DailyExport
import io.github.panuwattegif.readyproof.DriveSync
import io.github.panuwattegif.readyproof.EvidenceProvider
import io.github.panuwattegif.readyproof.MediaSaver
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.DelayCase
import io.github.panuwattegif.readyproof.core.EvidenceSet
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
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
            if (report.withEvidence.isNotEmpty()) {
                Ui.text(col, "✅ จับคู่ได้ ${report.withEvidence.size} เคส", 17f, Ui.GREEN, bold = true)
                report.withEvidence.forEach { caseCard(col, it, sets[it], report, zone) }
            }
            if (report.withoutEvidence.isNotEmpty()) {
                Ui.text(col, "❌ จับคู่ไม่ได้ ${report.withoutEvidence.size} เคส", 17f, Ui.RED, bold = true)
                report.withoutEvidence.forEach { caseCard(col, it, sets[it], report, zone) }
            }
        }
    }

    private fun summaryCard(col: LinearLayout, r: DailyReport, zone: ZoneId) {
        val s = Ui.card(col)
        Ui.text(s, DailyExport.headerSummary(this, r), 14f, Ui.MUTED)
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
        Ui.text(
            s,
            "Ready เห็น ${r.readySeenOrders} · มีภาพ ${r.readyOrders} · Pending ${r.pendingReadyProof} · " +
                "Completed ${r.completedSeen}: " + (if (r.readyVsCompletedMatch) "MATCH" else "MISMATCH"),
            14f,
            if (r.readyVsCompletedMatch) Ui.GREEN else Ui.AMBER,
            topDp = 2,
        )
        Ui.text(s, "ล่าช้าที่อ่านพบ: ${r.delayed}" + if (!r.complete) " · ยังไม่ยืนยันทั้งวัน" else "", 16f, bold = true, topDp = 6)
        Ui.text(s, "✅ มีภาพในแท็บ Ready (กดเสร็จแล้ว): ${r.withEvidence.size}", 15f, Ui.GREEN, topDp = 2)
        Ui.text(s, "❌ ไม่มีภาพ Ready / เหลือล่าช้าตามหลักฐาน: ${r.withoutEvidence.size}", 15f, Ui.RED, topDp = 2)
        Ui.text(
            s,
            "Delay% จาก History: Grab ${ReportText.pct(r.pct(r.delayed))} · Actual ${ReportText.pct(r.pct(r.actualDelayed))}",
            14f,
            Ui.MUTED,
            topDp = 4,
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

        val sets = r.sets()
        val unmatchedDelayFiles = r.withoutEvidence.count { it.delayShot != null }
        val files = sets.sumOf { if (it.delay != null) 2L else 1L } + unmatchedDelayFiles
        Ui.button(s, "📤 แชร์หลักฐาน ${r.cases.size} เคส ($files ไฟล์)") { shareEvidence(r, sets) }

        if (r.withoutEvidence.any { it.delayShot != null }) {
            Ui.text(
                s,
                "⚠ เคสที่ไม่มีภาพ Ready จะส่งภาพ DELAY ขาเดียวไปด้วย และยังนับเป็น Grab Delayed ตามเดิม",
                13f, Ui.AMBER, topDp = 4,
            )
        }
        if (r.cases.any { it.delayShot == null }) {
            Ui.text(s, "⚠ บางเคสยังไม่มีภาพ DELAY ที่ใช้ได้ — กลับไปกดกวาด History ใหม่ได้", 13f, Ui.AMBER, topDp = 4)
        }
        Ui.button(s, "📋 คัดลอกสรุป (ไว้วางใน LINE)", filled = false) { Share.copy(this, DailyExport.summary(this, r, zone)) }
        Ui.button(s, "📊 ส่งออกตาราง CSV", filled = false) { exportCsv(r, zone) }
        Ui.button(s, "📄 แชร์สรุปวันนี้เป็นไฟล์", filled = false) {
            runCatching {
                Share.file(this, MediaSaver.saveDownload(this, "${r.shopId ?: "UNKNOWN"}_summary-${r.date}.txt",
                    "text/plain", DailyExport.summary(this, r, zone).toByteArray()), "text/plain", "ส่งสรุปวันนี้")
            }.onFailure { Ui.alert(this, "แชร์ไม่ได้", it.message ?: "") }
        }
        Ui.button(s, "📤 แชร์ READY + HISTORY ทุกภาพของวัน (แม้ยังไม่ครบ)", filled = false) {
            val shots = RecordStore.load(this, r.date).filter { it.shopId == r.shopId && it.uri != null }
            Share.images(this, shots.mapNotNull { shot -> shot.uri?.let(Uri::parse) })
        }
    }

    /**
     * Send every delayed case that has an image. Matched cases contribute READY + DELAY; unmatched
     * cases still contribute DELAY alone instead of being silently dropped from the Drive handoff.
     */
    private fun shareEvidence(report: DailyReport, sets: List<EvidenceSet>) {
        val uris = ArrayList<Uri>()
        for (set in sets) {
            evidenceUri(set.ready, set.readyName)?.let { uris += it }
            val delay = set.delay
            val name = set.delayName
            if (delay != null && name != null) evidenceUri(delay, name)?.let { uris += it }
        }
        for (case in report.withoutEvidence) {
            val delay = case.delayShot ?: continue
            evidenceUri(delay, delayName(report, case))?.let { uris += it }
        }
        Share.images(this, uris, "ส่งหลักฐาน ${report.cases.size} เคส")
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
        val head = (if (c.hasEvidence) "✅ " else "❌ ") + c.gf + " ล่าช้า " + (c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        Ui.text(card, head, 16f, if (c.hasEvidence) Ui.GREEN else Ui.RED, bold = true)
        c.doneAt?.let { Ui.text(card, "เสร็จสมบูรณ์ " + Parsers.hhmm(it.toLocalTime()), 14f, Ui.MUTED) }
        c.readyShot?.let {
            Ui.text(card, "อยู่ในแท็บ Ready ตั้งแต่ " + ReportText.time(it.t, zone) + (it.status?.let { s -> " · $s" } ?: ""), 14f, topDp = 4)
        }
        c.pressedAt?.let { Ui.text(card, "กด Ready เวลา " + ReportText.time(it, zone), 13f, Ui.MUTED) }
        if (c.matchStatus == "UNKNOWN_INSTANCE") Ui.text(card, "UNKNOWN: ยังยืนยัน instance ไม่ได้ ห้ามใช้ GF อย่างเดียวจับคู่", 13f, Ui.AMBER)
        if (!c.hasEvidence) Ui.text(card, "ไม่พบภาพในแท็บ Ready ของเลขนี้ก่อนเวลาเสร็จ — เก็บเคสนี้ไว้ ไม่ตัดออก", 13f, Ui.RED, topDp = 2)
        if (c.delayShot == null) Ui.text(card, "ยังไม่มีภาพหน้า History ของออเดอร์นี้", 13f, Ui.AMBER, topDp = 2)
        val shots = ArrayList<Pair<Record, String>>()
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
}
