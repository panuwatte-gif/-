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
import io.github.panuwattegif.readyproof.EvidenceProvider
import io.github.panuwattegif.readyproof.MediaSaver
import io.github.panuwattegif.readyproof.ProofService
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.DailyReport
import io.github.panuwattegif.readyproof.core.DelayCase
import io.github.panuwattegif.readyproof.core.EvidenceSet
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.Verdict
import java.time.LocalDate
import java.time.ZoneId

/**
 * End of day: Grab's delayed orders (read from the History list) matched with the Ready tab
 * screenshots and split into three groups: proven in time, proven late, no evidence.
 * Each proven order is one set (GF-xxx_READY.jpg + GF-xxx_DELAY.jpg).
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
            Ui.text(c, if (report.historyRows == 0) "ยังไม่มีข้อมูลจากหน้าประวัติของวันนี้" else "ไม่มีออเดอร์ล่าช้า 🎉", 15f, bold = true)
        } else {
            val sets = Verdict.entries.flatMap { report.sets(it) }.associateBy { it.case }
            for (v in Verdict.entries) {
                val cases = report.cases.filter { it.verdict == v }
                if (cases.isEmpty()) continue
                Ui.text(col, groupTitle(v) + " (${cases.size})", 16f, colorOf(v), bold = true, topDp = 6)
                if (v == Verdict.NO_EVIDENCE) {
                    Ui.text(col, "ไม่ได้แปลว่าร้านช้าจริง: ไม่มีภาพในแท็บ Ready ของออเดอร์นี้ (ไม่ได้ผ่านแท็บ Ready หรือระบบจับไม่ได้)", 13f, Ui.MUTED)
                }
                cases.forEach { caseCard(col, it, sets[it], zone) }
            }
        }
    }

    private fun groupTitle(v: Verdict): String = when (v) {
        Verdict.IN_TIME -> "✅ " + v.label
        Verdict.LATE -> "⚠️ " + v.label
        Verdict.NO_EVIDENCE -> "❓ " + v.label
    }

    private fun colorOf(v: Verdict): Int = when (v) {
        Verdict.IN_TIME -> Ui.GREEN
        Verdict.LATE -> Ui.AMBER
        Verdict.NO_EVIDENCE -> Ui.RED
    }

    private fun summaryCard(col: LinearLayout, r: DailyReport, zone: ZoneId) {
        val s = Ui.card(col)
        if (r.grabCompleted != null) {
            Ui.text(s, "ยอดจาก Grab: เสร็จสมบูรณ์ ${r.grabCompleted}" + (r.grabCancelled?.let { " · ยกเลิก $it" } ?: ""), 15f)
            Ui.text(
                s,
                "อ่านรายการในประวัติได้ ${r.historyRows}/${r.grabCompleted}" + if (r.historyComplete) " ✓ ครบ" else " ⚠ ไม่ครบ",
                15f, if (r.historyComplete) Ui.GREEN else Ui.AMBER,
            )
        } else {
            Ui.text(s, "อ่านรายการในประวัติได้ ${r.historyRows} (ยังไม่ได้อ่านยอดรวมจาก Grab)", 15f)
        }
        Ui.text(s, "มีภาพในแท็บ Ready ${r.rowsWithReady}/${r.historyRows} ออเดอร์", 14f, Ui.MUTED)
        if (r.rowsWithoutReady.isNotEmpty()) {
            Ui.text(s, "ไม่มีภาพ Ready: " + r.rowsWithoutReady.take(20).joinToString(", ") + if (r.rowsWithoutReady.size > 20) " …" else "", 13f, Ui.MUTED)
        }
        Ui.divider(s)
        Ui.text(s, "Grab ระบุล่าช้า ${r.delayed} ออเดอร์ = ${ReportText.pct(r.grabPct)} (แบบ Grab คิด)", 16f, bold = true)
        Ui.text(s, "✅ ${Verdict.IN_TIME.label}: ${r.inTime.size}", 15f, Ui.GREEN, topDp = 2)
        Ui.text(s, "⚠️ ${Verdict.LATE.label}: ${r.late.size}", 15f, Ui.AMBER)
        Ui.text(s, "❓ ${Verdict.NO_EVIDENCE.label}: ${r.noEvidence.size}", 15f, Ui.RED)
        Ui.text(
            s,
            "% ล่าช้าที่ร้านควรได้ = (${r.delayed} − ${r.inTime.size}) / ${r.base} = ${ReportText.pct(r.realPct)}",
            16f, bold = true, topDp = 4,
        )
        if (r.historyRows == 0) {
            Ui.text(
                s,
                "ยังไม่มีข้อมูลจากหน้าประวัติ → รอสรุปอัตโนมัติหลังปิดร้าน หรือกด \"สรุปสิ้นวันตอนนี้\" ที่หน้าแรก",
                13f, Ui.AMBER, topDp = 8,
            )
        }
        if (date == LocalDate.now() && ProofService.instance != null) {
            Ui.button(s, "🧾 อ่านหน้าประวัติใหม่ตอนนี้", filled = false) {
                ProofService.instance?.runEndOfDayNow()
                ServiceStatus.openApp(this, ConfigStore.get(this).targetPackages.first())
            }
        }
        val inTime = r.sets(Verdict.IN_TIME)
        val files = inTime.sumOf { if (it.delay != null) 2L else 1L }
        Ui.button(s, "📤 ส่งหลักฐานกดทัน ${inTime.size} ชุด ($files ไฟล์) เข้า Drive") { shareSets(inTime) }
        val late = r.sets(Verdict.LATE)
        if (late.isNotEmpty()) {
            Ui.button(s, "ส่งชุดที่ช้าจริง ${late.size} ชุด (ไว้ตรวจเอง)", filled = false) { shareSets(late) }
        }
        if (inTime.any { it.delay == null }) {
            Ui.text(s, "⚠ บางชุดยังไม่มีภาพหน้าประวัติ — กด \"อ่านหน้าประวัติใหม่ตอนนี้\"", 13f, Ui.AMBER, topDp = 4)
        }
        Ui.button(s, "📋 คัดลอกสรุป (ไว้วางใน LINE)", filled = false) { Share.copy(this, ReportText.summary(r, zone)) }
        Ui.button(s, "📊 ส่งออกตาราง CSV", filled = false) { exportCsv(r, zone) }
    }

    /** Each set = GF-xxx_READY.jpg + GF-xxx_DELAY.jpg, named for Drive without copying the images. */
    private fun shareSets(sets: List<EvidenceSet>) {
        val uris = ArrayList<Uri>()
        for (set in sets) {
            evidenceUri(set.ready, set.readyName)?.let { uris += it }
            val delay = set.delay
            val name = set.delayName
            if (delay != null && name != null) evidenceUri(delay, name)?.let { uris += it }
        }
        Share.images(this, uris, "ส่งหลักฐาน ${sets.size} ชุด")
    }

    private fun evidenceUri(r: Record, name: String): Uri? = try {
        r.uri?.let { EvidenceProvider.uriFor(Uri.parse(it), name) }
    } catch (e: Exception) {
        null
    }

    private fun caseCard(col: LinearLayout, c: DelayCase, set: EvidenceSet?, zone: ZoneId) {
        val card = Ui.card(col)
        val head = c.gf + " ล่าช้า " + (c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        Ui.text(card, head, 16f, colorOf(c.verdict), bold = true)
        c.doneAt?.let { Ui.text(card, "เสร็จสมบูรณ์ " + Parsers.hhmm(it.toLocalTime()), 14f, Ui.MUTED) }
        c.readyShot?.let {
            Ui.text(card, "อยู่ในแท็บ Ready ตั้งแต่ " + ReportText.time(it.t, zone) + (it.status?.let { s -> " · $s" } ?: ""), 14f, topDp = 4)
        }
        when (c.verdict) {
            Verdict.LATE -> Ui.text(card, "ภาพแรกในแท็บ Ready แสดงว่าคนขับมาถึง/Grab เร่งแล้ว", 13f, Ui.AMBER, topDp = 2)
            Verdict.NO_EVIDENCE -> Ui.text(card, "ไม่พบภาพในแท็บ Ready ของเลขนี้ก่อนเวลาเสร็จ", 13f, Ui.MUTED, topDp = 2)
            Verdict.IN_TIME -> {}
        }
        if (c.delayShot == null) Ui.text(card, "ยังไม่มีภาพหน้าประวัติของออเดอร์นี้", 13f, Ui.AMBER, topDp = 2)
        val shots = ArrayList<Pair<Record, String>>()
        c.readyShot?.let { shots += it.record to (set?.readyName ?: "READY") }
        c.delayShot?.let { shots += it to (set?.delayName ?: "DELAY") }
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
