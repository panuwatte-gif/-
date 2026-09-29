package io.github.panuwattegif.readyproof.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

object TimeResolve {
    /** History shows only a clock time; the finish is the latest such time not after [ref]. */
    fun latestAtOrBefore(time: LocalTime, ref: LocalDateTime): LocalDateTime {
        val sameDay = ref.toLocalDate().atTime(time)
        return if (!sameDay.isAfter(ref)) sameDay else sameDay.minusDays(1)
    }

    fun parseHHmm(s: String): LocalTime? = try {
        LocalTime.parse(s)
    } catch (e: Exception) {
        null
    }

    fun toLocal(ms: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    fun toMillis(t: LocalDateTime, zone: ZoneId): Long = t.atZone(zone).toInstant().toEpochMilli()
}

/** One screenshot that supports "the food was ready" for an order. */
data class Evidence(
    val record: Record,
    /** READY (seen waiting for driver), PRESS (button tap) or VISIBLE (hand capture). */
    val type: ObsType,
    val status: String?,
    val countdown: String?,
) {
    val t: Long get() = record.t
}

/** An order Grab marked as delayed, with whatever evidence was found for it. */
data class DelayCase(
    val gf: String,
    val delayMin: Int?,
    val doneAt: LocalDateTime?,
    val firstSeen: Long,
    val evidence: List<Evidence>,
    /** History screenshots that show Grab's "ล่าช้าไป X นาที" for this order. */
    val delayShots: List<Record>,
) {
    val hasEvidence: Boolean get() = evidence.isNotEmpty()

    /**
     * The representative shot of one kind: the first READY seen (closest to the moment it became
     * ready) but the LAST button tap, because an earlier "tap" can only be a mis-read (e.g. the
     * tab of the same name) while the real press is the one right before the order moved on.
     */
    fun pick(type: ObsType): Evidence? {
        val of = evidence.filter { it.type == type }
        return if (type == ObsType.PRESS) of.maxByOrNull { it.t } else of.minByOrNull { it.t }
    }

    /** At most one READY and one PRESS (or hand capture) shot: enough to prove the case. */
    fun bestShots(): List<Record> =
        listOfNotNull(pick(ObsType.READY), pick(ObsType.PRESS) ?: pick(ObsType.VISIBLE))
            .map { it.record }.distinctBy { it.id }
}

data class DailyReport(
    val date: LocalDate,
    /** Finished orders seen in the history list for this date. */
    val completedSeen: Int,
    val cases: List<DelayCase>,
    /** Distinct order numbers whose button tap was captured this date. */
    val pressedOrders: Int,
    /** Distinct order numbers photographed in a ready state this date. */
    val readyOrders: Int,
) {
    val delayed: Int get() = cases.size
    val withEvidence: List<DelayCase> get() = cases.filter { it.hasEvidence }
    val withoutEvidence: List<DelayCase> get() = cases.filter { !it.hasEvidence }

    /** Percentage of [completedSeen], or null when nothing was seen. */
    fun pct(n: Int): Double? = if (completedSeen > 0) n * 100.0 / completedSeen else null

    /** Screenshots to hand over: the history shots plus the best evidence of every proven case. */
    fun shareRecords(): List<Record> =
        (cases.flatMap { it.delayShots } + withEvidence.flatMap { it.bestShots() })
            .filter { it.uri != null }.distinctBy { it.id }
}

object ReportBuilder {
    private class Acc(
        val gf: String,
        val doneAt: LocalDateTime?,
        val date: LocalDate,
        val firstSeen: Long,
    ) {
        var delayed = false
        var delayMin: Int? = null
        val delayShots = ArrayList<Record>()
    }

    /** [records] should cover the day before and after [date] too, so windows can cross midnight. */
    fun build(records: List<Record>, date: LocalDate, zone: ZoneId, cfg: Config): DailyReport {
        val sorted = records.sortedBy { it.t }
        val acc = LinkedHashMap<String, Acc>()
        for (r in sorted) {
            val seen = TimeResolve.toLocal(r.t, zone)
            for (item in r.items) {
                if (item.type != ObsType.DONE && item.type != ObsType.DELAY) continue
                val doneAt = item.doneAt?.let { TimeResolve.parseHHmm(it) }
                    ?.let { TimeResolve.latestAtOrBefore(it, seen.plusMinutes(1)) }
                val day = doneAt?.toLocalDate() ?: seen.toLocalDate()
                val key = "${item.gf}|${doneAt ?: day}"
                val a = acc.getOrPut(key) { Acc(item.gf, doneAt, day, r.t) }
                if (item.type == ObsType.DELAY) {
                    a.delayed = true
                    item.delayMin?.let { m -> a.delayMin = maxOf(a.delayMin ?: 0, m) }
                    if (r.kind == RecordKind.DELAY && r.uri != null && a.delayShots.none { it.id == r.id }) a.delayShots += r
                }
            }
        }

        val onDate = acc.values.filter { it.date == date }
        val evidenceRecords = sorted.filter {
            it.uri != null && (it.kind == RecordKind.PRESS || it.kind == RecordKind.READY || it.kind == RecordKind.MANUAL)
        }
        val windowMs = cfg.evidenceWindowHours * 3600_000L
        val dayStart = TimeResolve.toMillis(date.atStartOfDay(), zone)
        val dayEnd = TimeResolve.toMillis(date.plusDays(1).atStartOfDay(), zone)

        val cases = onDate.filter { it.delayed }.map { a ->
            val doneMs = a.doneAt?.let { TimeResolve.toMillis(it, zone) }
            val from = if (doneMs != null) doneMs - windowMs else dayStart
            val to = if (doneMs != null) doneMs + 5 * 60_000L else a.firstSeen
            val ev = evidenceRecords.asSequence()
                .filter { it.t in from..to }
                .mapNotNull { evidenceFor(it, a.gf) }
                .sortedWith(compareBy<Evidence>({ rank(it.type) }, { it.t }))
                .toList()
            DelayCase(a.gf, a.delayMin, a.doneAt, a.firstSeen, ev, a.delayShots)
        }.sortedWith(compareBy<DelayCase>({ it.doneAt == null }, { it.doneAt }, { it.gf }))

        val dayRecords = sorted.filter { it.t in dayStart until dayEnd }
        val pressed = dayRecords.filter { it.kind == RecordKind.PRESS }
            .flatMap { r -> r.items.filter { it.type == ObsType.PRESS }.map { it.gf } }.distinct().size
        val ready = dayRecords.filter { it.kind == RecordKind.READY }
            .flatMap { r -> r.items.filter { it.type == ObsType.READY }.map { it.gf } }.distinct().size

        return DailyReport(date, onDate.size, cases, pressed, ready)
    }

