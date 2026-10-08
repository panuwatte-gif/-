package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.panuwattegif.readyproof.ClickLog
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.Diagnostics
import io.github.panuwattegif.readyproof.core.Config
import io.github.panuwattegif.readyproof.core.ReportText
import java.time.ZoneId

/** Every setting on one page; nothing changes until "บันทึก" passes validation. */
class SettingsActivity : Activity() {
    private lateinit var sPress: Switch
    private lateinit var sRemind: Switch
    private lateinit var sReady: Switch
    private lateinit var sDelay: Switch
    private lateinit var sToast: Switch
    private lateinit var sDiag: Switch
    private lateinit var sAutoNavigation: Switch
    private lateinit var fTriggers: EditText
    private lateinit var fTabs: EditText
    private lateinit var fReadyTabs: EditText
    private lateinit var fRetention: EditText
    private lateinit var fQuality: EditText
    private lateinit var fReadyAny: EditText
    private lateinit var fReadyNone: EditText
    private lateinit var fRepeat: EditText
    private lateinit var fDoneAny: EditText
    private lateinit var fDelayAny: EditText
    private lateinit var fWindow: EditText
    private lateinit var fPattern: EditText
    private lateinit var fPrefix: EditText
    private lateinit var fPackages: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val cfg = ConfigStore.get(this)
        val col = Ui.page(this, "ตั้งค่า", "แก้แล้วกด \"บันทึก\" ด้านล่างสุด")

        val timing = Ui.card(col)
        Ui.text(timing, "แคปเมื่อไหร่", 17f, bold = true)
        sAutoNavigation = Ui.switch(timing, "เปิดหน้าอัตโนมัติ: หยุดใช้งานในรุ่นนี้", false) {}
        sAutoNavigation.isEnabled = false
        sReady = Ui.switch(timing, "แท็บ Ready (พร้อมจัดส่ง) เปิดอยู่ → แคปทุกออเดอร์ในแท็บ (ออเดอร์ละครั้ง)", cfg.captureReady) {}
        sDelay = Ui.switch(timing, "หน้า History เจอ \"Delayed by / ล่าช้าไป\" → แคป", cfg.captureDelay) {}
        sRemind = Ui.switch(timing, "กด Ready แล้ว 20 วิ ยังไม่มีภาพในแท็บ Ready → เตือน", cfg.remindReadyTab) {}
        sPress = Ui.switch(timing, "แคปตอนกดปุ่ม Ready ด้วย (ไม่จำเป็น)", cfg.capturePress) {}
        sToast = Ui.switch(timing, "แสดงข้อความเด้งหลังแคป", cfg.showToast) {}

        val button = Ui.card(col)
        Ui.text(button, "ปุ่ม Ready บนการ์ดออเดอร์", 17f, bold = true)
        Ui.text(button, "ใช้แค่เพื่อเตือนให้เปิดแท็บ Ready (หลักฐานคือภาพในแท็บ Ready)", 13f, Ui.MUTED)
        fTriggers = Ui.field(
            button, "คำบนปุ่ม (บรรทัดละ 1 คำ)", cfg.pressTriggers.joinToString("\n"),
            help = "ต้องตรงทั้งคำ · ใส่ * ท้ายคำ = ขึ้นต้นด้วยคำนี้ · id:ชื่อ = รหัสปุ่ม", multiLine = true,
        )
        recentTaps(button)

        val storage = Ui.card(col)
        Ui.text(storage, "การเก็บภาพ", 17f, bold = true)
        fRetention = Ui.field(storage, "จำนวนวันสำหรับค้นย้อนหลัง (ไม่ลบหลักฐานอัตโนมัติ)", cfg.retentionDays.toString(), number = true)
        fQuality = Ui.field(storage, "คุณภาพภาพ JPEG (30–100)", cfg.jpegQuality.toString(), number = true)

