package io.github.panuwattegif.readyproof

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * Hands a saved screenshot to another app (Drive, LINE ...) under the evidence-set name the shop
 * files it by ("GF-613_READY.jpg"), without making a copy. One screenshot can appear under
 * several names, e.g. one History shot that shows two delayed orders.
 *
 * content://<authority>/<mediaStore id>/<file name>
 * Not exported: other apps can only open the exact links granted to them by a share.
 */
class EvidenceProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.github.panuwattegif.readyproof.evidence"

        /** [media] is a MediaStore image uri written by this app. */
        fun uriFor(media: Uri, displayName: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(ContentUris.parseId(media).toString())
                .appendPath(displayName)
                .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = sizeOf(mediaUri(uri))
        val row = columns.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                OpenableColumns.SIZE -> size
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        return context!!.contentResolver.openFileDescriptor(mediaUri(uri), "r")
    }

    private fun mediaUri(uri: Uri): Uri {
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: throw IllegalArgumentException("bad uri $uri")
        return ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
    }

    private fun sizeOf(media: Uri): Long? = try {
        context!!.contentResolver.query(media, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
