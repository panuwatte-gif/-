package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri

/** Hand-offs to other apps (Google Drive, LINE, Gallery ...) through the Android share sheet. */
object Share {

    fun images(a: Activity, uris: List<Uri>, title: String = "ส่งภาพหลักฐาน") {
        if (uris.isEmpty()) {
            Ui.toast(a, "ไม่มีภาพให้ส่ง")
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "image/jpeg"
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(a, Intent.createChooser(intent, title))
    }

    fun file(a: Activity, uri: Uri, mime: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).setType(mime)
        intent.clipData = ClipData.newRawUri("", uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(a, Intent.createChooser(intent, title))
    }

    fun copy(a: Activity, text: String) {
        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ReadyProof", text))
        Ui.toast(a, "คัดลอกแล้ว วางใน LINE / Google Sheets ได้เลย")
    }

    fun view(a: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(a, intent)
    }

    private fun start(a: Activity, intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Ui.toast(a, "ไม่มีแอปที่เปิดได้ในเครื่องนี้")
        }
    }
}
