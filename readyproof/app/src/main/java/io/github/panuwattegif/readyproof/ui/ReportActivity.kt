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
import io.github.panuwattegif.readyproof.MediaSaver
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.DelayCase
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import java.time.LocalDate
import java.time.ZoneId

/**
 * End of day: Grab's delayed orders (read from the history list) matched with the
 * "พร้อมจัดส่ง" / READY screenshots, ready to send to Drive or LINE in one go.
 */
class ReportActivity : Activity() {
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
        val report = ReportBuilder.build(records, date, zone, cfg)
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

        summaryCard(col, report, zone)
        if (report.cases.isEmpty()) {
            val c = Ui.card(col)
            Ui.text(c, if (report.completedSeen == 0) "ยังไม่มีข้อมูลออเดอร์ล่าช้าของวันนี้" else "ไม่มีออเดอร์ล่าช้า 🎉", 15f, bold = true)
        } else {
            report.cases.forEach { caseCard(col, it, zone) }
        }
    }

    private fun summaryCard(col: LinearLayout, r: DailyReport, zone: ZoneId) {
        val s = Ui.card(col)
        Ui.text(s, "ออเดอร์ที่เห็นในหน้าประวัติ: ${r.completedSeen}", 15f)
        Ui.text(s, "Grab ระบุล่าช้า: ${r.delayed} (${ReportText.pct(r.pct(r.delayed))})", 16f, bold = true, topDp = 2)
        Ui.text(s, "✅ มีหลักฐานว่ากดพร้อมจัดส่งแล้ว: ${r.withEvidence.size}", 15f, Ui.GREEN, topDp = 2)
        Ui.text(s, "❌ ไม่มีหลักฐาน: ${r.withoutEvidence.size} → ล่าช้าจริง ${ReportText.pct(r.pct(r.withoutEvidence.size))}", 15f, Ui.RED, topDp = 2)
        Ui.text(s, "กดพร้อมจัดส่ง ${r.pressedOrders} ออเดอร์ · มีภาพ READY ${r.readyOrders} ออเดอร์", 13f, Ui.MUTED, topDp = 4)
        if (r.completedSeen == 0) {
            Ui.text(
                s,
                "ยังไม่มีข้อมูลจากหน้าประวัติของวันนี้ → เปิดแอป Grab → คำสั่งซื้อ → ประวัติ แล้วเลื่อนดูออเดอร์ของวันนี้ให้ครบ (เลื่อนช้าๆ) แอปจะอ่านเลขที่ล่าช้าให้เอง แล้วกลับมาหน้านี้",
                13f, Ui.AMBER, topDp = 8,
            )
            Ui.button(s, "เปิดแอป Grab", filled = false) { ServiceStatus.openApp(this, ConfigStore.get(this).targetPackages.first()) }
        }
        val shots = r.shareRecords()
        Ui.button(s, "📤 ส่งภาพหลักฐาน ${shots.size} ภาพ (เข้า Drive / LINE)") {
            Share.images(this, shots.mapNotNull { it.uri?.let(Uri::parse) })
        }
        Ui.button(s, "📋 คัดลอกสรุป (ไว้วางใน LINE)", filled = false) { Share.copy(this, ReportText.summary(r, zone)) }
        Ui.button(s, "📊 ส่งออกตาราง CSV", filled = false) { exportCsv(r, zone) }
    }

    private fun caseCard(col: LinearLayout, c: DelayCase, zone: ZoneId) {
        val card = Ui.card(col)
        val head = (if (c.hasEvidence) "✅ " else "❌ ") + c.gf + " ล่าช้า " + (c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        Ui.text(card, head, 16f, if (c.hasEvidence) Ui.GREEN else Ui.RED, bold = true)
        c.doneAt?.let { Ui.text(card, "เสร็จสมบูรณ์ " + Parsers.hhmm(it.toLocalTime()), 14f, Ui.MUTED) }
        c.pick(ObsType.READY)?.let {
            Ui.text(card, "READY " + ReportText.time(it.t, zone) + (it.status?.let { s -> " · $s" } ?: ""), 14f, topDp = 4)
        }
        c.pick(ObsType.PRESS)?.let {
            Ui.text(card, "กดพร้อมจัดส่ง " + ReportText.time(it.t, zone) + (it.countdown?.let { cd -> " · เหลือเวลา $cd" } ?: ""), 14f)
        }
        if (c.pick(ObsType.READY) == null && c.pick(ObsType.PRESS) == null) {
            c.pick(ObsType.VISIBLE)?.let { Ui.text(card, "แคปเอง " + ReportText.time(it.t, zone), 14f) }
        }
        if (!c.hasEvidence) {
            Ui.text(card, "ไม่พบภาพ \"กดพร้อมจัดส่ง\" หรือ READY ของเลขนี้ในช่วงก่อนเสร็จ", 13f, Ui.MUTED, topDp = 2)
        }
        thumbRow(card, (c.bestShots() + c.delayShots).distinctBy { it.id })
    }

    private fun thumbRow(parent: LinearLayout, shots: List<Record>) {
        if (shots.isEmpty()) return
        val scroll = HorizontalScrollView(this)
        val row = Ui.row(this)
        for (r in shots) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = Ui.rounded(this@ReportActivity, Ui.FIELD, 6f, Ui.LINE)
                contentDescription = r.file
                setOnClickListener { r.uri?.let { Share.view(this@ReportActivity, Uri.parse(it)) } }
            }
            row.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 160)).apply { rightMargin = Ui.dp(this@ReportActivity, 8) })
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
