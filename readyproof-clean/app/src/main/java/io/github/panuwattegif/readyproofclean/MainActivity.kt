package io.github.panuwattegif.readyproofclean

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private var selectedDate: LocalDate = LocalDate.now()
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1200L)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(body)
        setContentView(scroll)

        text("ReadyProof Clean", 24f, true)
        text("แอปใหม่แยกจาก ReadyProof เดิม · ข้อมูลและรูปของแอปเดิมไม่ถูกแตะ", 14f, false)

        section("ร้าน")
        val currentShop = CleanStore.selectedShop(this)
        button((if (currentShop == CleanStore.SHOP_KAPRAO) "✓ " else "") + "กะเพรา") {
            CleanStore.setShop(this, CleanStore.SHOP_KAPRAO)
            CleanProofService.instance?.reloadShopState()
            render()
        }
        button((if (currentShop == CleanStore.SHOP_LUKSAO) "✓ " else "") + "ลูกสาวทำเอง") {
            CleanStore.setShop(this, CleanStore.SHOP_LUKSAO)
            CleanProofService.instance?.reloadShopState()
            render()
        }

        section("ระบบจับหลักฐาน")
        text("สถานะ: " + CleanProofService.statusText, 15f, true)
        text(if (CleanProofService.instance == null)
            "Accessibility ยังไม่ทำงาน"
        else "Accessibility ทำงานอยู่", 14f, false)
        button("เปิดการช่วยเหลือพิเศษ (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("เปิด Grab Merchant") { openGrab() }
        button("กลับไปเฝ้า Ready") {
            CleanProofService.instance?.returnToReady()
            openGrab()
        }

        section("วันที่ที่จะกวาด History")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = Button(this).apply {
            text = "◀ วันก่อน"
            setOnClickListener { selectedDate = selectedDate.minusDays(1); render() }
        }
        val label = TextView(this).apply {
            text = selectedDate.format(fmt)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        val next = Button(this).apply {
            text = "วันถัดไป ▶"
            isEnabled = selectedDate.isBefore(LocalDate.now())
            setOnClickListener { if (selectedDate.isBefore(LocalDate.now())) selectedDate = selectedDate.plusDays(1); render() }
        }
        row.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row)

        button("กวาด History วันที่ " + selectedDate.format(fmt)) {
            val service = CleanProofService.instance
            if (service == null) {
                textToast("เปิด Accessibility ให้ ReadyProof Clean ก่อน")
            } else {
                service.startHistorySweep(selectedDate)
                openGrab()
            }
        }

        section("สรุป " + selectedDate.format(fmt))
        val shop = CleanStore.selectedShop(this)
        val s = CleanStore.summary(this, shop, selectedDate)
        text("ร้าน: " + CleanStore.shopLabel(shop), 16f, true)
        text("Ready เห็น ${s.readySeen} · มีภาพ ${s.readyProof} · Pending ${s.readyPending.size}", 15f, false)
        text("History header: " + (s.headerTotal?.let { "ทั้งหมด $it / สำเร็จ ${s.headerCompleted ?: 0} / ยกเลิก ${s.headerCancelled ?: 0}" } ?: "UNKNOWN"), 15f, false)
        text("History อ่านได้ ${s.observedHistory} ออเดอร์", 15f, false)
        text("Grab Delayed ${s.delayed} · ${pct(s.grabPct)}", 15f, true)
        text("มีหลักฐาน Ready ${s.withReadyEvidence}", 15f, false)
        text("หลังหักหลักฐานเหลือ ${s.actualDelayed} · ${pct(s.actualPct)}", 15f, true)
        text("Sweep: " + if (s.complete) "COMPLETE" else "INCOMPLETE", 15f, true)
        s.status?.let { text(it, 13f, false) }
        if (s.readyPending.isNotEmpty()) text("Ready Pending: " + s.readyPending.joinToString(), 13f, false)

        button("แชร์ภาพเคส Delayed (READY + HISTORY ที่มีจริง)") {
            ShareUtil.images(this, CleanStore.reportImageUris(this, shop, selectedDate), "หลักฐาน Delayed")
        }
        button("แชร์ภาพ READY + HISTORY ทั้งวัน") {
            ShareUtil.images(this, CleanStore.allImageUris(this, shop, selectedDate), "หลักฐานทั้งวัน")
        }
        button("แชร์ไฟล์สรุปวันนี้") {
            val bytes = CleanStore.summaryText(this, shop, selectedDate).toByteArray(Charsets.UTF_8)
            val uri = MediaSaver.saveText(this, "${shop}_summary_${selectedDate}.txt", bytes)
            ShareUtil.textFile(this, uri, "สรุป ReadyProof Clean")
        }

        text("รูปเก็บที่ Pictures/ReadyProofClean และยังแชร์เองได้ แม้การกวาดไม่ครบ", 12f, false)
    }

    private fun section(title: String) {
        val v = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(18), 0, dp(6))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        body.addView(v)
    }

    private fun text(value: String, size: Float, bold: Boolean) {
        val v = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, dp(3), 0, dp(3))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        body.addView(v)
    }

    private fun button(label: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun openGrab() {
        val i = packageManager.getLaunchIntentForPackage("com.grab.merchant")
        if (i != null) startActivity(i) else textToast("ไม่พบ Grab Merchant")
    }

    private fun textToast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun pct(v: Double?) = v?.let { String.format(Locale.US, "%.2f%%", it) } ?: "UNKNOWN"
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
