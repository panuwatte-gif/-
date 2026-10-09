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
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class UploadEntry(val key: String, val shopId: String?, val name: String, val mime: String,
    val md5: String, val remoteId: String? = null, val state: String = "PENDING", val error: String? = null,
    val batchMembers: List<String>? = null)

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
    private fun validPhoto(r: Record) = r.uri != null && r.items.any {
        it.type in listOf(ObsType.READY, ObsType.DELAY, ObsType.DONE, ObsType.CANCELLED)
    }

    fun offerRecord(ctx: Context, r: Record) {
        val app = ctx.applicationContext
        // All exceptions are confined to the sidecar executor; the local capture has already committed.
        runCatching { io.execute {
            runCatching {
                if (validPhoto(r)) {
                    val bytes = app.contentResolver.openInputStream(Uri.parse(r.uri!!))?.use { it.readBytes() }
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

    private fun stage(ctx: Context, shopId: String?, name: String, mime: String, bytes: ByteArray,
        batchMembers: List<String>? = null): String = synchronized(lock) {
        require(bytes.isNotEmpty())
        val key = digest(("${shopId ?: "UNKNOWN"}|$name|").toByteArray() + bytes, "SHA-256")
        if (meta(ctx, key).exists()) return@synchronized key
        val af = AtomicFile(payload(ctx, key))
        val stream = af.startWrite()
        try { stream.write(bytes); af.finishWrite(stream) } catch (e: Exception) { af.failWrite(stream); throw e }
        val remoteName = Naming.uploadName(shopId, name, key, mime)
        write(ctx, UploadEntry(key, shopId, remoteName, mime, digest(bytes, "MD5"), batchMembers = batchMembers))
        key
    }

    /** Release after a local History report commits. Missing photos never block the valid subset. */
    fun offerDailyBatch(
        ctx: Context,
        day: LocalDate,
        shopId: String?,
        records: List<Record>,
        text: String,
        manifest: String,
        diagnostics: String? = null,
    ) {
        val app = ctx.applicationContext
        runCatching { io.execute {
            runCatching {
                require(Shop.fromId(shopId) != null)
                if (!NightlyUploads.canRelease(day, LocalDateTime.now(ZoneId.of("Asia/Bangkok")))) {
                    status(app, "เก็บชุดทดสอบในเครื่องแล้ว — ส่งอัตโนมัติหลังปิดร้านเท่านั้น")
                    return@execute
                }
                val keys = linkedSetOf<String>()
                val photoRecords = linkedMapOf<String, MutableList<String>>()
                val missing = mutableListOf<String>()
                for (r in records.filter { it.shopId == shopId && validPhoto(it) && RecordStore.dateOf(it.t) == day }) {
                    runCatching {
                        val bytes = app.contentResolver.openInputStream(Uri.parse(r.uri!!))?.use { it.readBytes() }
                            ?: error("missing local photo")
                        val key = stage(app, shopId, r.file ?: "${r.id}.jpg", "image/jpeg", bytes)
                        keys += key
                        photoRecords.getOrPut(key) { mutableListOf() }.add(r.id)
                    }.onFailure { missing += r.id }
                }
                keys += stage(app, shopId, "summary-$day.txt", "text/plain", text.toByteArray())
                keys += stage(app, shopId, "manifest-$day.json", "application/json", manifest.toByteArray())
                // What the phone did that day, for remote troubleshooting (no photos inside).
                diagnostics?.takeIf { it.isNotBlank() }?.let {
                    keys += stage(app, shopId, "diagnostics-$day.txt", "text/plain", it.toByteArray())
                }
                val files = entries(app).associateBy { it.key }
                // Deterministic payload: unchanged passes reuse the same marker and uploaded files.
                val marker = Json.write(linkedMapOf("schema" to 1, "status" to "UPLOAD_DONE",
                    "shopId" to shopId, "date" to day.toString(), "sourceManifestMD5" to digest(manifest.toByteArray(), "MD5"),
                    "proofCompleteness" to "READ_SOURCE_MANIFEST", "localPhotoReadFailures" to missing,
                    "files" to keys.sorted().map { key -> linkedMapOf("key" to key, "name" to files[key]?.name,
                        "md5" to files[key]?.md5, "recordIds" to photoRecords[key]?.distinct()?.sorted()) }))
                // Journal this marker last: a crash during staging cannot release a partial batch.
                stage(app, shopId, "UPLOAD_DONE-$day.json", "application/json", marker.toByteArray(), keys.sorted())
                status(app, "เตรียมชุดหลังปิดร้าน $day แล้ว — ส่งเฉพาะไฟล์ที่มีจริง")
                schedule(app)
            }.onFailure {
                Diagnostics.error(app, "offerDailyBatch", it)
                status(app, "เตรียมชุดส่งไม่ได้: ${it.javaClass.simpleName} — ภาพและรายงานยังอยู่ในเครื่อง แชร์เองได้")
            }
        } }
    }

    fun fileStates(entries: List<UploadEntry>) = entries.map {
        NightlyUploads.FileState(it.key, it.shopId, it.state == "UPLOADED", it.batchMembers)
    }

    fun releasedEntries(ctx: Context, shopId: String): List<UploadEntry> {
        val all = entries(ctx)
        val keys = NightlyUploads.releasedKeys(fileStates(all), shopId)
        return all.filter { it.key in keys }
    }

    fun entries(ctx: Context): List<UploadEntry> = synchronized(lock) {
        root(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
            runCatching {
                val m = Json.parseObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
                UploadEntry(m.str("key")!!, m.str("shopId"), m.str("name")!!, m.str("mime")!!,
                    m.str("md5")!!, m.str("remoteId"), m.str("state") ?: "PENDING", m.str("error"), m.strList("batchMembers"))
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
                "state" to e.state, "error" to e.error, "batchMembers" to e.batchMembers)).toByteArray())
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
                        if (r.shopId == ShopStore.get(app)?.id && validPhoto(r)) {
                            runCatching {
                                val bytes = app.contentResolver.openInputStream(Uri.parse(r.uri!!))?.use { it.readBytes() }
                                if (bytes != null) stage(app, r.shopId, r.file ?: "${r.id}.jpg", "image/jpeg", bytes)
                            }
                        }
                    } }
                }
            }.onFailure { status(app, "ตรวจคิวเดิมไม่สำเร็จ — หลักฐานในเครื่องยังใช้ได้") }
            DailyExport.recoverBatches(app)
            schedule(app)
        } }
    }

    fun summary(ctx: Context): String {
        val shop = ShopStore.get(ctx)
        val es = entries(ctx).filter { it.shopId == shop?.id }
        val released = shop?.let { releasedEntries(ctx, it.id) } ?: emptyList()
        return "Drive: ส่งแล้ว ${es.count { it.state == "UPLOADED" }} / คิวหลังปิดร้าน ${released.count { it.state != "UPLOADED" }} / เก็บรอในเครื่อง ${es.count { it.state != "UPLOADED" && it.key !in released.map { r -> r.key } }}\n" +
            ConfigStore.prefs(ctx).getString("drive_status", "ยังไม่เชื่อม Google")
    }

    fun shareUri(ctx: Context, e: UploadEntry): Uri = FileProvider.getUriForFile(ctx,
        "${ctx.packageName}.exports", payload(ctx, e.key))
}