        val adv = Ui.card(col)
        Ui.text(adv, "ขั้นสูง (ปกติไม่ต้องแก้)", 17f, bold = true)
        fReadyTabs = Ui.field(adv, "ชื่อแท็บ Ready", cfg.readyTabLabels.joinToString("\n"),
            help = "ทุกออเดอร์ในแท็บนี้ = กดเสร็จแล้ว", multiLine = true)
        fTabs = Ui.field(adv, "ชื่อแท็บทั้งหมด", cfg.tabLabels.joinToString("\n"),
            help = "ใช้ดูว่าตอนนี้เปิดแท็บไหนอยู่", multiLine = true)
        fReadyAny = Ui.field(adv, "สำรอง: คำสถานะที่ถือว่าอยู่ในแท็บ Ready", cfg.readyAny.joinToString("\n"),
            help = "ใช้เฉพาะเมื่อเครื่องบอกไม่ได้ว่าเปิดแท็บไหน", multiLine = true)
        fReadyNone = Ui.field(adv, "สำรอง: ถ้าการ์ดมีคำเหล่านี้ ไม่นับเป็น Ready", cfg.readyNone.joinToString("\n"),
            help = "กันออเดอร์ที่ยังเตรียมอยู่หรือเสร็จไปแล้ว", multiLine = true)
        fRepeat = Ui.field(adv, "ค่าเดิมเก็บไว้ — รุ่นนี้ไม่ถ่าย READY ซ้ำตามเวลา", cfg.readyRepeatMinutes.toString(), number = true)
        fRepeat.isEnabled = false
        fDoneAny = Ui.field(adv, "คำว่า \"เสร็จสมบูรณ์\" ในหน้า History", cfg.doneAny.joinToString("\n"), multiLine = true)
        fDelayAny = Ui.field(adv, "คำว่า \"ล่าช้า\" ในหน้า History", cfg.delayAny.joinToString("\n"), multiLine = true)
        fWindow = Ui.field(adv, "จับคู่หลักฐานย้อนหลังกี่ชั่วโมงก่อนเวลาเสร็จ", cfg.evidenceWindowHours.toString(), number = true)
        fPattern = Ui.field(adv, "รูปแบบเลขออเดอร์ (regex)", cfg.gfPattern)
        fPrefix = Ui.field(adv, "คำนำหน้าเลขออเดอร์", cfg.gfPrefix)
        fPackages = Ui.field(adv, "แอปที่เฝ้าดู (package, บรรทัดละ 1)", cfg.targetPackages.joinToString("\n"), multiLine = true)
        sDiag = Ui.switch(adv, "เก็บข้อมูลหน้าจอเพื่อแก้ปัญหา", cfg.diagnostics) {}

        val actions = Ui.card(col)
        Ui.button(actions, "💾 บันทึก") { save() }
        Ui.button(actions, "คืนค่าเริ่มต้นทั้งหมด", filled = false, color = Ui.RED) {
            Ui.confirm(this, "คืนค่าเริ่มต้น?", "การตั้งค่าทั้งหมดจะกลับเป็นค่าเริ่มต้น (ภาพและรายงานไม่หาย)", "คืนค่า") {
                ConfigStore.save(this, Config.DEFAULT.copy(enabled = ConfigStore.get(this).enabled))
                Ui.toast(this, "คืนค่าเริ่มต้นแล้ว")
                render()
            }
        }

