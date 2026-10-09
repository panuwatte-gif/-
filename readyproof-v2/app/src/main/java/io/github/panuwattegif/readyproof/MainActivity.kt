package io.github.panuwattegif.readyproof

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import proof.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : Activity() {
    private lateinit var db: ProofDb
    private lateinit var body: LinearLayout
    private lateinit var day: String
    private val shop = "กะเพราโคตรคลีน"
    private val prefs by lazy { getSharedPreferences("control",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db=ProofDb(this)
        day=prefs.getString("day",null) ?: LocalDate.now(ZoneId.of("Asia/Bangkok")).toString()
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,24,28,24) }
        setContentView(ScrollView(this).apply { addView(body) })
    }
    override fun onResume() { super.onResume(); render() }
    private fun line(text: String, size: Float = 16f) {
        body.addView(TextView(this).apply { this.text=text; textSize=size; setPadding(0,12,0,12) })
    }
    private fun button(text: String, click: () -> Unit) {
        body.addView(Button(this).apply { this.text=text; setOnClickListener { click() } })
    }
    private fun render() {
        body.removeAllViews()
        line("ReadyProof • $shop",22f)
        line("วันที่ออเดอร์: $day  |  เวลากวาด/แคปบันทึกแยกต่างหาก")
        button("เลือกวันที่ของชุดออเดอร์") {
            val d=LocalDate.parse(day)
            DatePickerDialog(this,{ _,year,month,dayOfMonth ->
                day=LocalDate.of(year,month+1,dayOfMonth).toString()
                prefs.edit().putString("day",day).apply();render()
            },d.year,d.monthValue-1,d.dayOfMonth).show()
        }
        val sweeping=prefs.getString("sweep",null)
        line(if(sweeping!=null) "กำลังกวาด History วันที่ $sweeping" else "เฝ้า Ready เมื่อเปิดแท็บพร้อมจัดส่ง")
        line("Ready ที่เห็น ${prefs.getInt("visible",0)} • Pending ${prefs.getInt("pending",0)} • มีภาพจริง ${db.ready(day).count { it.image != null }}")
        if(sweeping==null) button("เริ่มกวาด History วันที่ $day") {
            prefs.edit().putString("sweep",day).putString("status","รอหน้า History ที่เลือกวันที่ใน Grab").apply()
            render()
            packageManager.getLaunchIntentForPackage("com.grab.merchant")?.let(::startActivity)
        } else button("หยุดกวาด History") {
            prefs.edit().remove("sweep").apply();render()
        }
        line("ใน Grab ให้เปิด History และเลือกวันเดียวกันเองก่อนกดเริ่ม • ระบบจะเลื่อนทีละขั้นหลังภาพบันทึกสำเร็จ")
        line("สถานะ: ${prefs.getString("status","รอเริ่ม")}")
        button("เปิดสิทธิ์การช่วยเหลือพิเศษ") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val all=db.history(day)
        val audit=Logic.audit(db.header(day),all,db.stuck(day))
        val cases=Logic.match(all,db.ready(day))
        line("กวาดรายออเดอร์ ${audit.rows}/${audit.header?.total ?: "UNKNOWN"} • ภาพ ${audit.images} • ${if(audit.complete)"ครบตามยอด" else "ยังไม่ยืนยันว่าครบ"}",18f)
        line(Logic.report(shop,day,audit,cases,all.count { it.card.delayed }))
        line("เคส Delayed เท่านั้น",20f)
        cases.forEach { c ->
            line("${c.history.card.gf} • ${when(c.kind) {
                CaseKind.MATCHED -> "คู่ Ready + DELAY"
                CaseKind.NO_READY -> "DELAY เดี่ยว ไม่มี Ready"
                CaseKind.UNKNOWN -> "UNKNOWN ยืนยัน instance ไม่ได้"
            }}\nDELAY: ${c.history.image?.let { File(it).name } ?: "ขาด"}" +
            c.ready?.image?.let { "\nREADY: ${File(it).name}" }.orEmpty())
        }
        button("แชร์หลักฐานและสรุป • เลือก Save to Google Drive") {
            try { share(cases,audit,all.count { it.card.delayed }) }
            catch (e: Exception) {
                line("แชร์ไม่สำเร็จ: ${e.message ?: "ไม่ทราบสาเหตุ"} • ภาพต้นฉบับยังอยู่ในเครื่อง")
            }
        }
    }
    private fun share(cases: List<Case>, audit: Audit, observedDelayed: Int) {
        val export=File(filesDir,"readyproof-v2/export").apply { mkdirs() }
        val files=mutableListOf<File>()
        val repeat=cases.groupingBy { it.history.card.gf }.eachCount()
        fun add(original: String?, name: String) {
            if(original==null)return
            val source=File(original)
            if(!source.isFile || source.length()==0L)return
            val target=File(export,name)
            source.copyTo(target,overwrite=true);files+=target
        }
        cases.forEach { case ->
            val gf=case.history.card.gf
            val repeated=repeat.getValue(gf)>1
            val suffix=case.history.captured
            add(case.history.image,Logic.filename(gf,"DELAY",day,repeated,suffix))
            if(case.kind==CaseKind.MATCHED) add(case.ready?.image,
                Logic.filename(gf,"READY",day,repeated,suffix))
        }
        add(db.headerImage(day),"HISTORY_TOTAL_${day}.jpg")
        val summary=File(export,"SUMMARY_${day}.txt")
        summary.writeText(Logic.report(shop,day,audit,cases,observedDelayed));files+=summary
        val uris=ArrayList(files.map { FileProvider.getUriForFile(this,
            "io.github.panuwattegif.readyproof.v2files",it) })
        val intent=Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type="*/*";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri("ReadyProof",uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        startActivity(Intent.createChooser(intent,"บันทึกไป Google Drive"))
    }
}
