package io.github.panuwattegif.readyproof.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** Loads small previews off the main thread; a deleted image shows as a grey box. */
class Thumbs(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun into(view: ImageView, uri: String?) {
        view.tag = uri
        if (uri == null) {
            view.setImageDrawable(ColorDrawable(Ui.LINE))
            return
        }
        cache.get(uri)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        try {
            pool.execute {
                val bmp = try {
                    app.contentResolver.loadThumbnail(Uri.parse(uri), Size(270, 600), null)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) cache.put(uri, bmp)
                main.post {
                    if (view.tag == uri) {
                        if (bmp != null) view.setImageBitmap(bmp) else view.setImageDrawable(ColorDrawable(Ui.LINE))
                    }
                }
            }
        } catch (ignored: Exception) {
            // pool already closed (screen is being destroyed)
        }
    }

    fun close() {
        pool.shutdownNow()
    }
}
