package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import io.github.panuwattegif.readyproof.ProofService
import io.github.panuwattegif.readyproof.ServiceStatus

/**
 * The home-screen icon "เปิด/ปิด ReadyProof", nothing to learn: when on, one tap (and a confirm)
 * switches it off so banking apps work; when off, one tap opens ReadyProof's own switch in
 * Settings (Android lets only the owner switch an accessibility service on).
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val service = ProofService.instance
        if (ServiceStatus.isEnabled(this) && service != null) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("ปิด ReadyProof?")
                .setMessage("ระหว่างปิดจะไม่แคปหลักฐาน แอปธนาคารใช้ได้\nเปิดกลับ: แตะไอคอนนี้อีกครั้ง")
                .setPositiveButton("ปิด") { _, _ ->
                    service.switchOff()
                    Ui.toast(app, "ปิด ReadyProof แล้ว — แอปธนาคารใช้ได้")
                    finish()
                }
                .setNegativeButton("ไม่ปิด") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        Ui.toast(app, "แตะสวิตช์ ReadyProof ให้เปิด แล้วกด \"อนุญาต\"", long = true)
        ServiceStatus.openOwnSwitch(this)
        finish()
    }
}
