package io.github.panuwattegif.readyproof

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.work.*
import io.github.panuwattegif.readyproof.core.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class UploadEntry(val key: String, val shopId: String?, val name: String, val mime: String,
    val md5: String, val remoteId: String? = null, val state: String = "PENDING", val error: String? = null)

/** Durable private copies + a journal. Capture never waits for network, auth or this executor. */
object DriveSync {
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private fun root(ctx: Context) = File(ctx.filesDir, "outbox").apply { mkdirs() }
    fun payload(ctx: Context, key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(root(ctx), "$key.data")
    }
    private fun meta(ctx: Context, key: String) = File(root(ctx), "$key.json")
    private fun digest(bytes: ByteArray, algorithm: String) = MessageDigest.getInstance(algorithm)
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun offerRecord(ctx: Context, r: Record) {
        val app = ctx.applicationContext
        // All exceptions are confined to the sidecar executor; the local capture has already committed.
        runCatching { io.execute {
            runCatching {
                if (r.uri != null && r.items.isNotEmpty()) {
                    val bytes = app.contentResolver.openInputStream(Uri.parse(r.uri))?.use { it.readBytes() }
                        ?: error("อ่านภาพในเครื่องไม่ได้")
                    stage(app, r.shopId, r.file ?: "${r.id}.jpg", "image/jpeg", bytes)
                }
            }.onFailure { status(app, "เตรียมอัปโหลดไม่ได้: ภาพยังอยู่ในเครื่อง — ${it.javaClass.simpleName}") }
            schedule(app)
        } }
    }

    fun offerBytes(ctx: Context, shopId: String?, name: String, mime: String, bytes: ByteArray) {
        val app = ctx.applicationContext
        runCatching { io.execute {
            runCatching { stage(app, shopId, name, mime, bytes) }
                .onFailure { status(app, "เตรียมส่งสรุปไม่ได้ — สรุปและภาพยังอยู่ในเครื่อง") }
            schedule(app)
        } }
    }

    private fun stage(ctx: Context, shopId: String?, name: String, mime: String, bytes: ByteArray) = synchronized(lock) {
        require(bytes.isNotEmpty())
        val key = digest(("${shopId ?: "UNKNOWN"}|$name|").toByteArray() + bytes, "SHA-256")
        if (meta(ctx, key).exists()) return@synchronized
        val af = AtomicFile(payload(ctx, key))
        val stream = af.startWrite()
        try { stream.write(bytes); af.finishWrite(stream) } catch (e: Exception) { af.failWrite(stream); throw e }
        val stem = name.substringBeforeLast('.', name).replace(Regex("[^A-Za-z0-9_-]"), "_")
        val ext = name.substringAfterLast('.', "dat")
        // Shop, date (in source filename), and content revision are visible to downstream consumers.
        val remoteName = "${shopId ?: "UNKNOWN"}_${stem}_${key.take(12)}.$ext"
        write(ctx, UploadEntry(key, shopId, remoteName, mime, digest(bytes, "MD5")))
    }

    fun entries(ctx: Context): List<UploadEntry> = synchronized(lock) {
        root(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
            runCatching {
                val m = Json.parseObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
                UploadEntry(m.str("key")!!, m.str("shopId"), m.str("name")!!, m.str("mime")!!,
                    m.str("md5")!!, m.str("remoteId"), m.str("state") ?: "PENDING", m.str("error"))
            }.getOrNull()
        } ?: emptyList()
    }

    fun write(ctx: Context, e: UploadEntry) = synchronized(lock) {
        require(e.key.matches(Regex("[a-f0-9]{64}")))
        val af = AtomicFile(meta(ctx, e.key))
        val out = af.startWrite()
        try {
            out.write(Json.write(linkedMapOf("key" to e.key, "shopId" to e.shopId,
                "name" to e.name, "mime" to e.mime, "md5" to e.md5, "remoteId" to e.remoteId,
                "state" to e.state, "error" to e.error)).toByteArray())
            af.finishWrite(out)
        } catch (ex: Exception) { af.failWrite(out); throw ex }
    }

    fun status(ctx: Context, message: String) {
        ConfigStore.prefs(ctx).edit().putString("drive_status", message).apply()
    }

    fun schedule(ctx: Context) {
        runCatching {
            if (!ConfigStore.prefs(ctx).getBoolean("drive_enabled", false)) return@runCatching
            val manager = WorkManager.getInstance(ctx)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniqueWork("readyproof-drive", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            manager.enqueueUniquePeriodicWork("readyproof-drive-recovery", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DriveWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        }.onFailure { status(ctx, "คิวอัปโหลดหยุดชั่วคราว — แชร์เองได้") }
    }

    fun recover(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { io.execute {
            // Reconcile the entire retained journal, including captures made while auto-upload was off.
            runCatching {
                val recordsDir = File(app.filesDir, "records")
                recordsDir.listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach { f ->
                    f.useLines { lines -> lines.mapNotNull(RecordCodec::decode).forEach { r ->
                        if (r.shopId == ShopStore.get(app)?.id && r.uri != null && r.items.isNotEmpty()) {
                            runCatching {
                                val bytes = app.contentResolver.openInputStream(Uri.parse(r.uri))?.use { it.readBytes() }
                                if (bytes != null) stage(app, r.shopId, r.file ?: "${r.id}.jpg", "image/jpeg", bytes)
                            }
                        }
                    } }
                }
            }.onFailure { status(app, "ตรวจคิวเดิมไม่สำเร็จ — หลักฐานในเครื่องยังใช้ได้") }
            schedule(app)
        } }
    }

    fun summary(ctx: Context): String {
        val shop = ShopStore.get(ctx)
        val es = entries(ctx).filter { it.shopId == shop?.id }
        return "Drive: ส่งแล้ว ${es.count { it.state == "UPLOADED" }} / รอ ${es.count { it.state != "UPLOADED" }}\n" +
            ConfigStore.prefs(ctx).getString("drive_status", "ยังไม่เชื่อม Google")
    }

    fun shareUri(ctx: Context, e: UploadEntry): Uri = FileProvider.getUriForFile(ctx,
        "${ctx.packageName}.exports", payload(ctx, e.key))
}
