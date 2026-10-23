package io.github.panuwattegif.readyproof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import io.github.panuwattegif.readyproof.core.DelayCase
import io.github.panuwattegif.readyproof.core.EvidenceSet
import io.github.panuwattegif.readyproof.core.GrabLimits
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.Parsers
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportText
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Evidence ready to send to Grab: one image per delayed order, its Ready-tab photo next to its
 * History row (as in the owner's pack Grab accepted), each under [GrabLimits.MAX_IMAGE_BYTES].
 * The original photos stay untouched in the gallery.
 */
object GrabPack {
    private const val PHOTO_H = 1200
    private const val MARGIN = 24
    private const val TITLE_H = 150
    private const val CAPTION_H = 120

    /** Builds the images (oldest order first); orders whose photos cannot be read are left out. */
    fun build(ctx: Context, day: LocalDate, sets: List<EvidenceSet>, delayOnly: List<DelayCase>, zone: ZoneId): List<File> {
        // Earlier days' images go; this day's are rewritten in place (a send may still be reading them).
        val root = File(ctx.cacheDir, "grab")
        root.listFiles()?.filter { it.name != day.toString() }?.forEach { it.deleteRecursively() }
        val dir = File(root, day.toString()).apply { mkdirs() }
        val out = ArrayList<File>()
        for (set in sets) {
            val c = set.case
            val ready = set.ready
            val readyLine = "หน้า พร้อมจัดส่ง เวลา " + ReportText.time(ready.t, zone)
            val readyStatus = c.readyShot?.status?.let { stripGf(it, c.gf) }
            val name = set.readyName.replace("_READY", "_Ready+History")
            image(ctx, dir, name, title(c, day), ReportText.grabTimeline(c, zone), listOfNotNull(
                Panel(ready, readyLine, readyStatus),
                set.delay?.let { Panel(it, "หน้า ประวัติ", historyLine(c)) },
            ))?.let { out += it }
        }
        for (c in delayOnly) {
            val delay = c.delayShot ?: continue
            image(ctx, dir, Naming.setName(c.gf, "History"), title(c, day), ReportText.grabTimeline(c, zone),
                listOf(Panel(delay, "หน้า ประวัติ", historyLine(c))))
                ?.let { out += it }
        }
        return out
    }

    private class Panel(val record: Record, val line1: String, val line2: String?)

    private fun title(c: DelayCase, day: LocalDate): String =
        c.gf + " · " + ReportText.date(day) + " · Grab ระบุล่าช้า" + (c.delayMin?.let { " $it นาที" } ?: "")

    private fun historyLine(c: DelayCase): String =
        listOfNotNull(c.doneAt?.let { "เสร็จสมบูรณ์ " + Parsers.hhmm(it.toLocalTime()) }, c.delayMin?.let { "ล่าช้า $it นาที" })
            .joinToString(" · ")

    private fun stripGf(status: String, gf: String): String = status.replace(gf, "").trim(' ', ',', '·')

    private fun image(ctx: Context, dir: File, name: String, title: String, times: String, panels: List<Panel>): File? {
        val bitmaps = panels.map { p -> load(ctx, p.record) }
        if (bitmaps.any { it == null }) {
            bitmaps.forEach { it?.recycle() }
            return null
        }
        val scaled = bitmaps.map { b -> b!!.let { Bitmap.createScaledBitmap(it, it.width * PHOTO_H / it.height, PHOTO_H, true).also { s -> if (s !== it) it.recycle() } } }
        val width = MARGIN + scaled.sumOf { it.width + MARGIN }
        val height = TITLE_H + PHOTO_H + CAPTION_H + MARGIN
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val titlePaint = paint(40f, Color.rgb(20, 20, 20), bold = true)
        drawFit(canvas, title, MARGIN.toFloat(), 62f, (width - 2 * MARGIN).toFloat(), titlePaint)
        // when it had to be ready, was pressed, picked up, delivered (Grab looks at times too)
        if (times.isNotBlank()) {
            drawFit(canvas, times, MARGIN.toFloat(), 122f, (width - 2 * MARGIN).toFloat(), paint(30f, Color.rgb(60, 60, 60), bold = false))
        }
        var x = MARGIN
        val line1 = paint(30f, Color.rgb(20, 20, 20), bold = true)
        val line2 = paint(28f, Color.rgb(90, 90, 90), bold = false)
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(200, 200, 200) }
        panels.forEachIndexed { i, p ->
            val b = scaled[i]
            canvas.drawBitmap(b, x.toFloat(), TITLE_H.toFloat(), null)
            canvas.drawRect(Rect(x, TITLE_H, x + b.width, TITLE_H + PHOTO_H), border)
            drawFit(canvas, p.line1, x.toFloat(), (TITLE_H + PHOTO_H + 44).toFloat(), b.width.toFloat(), line1)
            p.line2?.takeIf { it.isNotBlank() }?.let {
                drawFit(canvas, it, x.toFloat(), (TITLE_H + PHOTO_H + 90).toFloat(), b.width.toFloat(), line2)
            }
            x += b.width + MARGIN
            b.recycle()
        }
        val fitted = shrink(sheet)
        val bytes = encode(fitted)
        if (fitted !== sheet) fitted.recycle()
        sheet.recycle()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    private fun load(ctx: Context, r: Record): Bitmap? = try {
        r.uri?.let { u -> ctx.contentResolver.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) } }
    } catch (e: Exception) {
        Diagnostics.error(ctx, "grabPack", e)
        null
    }

    private fun shrink(b: Bitmap): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= GrabLimits.MAX_LONG_SIDE_PX) return b
        val f = GrabLimits.MAX_LONG_SIDE_PX.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /** The best JPEG quality that stays under the size limit. */
    private fun encode(b: Bitmap): ByteArray {
        var last = ByteArray(0)
        for (q in listOf(82, 75, 68, 60, 52, 45)) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            last = out.toByteArray()
            if (last.size <= GrabLimits.MAX_IMAGE_BYTES) return last
        }
        return last
    }

    private fun paint(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Draws [text] on one line, smaller if it does not fit [maxWidth]. */
    private fun drawFit(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, p: Paint) {
        val paint = Paint(p)
        while (paint.measureText(text) > maxWidth && paint.textSize > 18f) paint.textSize -= 1f
        canvas.drawText(text, x, y, paint)
    }
}
