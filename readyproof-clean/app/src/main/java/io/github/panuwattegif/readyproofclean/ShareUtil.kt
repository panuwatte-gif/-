package io.github.panuwattegif.readyproofclean

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareUtil {
    fun images(ctx: Context, uris: List<Uri>, title: String) {
        if (uris.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title))
    }

    fun textFile(ctx: Context, uri: Uri, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title))
    }
}
