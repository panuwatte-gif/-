package io.github.panuwattegif.readyproof

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/**
 * Screenshots go to Pictures/ReadyProof (visible in Gallery / Google Photos, kept if the app is
 * removed); exports go to Download/ReadyProof. No storage permission is needed for either.
 */
object MediaSaver {
    val PICTURES_PATH = Environment.DIRECTORY_PICTURES + "/ReadyProof"
    val DOWNLOADS_PATH = Environment.DIRECTORY_DOWNLOADS + "/ReadyProof"

    private fun images() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun saveJpeg(ctx: Context, bitmap: Bitmap, name: String, takenAt: Long, quality: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PICTURES_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return write(ctx, images(), values) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("JPEG compress failed")
        }
    }

    fun saveDownload(ctx: Context, name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOADS_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return write(ctx, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) { it.write(bytes) }
    }

    private fun write(ctx: Context, collection: Uri, values: ContentValues, body: (java.io.OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed (storage full?)")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw IOException("cannot open output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Deletes this app's screenshots taken before [cutoffMs]; files it no longer owns are skipped. */
    fun deleteOlderThan(ctx: Context, cutoffMs: Long): Int {
        val resolver = ctx.contentResolver
        val collection = images()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?"
        val args = arrayOf("$PICTURES_PATH%", cutoffMs.toString())
        var deleted = 0
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                deleted += runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
            }
        }
        return deleted
    }
}
