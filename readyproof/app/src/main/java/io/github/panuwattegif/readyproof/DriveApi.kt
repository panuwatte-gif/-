package io.github.panuwattegif.readyproof

import io.github.panuwattegif.readyproof.core.Json
import io.github.panuwattegif.readyproof.core.Shop
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Official Drive v3 only. No Grab HTTP calls, folder creation, deletion or sharing changes. */
class DriveApi(private val token: String,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) {
    class HttpError(val code: Int) : Exception("Drive HTTP $code")
    companion object {
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val SPREADSHEET = "application/vnd.google-apps.spreadsheet"
    }
    private val base = "https://www.googleapis.com/drive/v3"
    private fun connection(url: String): HttpURLConnection = openConnection(url).apply {
        require(url.startsWith("https://www.googleapis.com/"))
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("Authorization", "Bearer $token")
    }
    private fun json(url: String): Map<String, Any?> {
        val c = connection(url)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            return Json.parseObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    fun verifyFolder(folder: String) {
        require(Shop.entries.any { it.folderId == folder })
        val m = json("$base/files/$folder?fields=id,mimeType,trashed,capabilities(canAddChildren)&supportsAllDrives=true")
        require(m["id"] == folder && m["mimeType"] == "application/vnd.google-apps.folder" && m["trashed"] == false)
        require((m["capabilities"] as? Map<*, *>)?.get("canAddChildren") == true) { "ไม่มีสิทธิ์เพิ่มไฟล์ในโฟลเดอร์ร้าน" }
    }
    fun generateId(): String = ((json("$base/files/generateIds?count=1&space=drive&type=files")["ids"] as? List<*>)
        ?.firstOrNull() as? String)?.also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) } ?: error("Drive ID missing")

    /**
     * Replaces the content of the shop's own Google Sheet (only a sheet named in [Shop]) with an
     * .xlsx: Drive turns it into the sheet's tabs; the sheet's link, owner and sharing stay as they are.
     */
    fun replaceSpreadsheet(fileId: String, xlsx: ByteArray) {
        require(Shop.entries.any { it.sheetId == fileId })
        require(xlsx.isNotEmpty())
        val c = connection("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media&supportsAllDrives=true&fields=id,mimeType,trashed")
        try {
            try {
                c.requestMethod = "PATCH"
            } catch (e: java.net.ProtocolException) {
                // Google's own way round a connection that cannot send PATCH.
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            c.doOutput = true
            c.setRequestProperty("Content-Type", XLSX)
            c.setFixedLengthStreamingMode(xlsx.size)
            c.outputStream.use { it.write(xlsx) }
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            val m = Json.parseObject(c.inputStream.bufferedReader().use { it.readText() })
            require(m["id"] == fileId && m["mimeType"] == SPREADSHEET && m["trashed"] != true) { "ตรวจยืนยัน Google Sheet ไม่ผ่าน" }
        } finally { c.disconnect() }
    }

    fun upload(e: UploadEntry, file: File, folder: String) {
        require(Shop.fromId(e.shopId)?.folderId == folder)
        require(file.isFile && file.length() > 0) { "ไฟล์ในคิวหาย — แชร์ภาพต้นฉบับเองได้" }
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == e.md5) { "ไฟล์ในคิวไม่ตรงต้นฉบับ" }
        val id = e.remoteId ?: error("persist remote ID before upload")
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val boundary = "readyproof_${e.key}"
        val metadata = Json.write(linkedMapOf("id" to id, "name" to e.name, "parents" to listOf(folder),
            "appProperties" to mapOf("readyproofKey" to e.key, "shopId" to e.shopId)))
        val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: ${e.mime}\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val c = connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            c.setFixedLengthStreamingMode(prefix.size + file.length() + suffix.size)
            c.outputStream.use { out -> out.write(prefix); file.inputStream().use { it.copyTo(out) }; out.write(suffix) }
            if (c.responseCode !in 200..299 && c.responseCode != 409) throw HttpError(c.responseCode)
            // A lost response can leave the file on Drive. Retry with its persisted ID, then verify.
        } finally { c.disconnect() }
        val saved = json("$base/files/$id?fields=id,parents,appProperties,md5Checksum,trashed&supportsAllDrives=true")
        val properties = saved["appProperties"] as? Map<*, *>
        require(saved["id"] == id && saved["trashed"] == false && saved["md5Checksum"] == e.md5 &&
            (saved["parents"] as? List<*>)?.contains(folder) == true &&
            properties?.get("readyproofKey") == e.key && properties?.get("shopId") == e.shopId) { "ตรวจยืนยันไฟล์ไม่ผ่าน" }
    }
}
