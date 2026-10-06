package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import io.github.panuwattegif.readyproof.*
import io.github.panuwattegif.readyproof.core.Shop

class DriveActivity : Activity() {
    private var selectedAccount: String? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedAccount = state?.getString("selectedAccount")
        render()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("selectedAccount", selectedAccount)
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val col = Ui.page(this, "ร้านและ Google Drive", "หลักฐานในเครื่องและแชร์เองใช้งานได้เสมอ")
        val card = Ui.card(col)
        val shop = ShopStore.get(this)
        if (shop == null) {
            Ui.text(card, "เลือกร้านของมือถือเครื่องนี้", 18f, bold = true)
            Ui.text(card, "ล็อกร้านครั้งเดียวเพื่อกันอัปข้ามร้าน เลือกให้ตรงกับบัญชีร้านที่เปิดใน Grab\nภาพเดิมที่ไม่มีชื่อร้านจะอยู่ในรายงานข้อมูลเดิม และไม่ถูกอัปอัตโนมัติ", 14f, Ui.MUTED)
            for (s in Shop.entries) Ui.button(card, s.label) {
                Ui.confirm(this, "มือถือร้าน ${s.label}?", "หลักฐานใหม่ทั้งหมดจะระบุ ${s.label} และส่งเฉพาะโฟลเดอร์นี้\n${s.folderId}\nแอปไม่สามารถตรวจชื่อร้านใน Grab ให้แทนคุณได้", "ล็อกร้าน") {
                    if (ShopStore.bind(this, s)) {
                        ProofService.instance?.onShopBound()
                        render()
                    }
                }
            }
            return
        }
        Ui.text(card, "ร้าน: ${shop.label}", 18f, bold = true)
        Ui.text(card, "ปลายทางตายตัว:\n${shop.folderId}\nส่งไฟล์เข้าโฟลเดอร์รอตรวจโดยตรง ไม่สร้างโฟลเดอร์อื่น", 14f, Ui.MUTED)
        val prefs = ConfigStore.prefs(this)
        Ui.text(card, "บัญชี: ${prefs.getString("drive_account", null) ?: "ยังไม่เชื่อม"}", 14f)
        Ui.text(card, DriveSync.summary(this), 14f, Ui.MUTED, topDp = 8)
        Ui.switch(card, "อัปโหลดอัตโนมัติ (เฉพาะไฟล์ที่มีจริง)", prefs.getBoolean("drive_enabled", false)) { on ->
            prefs.edit().putBoolean("drive_enabled", on).apply()
            if (on) DriveSync.recover(this)
        }
        Ui.button(card, "เชื่อม / อนุญาต Google Drive") {
            try {
                startActivityForResult(AccountPicker.newChooseAccountIntent(
                    AccountPicker.AccountChooserOptions.Builder().setAllowableAccountsTypes(listOf("com.google")).build()), 410)
            } catch (e: Exception) { Ui.alert(this, "เลือกบัญชีไม่ได้", "เครื่องต้องมี Google Play services / แชร์ผ่าน Drive เองได้") }
        }
        Ui.button(card, "ลองส่งไฟล์ที่ค้างอีกครั้ง", filled = false) { DriveSync.recover(this); Ui.toast(this, "ตรวจคิวและลองส่งเมื่อเครือข่ายพร้อม") }
        Ui.text(card, "Google ต้องลงทะเบียน OAuth สำหรับแอปนี้และเปิด Drive API ก่อนใช้งาน\nการเข้าถึงโฟลเดอร์เดิมต้องขอสิทธิ์ Drive แบบเต็ม ซึ่ง Google อาจกำหนดให้ตรวจสอบแอป\nถ้า AUTH_REQUIRED / 401 / 403 ให้เชื่อมใหม่หรือแชร์เองได้ ไม่มีผลต่อการเก็บหลักฐาน", 13f, Ui.AMBER, topDp = 8)
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { DriveSync.status(this, "ยกเลิกการเชื่อม — แชร์เองได้"); return }
        if (requestCode == 410) {
            selectedAccount = data?.getStringExtra("authAccount")
            val email = selectedAccount ?: return
            Identity.getAuthorizationClient(this).authorize(DriveAuth.request(email))
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        try { startIntentSenderForResult(result.pendingIntent!!.intentSender, 411, null, 0, 0, 0) }
                        catch (e: Exception) { failed() }
                    } else connected(result)
                }.addOnFailureListener { failed() }
        } else if (requestCode == 411) {
            runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data) }
                .onSuccess { connected(it) }.onFailure { failed() }
        }
    }

    private fun connected(result: AuthorizationResult) {
        val email = selectedAccount ?: return
        if (result.accessToken == null) { failed(); return }
        // Tokens remain in Google's cache; only the chosen account and opt-in are persisted.
        ConfigStore.prefs(this).edit().putString("drive_account", email).putBoolean("drive_enabled", true).apply()
        DriveSync.status(this, "อนุญาตแล้ว — กำลังตรวจสิทธิ์โฟลเดอร์และส่งไฟล์")
        DriveSync.recover(this)
        render()
    }
    private fun failed() {
        DriveSync.status(this, "AUTH_REQUIRED: ตรวจ OAuth package/SHA-1, บัญชีทดสอบ และ Drive API — แชร์เองได้")
        Ui.alert(this, "ยังเชื่อม Google ไม่สำเร็จ", "ตรวจการตั้งค่า OAuth ของแอป บัญชี Google และสิทธิ์โฟลเดอร์\nการแคป / History / รายงาน / แชร์เองยังทำงาน")
        render()
    }
}