        val help = Ui.card(col)
        Ui.text(help, "แก้ปัญหา", 17f, bold = true)
        Ui.text(
            help,
            "ถ้าแอปไม่แคปแท็บ Ready หรืออ่านเลข GF ไม่ได้: เปิด \"เก็บข้อมูลหน้าจอเพื่อแก้ปัญหา\" → บันทึก → ใช้แอป Grab ตามปกติให้ผ่านแท็บ Preparing / Ready / History → กลับมากดปุ่มด้านล่าง แล้วส่งไฟล์ให้ผู้ดูแลแอป",
            13f, Ui.MUTED,
        )
        Ui.button(help, "📤 ส่งออกไฟล์ช่วยแก้ปัญหา", filled = false) {
            try {
                Share.file(this, Diagnostics.export(this), "text/plain", "ส่งไฟล์ช่วยแก้ปัญหา")
            } catch (e: Exception) {
                Ui.alert(this, "ส่งออกไม่สำเร็จ", e.message ?: "")
            }
        }
    }

    /** The last taps in Grab, so a renamed button can be picked with one tap. */
    private fun recentTaps(parent: LinearLayout) {
        Ui.text(parent, "ปุ่มที่เพิ่งกดในแอป Grab (ล่าสุดอยู่บน)", 14f, bold = true, topDp = 14)
        val taps = ClickLog.list(this)
        if (taps.isEmpty()) {
            Ui.text(parent, "ยังไม่มี — เปิดแอป Grab แล้วกดปุ่มตามปกติ รายการจะขึ้นที่นี่ (✓ = แอปแคปให้แล้ว)", 13f, Ui.MUTED)
            return
        }
        val zone = ZoneId.systemDefault()
        for (e in taps.take(15)) {
            val row = Ui.row(this)
            val label = e.label.ifEmpty { "(ไม่มีข้อความ)" }
            row.addView(TextView(this).apply {
                text = ReportText.time(e.t, zone) + "  " + (if (e.matched) "✓ " else "") + label +
                    "\n" + (e.className?.substringAfterLast('.') ?: "") + (e.viewId?.let { " · " + it.substringAfter(":id/") } ?: "")
                textSize = 13f
                setTextColor(if (e.matched) Ui.GREEN else Ui.TEXT)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (!e.matched && (e.label.isNotEmpty() || e.viewId != null)) {
                row.addView(Ui.smallButton(this, "ใช้ปุ่มนี้") { addTrigger(e) })
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@SettingsActivity, 6) })
        }
        Ui.button(parent, "ล้างรายการ", filled = false) {
            ClickLog.clear(this)
            Ui.toast(this, "ล้างแล้ว (เปิดหน้านี้ใหม่เพื่ออัปเดต)")
        }
    }

    private fun addTrigger(e: ClickLog.Entry) {
        val entry = if (e.label.isNotEmpty() && !e.label.contains(" | ")) e.label
        else e.viewId?.substringAfter(":id/")?.let { "id:$it" } ?: return
        val lines = Config.lines(fTriggers.text.toString())
        if (entry !in lines) fTriggers.setText((lines + entry).joinToString("\n"))
        Ui.toast(this, "เพิ่ม \"$entry\" แล้ว — กด \"บันทึก\" ด้านล่างสุด", long = true)
    }

    private fun save() {
        fun lines(e: EditText) = Config.lines(e.text.toString())
        fun num(e: EditText) = e.text.toString().trim().toIntOrNull() ?: -1
        val cfg = ConfigStore.get(this).copy(
            capturePress = sPress.isChecked,
            remindReadyTab = sRemind.isChecked,
            captureReady = sReady.isChecked,
            tabLabels = lines(fTabs),
            readyTabLabels = lines(fReadyTabs),
            captureDelay = sDelay.isChecked,
            showToast = sToast.isChecked,
            diagnostics = sDiag.isChecked,
            pressTriggers = lines(fTriggers),
            retentionDays = num(fRetention),
            jpegQuality = num(fQuality),
            readyAny = lines(fReadyAny),
            readyNone = lines(fReadyNone),
            readyRepeatMinutes = num(fRepeat),
            doneAny = lines(fDoneAny),
            delayAny = lines(fDelayAny),
            evidenceWindowHours = num(fWindow),
            gfPattern = fPattern.text.toString().trim(),
            gfPrefix = fPrefix.text.toString().trim(),
            targetPackages = lines(fPackages),
        )
        val errors = ConfigStore.save(this, cfg)
        if (errors.isEmpty()) ConfigStore.prefs(this).edit()
            .putBoolean("auto_navigation_enabled", sAutoNavigation.isChecked).apply()
        if (errors.isEmpty()) {
            Ui.toast(this, "บันทึกแล้ว")
            finish()
        } else {
            Ui.alert(this, "ยังบันทึกไม่ได้", errors.joinToString("\n") { "• $it" })
        }
    }
}