    private fun evidenceFor(r: Record, gf: String): Evidence? {
        val item = r.items.firstOrNull { it.gf == gf }
        return when (r.kind) {
            RecordKind.PRESS -> item?.takeIf { it.type == ObsType.PRESS }
                ?.let { Evidence(r, ObsType.PRESS, r.click ?: it.status, it.countdown) }
            RecordKind.READY -> item?.takeIf { it.type == ObsType.READY }
                ?.let { Evidence(r, ObsType.READY, it.status, null) }
            RecordKind.MANUAL -> if (item != null || r.visible.contains(gf)) {
                Evidence(r, ObsType.VISIBLE, item?.status, null)
            } else {
                null
            }
            else -> null
        }
    }

    private fun rank(type: ObsType): Int = when (type) {
        ObsType.READY -> 0
        ObsType.PRESS -> 1
        else -> 2
    }
}

/** Text forms of a report: the chat summary and the spreadsheet export. */
object ReportText {
    fun date(d: LocalDate): String = Parsers.pad2(d.dayOfMonth) + "/" + Parsers.pad2(d.monthValue) + "/" + d.year

    fun time(ms: Long, zone: ZoneId): String = Parsers.hhmm(TimeResolve.toLocal(ms, zone).toLocalTime())

    fun pct(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.1f%%", v)

    fun summary(r: DailyReport, zone: ZoneId): String = buildString {
        append("สรุปออเดอร์ล่าช้า วันที่ ").append(date(r.date)).append('\n')
        append("• ออเดอร์ที่เห็นในหน้าประวัติ: ").append(r.completedSeen).append('\n')
        append("• Grab ระบุล่าช้า: ").append(r.delayed).append(" (").append(pct(r.pct(r.delayed))).append(")\n")
        append("• มีหลักฐานว่ากดพร้อมจัดส่งแล้ว: ").append(r.withEvidence.size).append('\n')
        append("• ไม่มีหลักฐาน: ").append(r.withoutEvidence.size)
            .append(" → ล่าช้าจริง ").append(pct(r.pct(r.withoutEvidence.size))).append('\n')
        append("• วันนี้กดพร้อมจัดส่ง ").append(r.pressedOrders).append(" ออเดอร์ · มีภาพ READY ")
            .append(r.readyOrders).append(" ออเดอร์\n")
        if (r.withEvidence.isNotEmpty()) {
            append("\n✅ มีหลักฐาน\n")
            r.withEvidence.forEach { append(caseLine(it, zone)).append('\n') }
        }
        if (r.withoutEvidence.isNotEmpty()) {
            append("\n❌ ไม่มีหลักฐาน\n")
            r.withoutEvidence.forEach { append(caseLine(it, zone)).append('\n') }
        }
    }

    fun caseLine(c: DelayCase, zone: ZoneId): String = buildString {
        append(c.gf)
        append(" ล่าช้า ").append(c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        c.doneAt?.let { append(" (เสร็จ ").append(Parsers.hhmm(it.toLocalTime())).append(')') }
        val parts = ArrayList<String>()
        c.pick(ObsType.READY)?.let { e ->
            parts += "READY " + time(e.t, zone) + (e.status?.let { " \"$it\"" } ?: "")
        }
        c.pick(ObsType.PRESS)?.let { e ->
            parts += "กดพร้อมจัดส่ง " + time(e.t, zone) + (e.countdown?.let { " (เหลือ $it)" } ?: "")
        }
        if (parts.isEmpty()) c.pick(ObsType.VISIBLE)?.let { e -> parts += "แคปเอง " + time(e.t, zone) }
        if (parts.isNotEmpty()) append(" — ").append(parts.joinToString(" · "))
    }

    fun csv(r: DailyReport, zone: ZoneId): String = buildString {
        append('﻿') // BOM so Excel shows Thai correctly; Google Sheets ignores it
        append("date,gf,delay_min,done_at,has_evidence,ready_time,ready_status,press_time,press_countdown,files\n")
        for (c in r.cases) {
            val ready = c.pick(ObsType.READY)
            val press = c.pick(ObsType.PRESS)
            val files = c.bestShots().mapNotNull { it.file } + c.delayShots.mapNotNull { it.file }
            val row = listOf(
                r.date.toString(),
                c.gf,
                c.delayMin?.toString().orEmpty(),
                c.doneAt?.let { Parsers.hhmm(it.toLocalTime()) }.orEmpty(),
                if (c.hasEvidence) "yes" else "no",
                ready?.let { time(it.t, zone) }.orEmpty(),
                ready?.status.orEmpty(),
                press?.let { time(it.t, zone) }.orEmpty(),
                press?.countdown.orEmpty(),
                files.distinct().joinToString("; "),
            )
            append(row.joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
