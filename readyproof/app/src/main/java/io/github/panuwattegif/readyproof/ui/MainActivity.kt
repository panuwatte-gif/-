package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.content.Intent
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.ProofService
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.ObsType
import io.github.panuwattegif.readyproof.core.RecordKind
import io.github.panuwattegif.readyproof.core.ReportBuilder
import io.github.panuwattegif.readyproof.core.ReportText
import java.time.LocalDate
import java.time.ZoneId

/** Home: is the watcher alive, today's numbers, and the way to every other screen. */
class MainActivity : Activity() {
    private val refresh: () -> Unit = { render() }

    override fun onResume() {
        super.onResume()
        RecordStore.addListener(refresh)
        render()
    }

    override fun onPause() {
        RecordStore.removeListener(refresh)
        super.onPause()
    }

    private fun render() {
        val col = Ui.page(this, "ReadyProof", "แคปหลักฐานออเดอร์ Grab อัตโนมัติ", back = false)
        val cfg = ConfigStore.get(this)
        val zone = ZoneId.systemDefault()
        val enabled = ServiceStatus.isEnabled(this)
        val running = ProofService.instance != null

        // --- status ---
        val status = Ui.card(col)
        when {
            enabled && running && cfg.enabled ->
                Ui.text(status, "● ระบบทำงานอยู่", 19f, Ui.GREEN, bold = true)
            enabled && running ->
                Ui.text(status, "⏸ หยุดแคปชั่วคราว", 19f, Ui.AMBER, bold = true)
            enabled -> {
                Ui.text(status, "⚠ เปิดสิทธิ์แล้ว แต่ระบบยังไม่ทำงาน", 19f, Ui.AMBER, bold = true)
                Ui.text(status, "มือถือบางรุ่นปิดแอปเบื้องหลังเอง: เข้าหน้าการช่วยเหลือพิเศษ → ReadyProof → ปิดแล้วเปิดใหม่ และกด \"อนุญาตให้ทำงานเบื้องหลังตลอด\" ด้านล่าง", 14f, Ui.MUTED, topDp = 4)
                Ui.button(status, "เปิดหน้าการช่วยเหลือพิเศษ") { ServiceStatus.openAccessibilitySettings(this) }
            }
            else -> {
                Ui.text(status, "✕ ยังไม่ได้เปิดสิทธิ์ \"การช่วยเหลือพิเศษ\"", 19f, Ui.RED, bold = true)
                Ui.text(
                    status,
                    "1) กดปุ่มเขียวด้านล่าง → หา ReadyProof (อาจอยู่ในหัวข้อ \"แอปที่ดาวน์โหลด\") → เปิดสวิตช์ → อนุญาต\n" +
                        "2) ถ้าสวิตช์เป็นสีเทากดไม่ได้ หรือขึ้นว่า \"การตั้งค่าที่ถูกจำกัด\" → กดปุ่ม \"เปิดหน้าข้อมูลแอป\" → แตะ ⋮ มุมขวาบน → \"อนุญาตการตั้งค่าที่ถูกจำกัด\" → แล้วทำข้อ 1 ใหม่",
                    14f, Ui.MUTED, topDp = 4,
                )
                Ui.button(status, "เปิดหน้าการช่วยเหลือพิเศษ") { ServiceStatus.openAccessibilitySettings(this) }
                Ui.button(status, "เปิดหน้าข้อมูลแอป (ถ้าติด \"ถูกจำกัด\")", filled = false) { ServiceStatus.openAppInfo(this) }
            }
        }
        if (enabled) {
            Ui.switch(status, "แคปอัตโนมัติ", cfg.enabled) { on ->
                ConfigStore.update(this) { it.copy(enabled = on) }
                render()
            }
        }
        val target = cfg.targetPackages.first()
        val installed = ServiceStatus.isInstalled(this, target)
        Ui.text(
            status,
            if (installed) "✓ พบแอป GrabMerchant ในเครื่องนี้" else "✕ ไม่พบแอป GrabMerchant ในเครื่องนี้ ($target)",
            14f, if (installed) Ui.MUTED else Ui.RED, topDp = 6,
        )
        val lastEvent = ProofService.lastTargetEventAt
        if (lastEvent > 0) Ui.text(status, "เห็นหน้าจอ Grab ล่าสุด: " + ReportText.time(lastEvent, zone), 14f, Ui.MUTED)
        if (!ServiceStatus.isIgnoringBattery(this)) {
            Ui.button(status, "อนุญาตให้ทำงานเบื้องหลังตลอด (กันมือถือปิดระบบ)", filled = false) {
                ServiceStatus.requestIgnoreBattery(this)
            }
        }

        // --- today ---
        val today = LocalDate.now()
        val records = RecordStore.load(this, today)
        val report = ReportBuilder.build(
            RecordStore.loadRange(this, today.minusDays(1), today.plusDays(1)),
            today,
            zone,
            cfg,
        )
        fun gfs(type: ObsType, withImage: Boolean) =
            records.filter { !withImage || it.uri != null }.flatMap { r -> r.items.filter { it.type == type }.map { it.gf } }.toSet()
        val ready = gfs(ObsType.READY, withImage = true)
        val pressedNoShot = gfs(ObsType.PRESS, withImage = false) - ready
        val day = Ui.card(col)
        Ui.text(day, "วันนี้ " + ReportText.date(today), 17f, bold = true)
        Ui.text(
            day,
            "มีภาพในแท็บ Ready ${ready.size} ออเดอร์ · History ${report.historyOrders} ออเดอร์ " +
                "(เสร็จ ${report.completedSeen} / ยกเลิก ${report.cancelledSeen}) · " +
                "Delayed ${report.delayed}",
            15f, topDp = 4,
        )
        Ui.text(
            day,
            "ตรวจจำนวน Ready ${report.readyOrders} / Completed ${report.completedSeen}: " +
                (if (report.readyVsCompletedMatch) "MATCH" else "MISMATCH"),
            14f,
            if (report.readyVsCompletedMatch) Ui.GREEN else Ui.AMBER,
            topDp = 3,
        )
        if (pressedNoShot.isNotEmpty()) {
            Ui.text(
                day,
                "⏰ กด Ready แล้วแต่ยังไม่มีภาพในแท็บ Ready: " + pressedNoShot.take(6).joinToString(", ") +
                    (if (pressedNoShot.size > 6) " …" else "") + " → ระบบจะคงเป็น PENDING และสแกนซ้ำ",
                14f, Ui.AMBER, topDp = 4,
            )
        }
        ConfigStore.prefs(this).getString("auto_history_last_result", null)?.let {
            Ui.text(day, "History ล่าสุด: $it", 13f, Ui.MUTED, topDp = 2)
        }
        ProofService.lastCaptureText?.let { Ui.text(day, "ล่าสุด: $it", 14f, Ui.MUTED, topDp = 2) }
        Ui.button(day, "📋 รายงานออเดอร์ล่าช้า + จับคู่หลักฐาน") { startActivity(Intent(this, ReportActivity::class.java)) }
        Ui.button(day, "🖼️ ภาพที่แคปไว้ / ค้นหาเลข GF", filled = false) { startActivity(Intent(this, CapturesActivity::class.java)) }

        // --- tools ---
        val tools = Ui.card(col)
        Ui.button(tools, "🔄 กวาด History ตอนนี้", filled = false) {
            val service = ProofService.instance
            if (service == null) {
                Ui.alert(this, "ระบบยังไม่ทำงาน", "เปิดสิทธิ์การช่วยเหลือพิเศษให้ ReadyProof ก่อน")
            } else {
                service.requestHistorySweepNow()
                Ui.toast(this, "กำลังเปิด History และกวาดรายการอัตโนมัติ", long = true)
            }
        }
        Ui.button(tools, "🧪 ทดสอบ: แคปหน้าจอใน 5 วินาที", filled = false) {
            val service = ProofService.instance
            if (service == null) {
                Ui.alert(this, "ระบบยังไม่ทำงาน", "เปิดสิทธิ์การช่วยเหลือพิเศษให้ ReadyProof ก่อน แล้วลองใหม่")
            } else {
                service.requestManualCapture(5_000, "ทดสอบ")
                Ui.toast(this, "สลับไปหน้าแอป Grab ภายใน 5 วินาที", long = true)
                ServiceStatus.openApp(this, target)
            }
        }
        Ui.button(tools, "⚙️ ตั้งค่า", filled = false) { startActivity(Intent(this, SettingsActivity::class.java)) }
        Ui.button(tools, "📖 คู่มือการใช้งาน", filled = false) { startActivity(Intent(this, GuideActivity::class.java)) }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        Ui.text(col, "ReadyProof $version · ไม่ใช้อินเทอร์เน็ต ข้อมูลอยู่ในเครื่องนี้เท่านั้น", 12f, Ui.MUTED, topDp = 4)
    }
}
