package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Switch
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.Diagnostics
import io.github.panuwattegif.readyproof.core.Config

/** Every setting on one page; nothing changes until "บันทึก" passes validation. */
class SettingsActivity : Activity() {
    private lateinit var sAutoNavigation: Switch
    private lateinit var sReady: Switch
    private lateinit var sDelay: Switch
    private lateinit var sScroll: Switch
    private lateinit var sAwake: Switch
    private lateinit var sGuard: Switch
    private lateinit var sEod: Switch
    private lateinit var sToast: Switch
    private lateinit var sDiag: Switch
    private lateinit var fClose: EditText
    private lateinit var fBuffer: EditText
    private lateinit var fRecheck: EditText
    private lateinit var fMaxWait: EditText
    private lateinit var fSweep: EditText
    private lateinit var fIdle: EditText
    private lateinit var fLate: EditText
    private lateinit var fRetention: EditText
    private lateinit var fQuality: EditText
    private lateinit var fReadyTabs: EditText
    private lateinit var fPreparingTabs: EditText
    private lateinit var fHistoryTabs: EditText
    private lateinit var fTabs: EditText
    private lateinit var fNav: EditText
    private lateinit var fOrdersNav: EditText
    private lateinit var fCompleted: EditText
    private lateinit var fCancelled: EditText
    private lateinit var fReadyAny: EditText
    private lateinit var fReadyNone: EditText
    private lateinit var fDoneAny: EditText
    private lateinit var fDelayAny: EditText
    private lateinit var fCancelAny: EditText
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

        val watch = Ui.card(col)
        Ui.text(watch, "ระหว่างวัน (เครื่องที่เปิดแท็บ Ready ค้างไว้)", 17f, bold = true)
        sAutoNavigation = Ui.switch(watch, "ให้แอปเลื่อนรายการ / แตะแท็บ Grab เอง (ปิดแล้วยังแคปสิ่งที่อยู่บนจอและแชร์เองได้)",
            ConfigStore.prefs(this).getBoolean("auto_navigation_enabled", true)) {}
        sReady = Ui.switch(watch, "แคปทุกออเดอร์ที่เข้ามาในแท็บ Ready (ออเดอร์ละครั้ง)", cfg.captureReady) {}
        sScroll = Ui.switch(watch, "เลื่อนรายการเองเมื่อออเดอร์ยาวเกินจอ", cfg.autoScroll) {}
        fSweep = Ui.field(watch, "กวาดรายการ Ready ซ้ำทุกกี่นาที (กันพลาด)", cfg.fullSweepMinutes.toString(),
            help = "ปกติแอปกวาดทันทีเมื่อรายการเปลี่ยน ค่านี้คือรอบสำรอง", number = true)
        sGuard = Ui.switch(watch, "พากลับแท็บ Ready เองเมื่อหลุดไปหน้าอื่น", cfg.guardReadyTab) {}
        fIdle = Ui.field(watch, "ถ้ามีคนใช้เครื่องอยู่ รอให้ว่างกี่นาทีก่อนพากลับ", cfg.guardIdleMinutes.toString(), number = true)
        sAwake = Ui.switch(watch, "ไม่ให้จอดับระหว่างเปิด Grab (จนสรุปสิ้นวันเสร็จ)", cfg.keepScreenOn) {}
        sToast = Ui.switch(watch, "แสดงข้อความเด้งหลังแคป (อาจบังจอ ไม่แนะนำ)", cfg.showToast) {}

        val eod = Ui.card(col)
        Ui.text(eod, "สิ้นวัน", 17f, bold = true)
        sEod = Ui.switch(eod, "สรุปสิ้นวันอัตโนมัติ (อ่านหน้าประวัติทั้งหมด + จับคู่)", cfg.autoEndOfDay) {}
        sDelay = Ui.switch(eod, "แคปออเดอร์ที่ \"Delayed by / ล่าช้าไป\" ในหน้าประวัติ", cfg.captureDelay) {}
        fClose = Ui.field(eod, "เวลาปิดร้าน (เช่น 19:00)", cfg.closeTime)
        fBuffer = Ui.field(eod, "เผื่อเวลาหลังปิดร้านกี่นาที", cfg.endBufferMinutes.toString(), number = true)
        fRecheck = Ui.field(eod, "ถ้ายังมีออเดอร์ค้างในแท็บ Ready ตรวจใหม่ทุกกี่นาที", cfg.recheckMinutes.toString(), number = true)
        fMaxWait = Ui.field(eod, "รอออเดอร์ค้างนานสุดกี่นาที แล้วสรุปเลย", cfg.endMaxWaitMinutes.toString(), number = true)
        fLate = Ui.field(eod, "สถานะในภาพแรกที่แปลว่า \"ร้านช้าจริง\"", cfg.lateStatus.joinToString("\n"),
            help = "ถ้าภาพแรกของออเดอร์ในแท็บ Ready มีคำเหล่านี้ = คนขับมาถึงก่อนร้านกดเสร็จ (บรรทัดละ 1 คำ)", multiLine = true)
        fWindow = Ui.field(eod, "จับคู่หลักฐานย้อนหลังกี่ชั่วโมงก่อนเวลาเสร็จ", cfg.evidenceWindowHours.toString(), number = true)

        val storage = Ui.card(col)
        Ui.text(storage, "การเก็บภาพ", 17f, bold = true)
        fRetention = Ui.field(storage, "จำนวนวันสำหรับค้นย้อนหลัง (ไม่ลบหลักฐานอัตโนมัติ)", cfg.retentionDays.toString(), number = true)
        fQuality = Ui.field(storage, "คุณภาพภาพ JPEG (30–100)", cfg.jpegQuality.toString(), number = true)

