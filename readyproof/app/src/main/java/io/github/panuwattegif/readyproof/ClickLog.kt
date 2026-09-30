package io.github.panuwattegif.readyproof

import android.content.Context
import io.github.panuwattegif.readyproof.core.Json
import io.github.panuwattegif.readyproof.core.bool
import io.github.panuwattegif.readyproof.core.long
import io.github.panuwattegif.readyproof.core.str

/**
 * The last taps inside the watched app, shown in Settings so the right button label can be
 * picked with one tap if Grab ever renames "พร้อมจัดส่ง".
 */
object ClickLog {
    data class Entry(
        val t: Long,
        val label: String,
        val className: String?,
        val viewId: String?,
        val matched: Boolean,
    )

    private const val MAX = 30
    private const val KEY = "click_log"
    private val entries = ArrayDeque<Entry>()
    private var loaded = false

    @Synchronized
    fun add(ctx: Context, e: Entry) {
        ensureLoaded(ctx)
        entries.addFirst(e)
        while (entries.size > MAX) entries.removeLast()
        save(ctx)
    }

    @Synchronized
    fun list(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        return entries.toList()
    }

    @Synchronized
    fun clear(ctx: Context) {
        entries.clear()
        loaded = true
        save(ctx)
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val text = ConfigStore.prefs(ctx).getString(KEY, null) ?: return
        try {
            (Json.parse(text) as? List<*>)?.forEach { any ->
                @Suppress("UNCHECKED_CAST")
                val m = any as? Map<String, Any?> ?: return@forEach
                entries.addLast(
                    Entry(
                        t = m.long("t") ?: 0L,
                        label = m.str("label").orEmpty(),
                        className = m.str("cls"),
                        viewId = m.str("id"),
                        matched = m.bool("m") ?: false,
                    )
                )
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }

    private fun save(ctx: Context) {
        val list = entries.map { mapOf("t" to it.t, "label" to it.label, "cls" to it.className, "id" to it.viewId, "m" to it.matched) }
        ConfigStore.prefs(ctx).edit().putString(KEY, Json.write(list)).apply()
    }
}
