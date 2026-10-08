package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import io.github.panuwattegif.readyproof.ProofService
import io.github.panuwattegif.readyproof.ServiceStatus
import io.github.panuwattegif.readyproof.core.ReportText
import java.time.LocalDate

/** The business date is an explicit choice, independent of screenshot creation time. */
object HistorySweepUi {
    fun choose(activity: Activity, targetPackage: String, initial: LocalDate = LocalDate.now()) {
        val service = ProofService.instance
        if (service == null) {
            Ui.alert(activity, "ระบบยังไม่ทำงาน", "เปิดสิทธิ์การช่วยเหลือพิเศษให้ ReadyProof ก่อน")
            return
        }
        DatePickerDialog(activity, { _, y, m, d ->
            val day = LocalDate.of(y, m + 1, d)
            AlertDialog.Builder(activity).setTitle("กวาด History วันที่ ${ReportText.date(day)}")
                .setMessage("เลือกวันที่เดียวกันใน History ของ Grab ไว้ก่อน แล้วเริ่มกวาด\n\nระบบจะบันทึกออเดอร์ลงวันที่นี้ โดยคงเวลาแคปจริงของรูปไว้ หากอ่านพบวันที่อื่นจะหยุดและแจ้ง ไม่ย้ายออเดอร์วันอื่นมาใส่")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("เริ่มกวาดวันที่นี้") { _, _ ->
                    ServiceStatus.openApp(activity, targetPackage)
                    service.requestHistorySweepNow(day)
                    Ui.toast(activity, "กำลังกวาด History วันที่ ${ReportText.date(day)}", long = true)
                }.show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }
}
