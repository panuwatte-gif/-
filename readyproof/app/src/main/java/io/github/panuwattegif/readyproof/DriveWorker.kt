package io.github.panuwattegif.readyproof

import android.accounts.Account
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import io.github.panuwattegif.readyproof.core.Shop
import io.github.panuwattegif.readyproof.core.NightlyUploads
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class DriveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!io.github.panuwattegif.readyproof.core.ManualWorkflow.autoUpload) return Result.success()
        // Immediate and periodic workers have different unique names. Serialize only uploads,
        // never capture/staging, so both cannot allocate different IDs for the same pending entry.
        if (!uploadLock.tryLock()) return Result.retry()
        return try { uploadPending() } finally { uploadLock.unlock() }
    }

    private fun uploadPending(): Result {
        val ctx = applicationContext
        val prefs = ConfigStore.prefs(ctx)
        if (!prefs.getBoolean("drive_enabled", false)) return Result.success()
        DriveSync.recover(ctx)
        val shop = ShopStore.get(ctx) ?: return Result.success()
        val pending = DriveSync.releasedEntries(ctx, shop.id).filter { it.state != "UPLOADED" }
        if (pending.isEmpty()) return Result.success() // No Google auth/API calls for daytime staging.
        val account = prefs.getString("drive_account", null) ?: run {
            DriveSync.status(ctx, "ต้องเชื่อมบัญชี Google — แคปและแชร์เองยังทำงาน")
            return Result.success()
        }
        var token: String? = null
        try {
            val auth = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(DriveAuth.request(account)), 30, TimeUnit.SECONDS)
            if (auth.hasResolution()) {
                DriveSync.status(ctx, "AUTH_REQUIRED: เปิดตั้งค่า Drive แล้วเชื่อม Google อีกครั้ง")
                return Result.success() // Never open a consent UI from background work.
            }
            token = auth.accessToken ?: error("AUTH_REQUIRED")
            val api = DriveApi(token)
            api.verifyFolder(shop.folderId)
            var failed = false
            // Bind each entry, not the current UI selection. Refuse unknown or mismatched shops.
            for (original in pending.sortedBy { it.batchMembers != null }) {
                if (isStopped || !prefs.getBoolean("drive_enabled", false)) return Result.retry()
                var entry = original
                try {
                    if (entry.batchMembers != null && !NightlyUploads.markerReady(
                        NightlyUploads.FileState(entry.key, entry.shopId, false, entry.batchMembers),
                        DriveSync.fileStates(DriveSync.entries(ctx)))) {
                        failed = true
                        continue // UPLOAD_DONE is sent only after every member is remotely verified.
                    }
                    require(Shop.fromId(entry.shopId)?.folderId == shop.folderId)
                    if (entry.remoteId == null) {
                        entry = entry.copy(remoteId = api.generateId())
                        // Persist ID BEFORE request: retries after a timeout reuse the same Drive ID.
                        DriveSync.write(ctx, entry)
                    }
                    api.upload(entry, DriveSync.payload(ctx, entry.key), shop.folderId)
                    DriveSync.write(ctx, entry.copy(state = "UPLOADED", error = null))
                } catch (e: DriveApi.HttpError) {
                    DriveSync.write(ctx, entry.copy(state = "RETRY", error = "HTTP ${e.code}"))
                    if (e.code == 401) throw e
                    failed = true // One broken file does not prevent other valid files being uploaded.
                } catch (e: Exception) {
                    DriveSync.write(ctx, entry.copy(state = "RETRY", error = e.javaClass.simpleName))
                    failed = true
                }
            }
            DriveSync.status(ctx, if (failed) "บางไฟล์ยังส่งไม่ได้ — จะลองใหม่ / แชร์เองได้" else "ส่งไฟล์ที่มีครบแล้ว (ความครบของหลักฐานดูในรายงาน)")
            return if (failed) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (e is DriveApi.HttpError && e.code == 401 && token != null) {
                runCatching { Tasks.await(Identity.getAuthorizationClient(ctx).clearToken(
                    ClearTokenRequest.builder().setToken(token).build()), 10, TimeUnit.SECONDS) }
            }
            DriveSync.status(ctx, "ส่ง Drive ไม่สำเร็จ: ${if (e is DriveApi.HttpError) "HTTP ${e.code}" else e.javaClass.simpleName} — จะลองใหม่ / แชร์เองได้")
            return Result.retry()
        }
    }

    companion object { private val uploadLock = ReentrantLock() }
}

object DriveAuth {
    // Existing fixed folders were not created by this app: drive.file alone cannot grant access by ID.
    // Full Drive is a restricted scope, requiring OAuth console configuration and possibly verification.
    fun request(email: String) = AuthorizationRequest.builder()
        .setAccount(Account(email, "com.google"))
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive"))).build()
}