        val adv = Ui.card(col)
        Ui.text(adv, "ขั้นสูง (ปกติไม่ต้องแก้)", 17f, bold = true)
        fReadyTabs = Ui.field(adv, "ชื่อแท็บ Ready", cfg.readyTabLabels.joinToString("\n"),
            help = "ทุกออเดอร์ในแท็บนี้ = ร้านทำเสร็จแล้ว", multiLine = true)
        fPreparingTabs = Ui.field(adv, "ชื่อแท็บกำลังเตรียม", cfg.preparingTabLabels.joinToString("\n"),
            help = "สิ้นวันต้องว่างทั้งแท็บ Ready และแท็บนี้ก่อนอ่านประวัติ", multiLine = true)
        fHistoryTabs = Ui.field(adv, "ชื่อแท็บประวัติ", cfg.historyTabLabels.joinToString("\n"), multiLine = true)
        fTabs = Ui.field(adv, "ชื่อแท็บทั้งหมด", cfg.tabLabels.joinToString("\n"),
            help = "ใช้ดูว่าเปิดแท็บไหนอยู่ และหาแถบแท็บที่จะแตะ", multiLine = true)
        fNav = Ui.field(adv, "ชื่อเมนูแถบล่างของ Grab", cfg.navLabels.joinToString("\n"), multiLine = true)
        fOrdersNav = Ui.field(adv, "เมนูที่พาไปหน้าออเดอร์", cfg.ordersNavLabels.joinToString("\n"), multiLine = true)
        fCompleted = Ui.field(adv, "คำว่า \"Completed\" ที่หัวหน้าประวัติ", cfg.completedLabels.joinToString("\n"), multiLine = true)
        fCancelled = Ui.field(adv, "คำว่า \"Cancelled\" ที่หัวหน้าประวัติ", cfg.cancelledLabels.joinToString("\n"), multiLine = true)
        fDoneAny = Ui.field(adv, "คำว่า \"เสร็จสมบูรณ์\" บนรายการประวัติ", cfg.doneAny.joinToString("\n"), multiLine = true)
        fDelayAny = Ui.field(adv, "คำว่า \"ล่าช้า\" บนรายการประวัติ", cfg.delayAny.joinToString("\n"), multiLine = true)
        fCancelAny = Ui.field(adv, "คำว่า \"ยกเลิก\" บนรายการประวัติ", cfg.cancelAny.joinToString("\n"), multiLine = true)
        fReadyAny = Ui.field(adv, "สำรอง: คำสถานะที่ถือว่าอยู่ในแท็บ Ready", cfg.readyAny.joinToString("\n"),
            help = "ใช้เฉพาะเมื่อเครื่องบอกไม่ได้ว่าเปิดแท็บไหน", multiLine = true)
        fReadyNone = Ui.field(adv, "สำรอง: ถ้าการ์ดมีคำเหล่านี้ ไม่นับเป็น Ready", cfg.readyNone.joinToString("\n"), multiLine = true)
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
            "ถ้าแอปไม่แคป เลื่อนไม่ได้ หรือสรุปสิ้นวันไม่ครบ: เปิด \"เก็บข้อมูลหน้าจอเพื่อแก้ปัญหา\" → บันทึก → ปล่อยให้ทำงาน 1 วัน (หรือเปิด Grab ให้ผ่านแท็บ Ready / ประวัติ) → กลับมากดปุ่มด้านล่าง แล้วส่งไฟล์ให้ผู้ดูแลแอป",
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

    private fun save() {
        fun lines(e: EditText) = Config.lines(e.text.toString())
        fun num(e: EditText) = e.text.toString().trim().toIntOrNull() ?: -1
        val cfg = ConfigStore.get(this).copy(
            captureReady = sReady.isChecked,
            autoScroll = sScroll.isChecked,
            fullSweepMinutes = num(fSweep),
            guardReadyTab = sGuard.isChecked,
            guardIdleMinutes = num(fIdle),
            keepScreenOn = sAwake.isChecked,
            showToast = sToast.isChecked,
            autoEndOfDay = sEod.isChecked,
            captureDelay = sDelay.isChecked,
            closeTime = fClose.text.toString().trim(),
            endBufferMinutes = num(fBuffer),
            recheckMinutes = num(fRecheck),
            endMaxWaitMinutes = num(fMaxWait),
            lateStatus = lines(fLate),
            evidenceWindowHours = num(fWindow),
            retentionDays = num(fRetention),
            jpegQuality = num(fQuality),
            readyTabLabels = lines(fReadyTabs),
            preparingTabLabels = lines(fPreparingTabs),
            historyTabLabels = lines(fHistoryTabs),
            tabLabels = lines(fTabs),
            navLabels = lines(fNav),
            ordersNavLabels = lines(fOrdersNav),
            completedLabels = lines(fCompleted),
            cancelledLabels = lines(fCancelled),
            doneAny = lines(fDoneAny),
            delayAny = lines(fDelayAny),
            cancelAny = lines(fCancelAny),
            readyAny = lines(fReadyAny),
            readyNone = lines(fReadyNone),
            gfPattern = fPattern.text.toString().trim(),
            gfPrefix = fPrefix.text.toString().trim(),
            targetPackages = lines(fPackages),
            diagnostics = sDiag.isChecked,
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
