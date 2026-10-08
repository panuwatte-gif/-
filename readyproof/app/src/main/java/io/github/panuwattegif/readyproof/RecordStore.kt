package io.github.panuwattegif.readyproof

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.RecordCodec
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The capture log: one append-only JSON-lines file per day in app storage.
 * A broken line (e.g. power cut mid-write) is skipped, never fatal.
 */
object RecordStore {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "records").apply { mkdirs() }

    private fun file(ctx: Context, date: LocalDate) = File(dir(ctx), "$date.jsonl")

    fun dateOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()

    fun append(ctx: Context, r: Record) {
        synchronized(lock) {
            FileOutputStream(file(ctx, dateOf(r.t)), true).use {
                it.write((RecordCodec.encode(r) + "\n").toByteArray(Charsets.UTF_8))
            }
        }
        main.post { listeners.forEach { it() } }
    }

    fun load(ctx: Context, date: LocalDate): List<Record> = synchronized(lock) {
        val f = file(ctx, date)
        if (!f.exists()) return emptyList()
        f.readLines(Charsets.UTF_8).mapNotNull { RecordCodec.decode(it) }
    }

    fun loadRange(ctx: Context, from: LocalDate, to: LocalDate): List<Record> {
        val out = ArrayList<Record>()
        var d = from
        while (!d.isAfter(to)) {
            out += load(ctx, d)
            d = d.plusDays(1)
        }
        return out
    }

    /** History may be captured days later; its business date does not change the log filename. */
    fun loadReport(ctx: Context, day: LocalDate): List<Record> = synchronized(lock) {
        val from = day.minusDays(1)
        val to = maxOf(day.plusDays(1), LocalDate.now())
        dir(ctx).listFiles().orEmpty().mapNotNull { f ->
            val date = runCatching { LocalDate.parse(f.name.removeSuffix(".jsonl")) }.getOrNull()
            if (date == null || date.isBefore(from) || date.isAfter(to)) null else date
        }.sorted().flatMap { load(ctx, it) }
    }

    fun deleteBefore(ctx: Context, date: LocalDate): Int = synchronized(lock) {
        var n = 0
        dir(ctx).listFiles()?.forEach { f ->
            val d = runCatching { LocalDate.parse(f.name.removeSuffix(".jsonl")) }.getOrNull() ?: return@forEach
            if (d.isBefore(date) && f.delete()) n++
        }
        n
    }

    /** Called on the main thread after every append. */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }
}
