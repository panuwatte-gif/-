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

    fun parseDay(s: String?): LocalDate? = try {
        s?.let { LocalDate.parse(it) }
    } catch (e: Exception) {
        null
    }

    fun toLocal(ms: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    fun toMillis(t: LocalDateTime, zone: ZoneId): Long = t.atZone(zone).toInstant().toEpochMilli()
}

/** What the Ready-tab evidence says about a delayed order. */
enum class Verdict(val label: String) {
    /** First shot shows the order waiting for a rider who had not arrived yet. */
    IN_TIME("หลักฐานว่าร้านกดทัน"),
    /** First shot already shows the rider there / Grab asking the shop to hurry. */
    LATE("หลักฐานว่าร้านช้าจริง"),
    /** No Ready-tab shot: no evidence or the phone missed it. Not proof of being late. */
    NO_EVIDENCE("ไม่มีหลักฐาน / ระบบจับไม่ได้"),
}

/** A screenshot showing the order inside the Ready tab, i.e. already marked done. */
data class Evidence(val record: Record, val status: String?) {
    val t: Long get() = record.t
}

/** An order Grab marked as delayed, with the evidence found for it. */
data class DelayCase(
    val gf: String,
    val delayMin: Int?,
    val doneAt: LocalDateTime?,
    val firstSeen: Long,
    /** READY shots of the order's stay in the Ready tab, earliest first. */
    val evidence: List<Evidence>,
    /** History screenshots that show this order as delayed, latest first. */
    val delayShots: List<Record>,
    val verdict: Verdict,
) {
    val hasEvidence: Boolean get() = evidence.isNotEmpty()

    /** The earliest READY shot: closest to the moment the order was marked done. */
    val readyShot: Evidence? get() = evidence.firstOrNull()

    val delayShot: Record? get() = delayShots.firstOrNull()
}

/** The files handed to Grab for one order, named the way the shop already files them. */
data class EvidenceSet(
    val case: DelayCase,
    val ready: Record,
    val readyName: String,
    val delay: Record?,
    val delayName: String?,
)

data class DailyReport(
    val date: LocalDate,
    /** Grab's own totals at the top of History; null when they were never read. */
    val grabCompleted: Int?,
    val grabCancelled: Int?,
    /** Finished orders read from the History list. */
    val historyRows: Int,
    val cases: List<DelayCase>,
    /** Finished orders that have at least one Ready-tab shot. */
    val rowsWithReady: Int,
    /** Finished orders without any Ready-tab shot. */
    val rowsWithoutReady: List<String>,
    /** Different orders photographed in the Ready tab that day. */
    val readyOrders: Int,
) {
    /** What the percentages are taken of: Grab's own count when known. */
    val base: Int get() = grabCompleted ?: historyRows
    val delayed: Int get() = cases.size
    val inTime: List<DelayCase> get() = cases.filter { it.verdict == Verdict.IN_TIME }
    val late: List<DelayCase> get() = cases.filter { it.verdict == Verdict.LATE }
    val noEvidence: List<DelayCase> get() = cases.filter { it.verdict == Verdict.NO_EVIDENCE }

    /** Every History row was read (the count matches Grab's own total). */
    val historyComplete: Boolean get() = grabCompleted != null && historyRows >= grabCompleted

    fun pct(n: Int): Double? = if (base > 0) n * 100.0 / base else null

    /** Delay rate as Grab counts it: every delayed order. */
    val grabPct: Double? get() = pct(delayed)

    /** Delay rate the shop should get: orders proven done in time are not late. */
    val realPct: Double? get() = pct(delayed - inTime.size)

    /**
     * One set per order with a Ready shot: GF-xxx_READY.jpg + GF-xxx_DELAY.jpg. When the same
     * order number was used twice that day, the finish time is added ("GF-613_1147_READY.jpg").
     */
    fun sets(verdict: Verdict): List<EvidenceSet> {
        val repeated = cases.groupingBy { it.gf }.eachCount().filterValues { it > 1 }.keys
        return cases.filter { it.verdict == verdict && it.readyShot != null }.map { c ->
            val tag = if (c.gf in repeated && c.doneAt != null) {
                c.gf + "_" + Parsers.pad2(c.doneAt.hour) + Parsers.pad2(c.doneAt.minute)
            } else {
                c.gf
            }
            val delay = c.delayShot
            EvidenceSet(
                case = c,
                ready = c.readyShot!!.record,
                readyName = Naming.setName(tag, "READY"),
                delay = delay,
                delayName = delay?.let { Naming.setName(tag, "DELAY") },
            )
        }
    }
}

object ReportBuilder {
    /** READY shots further apart than this belong to different stays (Grab reuses order numbers). */
    private const val STAY_GAP_MS = 45 * 60_000L

    private class Row(
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
        val rows = LinkedHashMap<String, Row>()
        for (r in sorted) {
            val seen = TimeResolve.toLocal(r.t, zone)
            val day = TimeResolve.parseDay(r.day)
            for (item in r.items) {
                if (item.type != ObsType.DONE && item.type != ObsType.DELAY) continue
                val time = item.doneAt?.let { TimeResolve.parseHHmm(it) }
                val doneAt = when {
                    time == null -> null
                    day != null -> day.atTime(time)
                    else -> TimeResolve.latestAtOrBefore(time, seen.plusMinutes(1))
                }
                val rowDay = doneAt?.toLocalDate() ?: day ?: seen.toLocalDate()
                val key = "${item.gf}|${doneAt ?: rowDay}"
                val row = rows.getOrPut(key) { Row(item.gf, doneAt, rowDay, r.t) }
                if (item.type == ObsType.DELAY || item.delayMin != null) {
                    row.delayed = true
                    item.delayMin?.let { m -> row.delayMin = maxOf(row.delayMin ?: 0, m) }
                }
                if (item.type == ObsType.DELAY && r.uri != null && row.delayShots.none { it.id == r.id }) row.delayShots += r
            }
        }

        val onDate = rows.values.filter { it.date == date }
        val readyShots = sorted.filter { r -> r.uri != null && r.items.any { it.type == ObsType.READY } }
        val windowMs = cfg.evidenceWindowHours * 3600_000L
        val dayStart = TimeResolve.toMillis(date.atStartOfDay(), zone)
        val dayEnd = TimeResolve.toMillis(date.plusDays(1).atStartOfDay(), zone)

        fun evidenceFor(row: Row): List<Evidence> {
            val doneMs = row.doneAt?.let { TimeResolve.toMillis(it, zone) }
            val from = if (doneMs != null) doneMs - windowMs else dayStart
            val to = if (doneMs != null) doneMs + 5 * 60_000L else row.firstSeen
            val shots = readyShots.filter { it.t in from..to }.mapNotNull { r ->
                r.items.firstOrNull { it.gf == row.gf && it.type == ObsType.READY }?.let { Evidence(r, it.status) }
            }
            if (shots.isEmpty()) return shots
            // Keep only the last stay before the order finished.
            var start = shots.size - 1
            while (start > 0 && shots[start].t - shots[start - 1].t <= STAY_GAP_MS) start--
            return shots.subList(start, shots.size)
        }

        fun verdictOf(ev: List<Evidence>): Verdict = when {
            ev.isEmpty() -> Verdict.NO_EVIDENCE
            TextNorm.containsAny(ev.first().status, cfg.lateStatus) -> Verdict.LATE
            else -> Verdict.IN_TIME
        }

        val withEvidence = onDate.associateWith { evidenceFor(it) }
        val cases = onDate.filter { it.delayed }.map { row ->
            val ev = withEvidence.getValue(row)
            DelayCase(row.gf, row.delayMin, row.doneAt, row.firstSeen, ev, row.delayShots.sortedByDescending { it.t }, verdictOf(ev))
        }.sortedWith(compareBy<DelayCase>({ it.doneAt == null }, { it.doneAt }, { it.gf }))

        val stats = sorted.lastOrNull { it.kind == RecordKind.STATS && TimeResolve.parseDay(it.day) == date }
        val readyGfs = readyShots.filter { it.t in dayStart until dayEnd }
            .flatMap { r -> r.items.filter { it.type == ObsType.READY }.map { it.gf } }.toSet()

        return DailyReport(
            date = date,
            grabCompleted = stats?.completed,
            grabCancelled = stats?.cancelled,
            historyRows = onDate.size,
            cases = cases,
            rowsWithReady = withEvidence.count { it.value.isNotEmpty() },
            rowsWithoutReady = withEvidence.filter { it.value.isEmpty() }.keys.map { it.gf },
            readyOrders = readyGfs.size,
        )
    }
}

/** Text forms of a report: the chat summary and the spreadsheet export. */
object ReportText {
    fun date(d: LocalDate): String = Parsers.pad2(d.dayOfMonth) + "/" + Parsers.pad2(d.monthValue) + "/" + d.year

    fun time(ms: Long, zone: ZoneId): String = Parsers.hhmm(TimeResolve.toLocal(ms, zone).toLocalTime())

    fun pct(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.2f%%", v)

    fun summary(r: DailyReport, zone: ZoneId): String = buildString {
        append("สรุปออเดอร์ล่าช้า วันที่ ").append(date(r.date)).append('\n')
        if (r.grabCompleted != null) {
            append("• ยอดจาก Grab (หัวหน้าประวัติ): เสร็จสมบูรณ์ ").append(r.grabCompleted)
            r.grabCancelled?.let { append(" · ยกเลิก ").append(it) }
            append('\n')
            append("• อ่านรายการในประวัติได้ ").append(r.historyRows).append('/').append(r.grabCompleted)
            append(if (r.historyComplete) " ✓ ครบ" else " ⚠ ไม่ครบ").append('\n')
        } else {
            append("• อ่านรายการในประวัติได้ ").append(r.historyRows).append(" (ยังไม่ได้อ่านยอดรวมจาก Grab)\n")
        }
        append("• มีภาพในแท็บ Ready: ").append(r.rowsWithReady).append('/').append(r.historyRows).append(" ออเดอร์")
        if (r.rowsWithoutReady.isNotEmpty()) {
            append(" (ไม่มีภาพ: ").append(r.rowsWithoutReady.take(15).joinToString(", "))
            if (r.rowsWithoutReady.size > 15) append(" …")
            append(')')
        }
        append('\n')
        append('\n')
        append("Grab ระบุล่าช้า ").append(r.delayed).append(" ออเดอร์ = ").append(pct(r.grabPct)).append(" (แบบ Grab คิด)\n")
        append("• ").append(Verdict.IN_TIME.label).append(": ").append(r.inTime.size).append('\n')
        append("• ").append(Verdict.LATE.label).append(": ").append(r.late.size).append('\n')
        append("• ").append(Verdict.NO_EVIDENCE.label).append(": ").append(r.noEvidence.size).append('\n')
        append("% ล่าช้าที่ร้านควรได้ (ตัดออเดอร์ที่มีหลักฐานกดทันออก) = (")
            .append(r.delayed).append(" − ").append(r.inTime.size).append(") / ").append(r.base)
            .append(" = ").append(pct(r.realPct)).append('\n')
        section(this, "✅ " + Verdict.IN_TIME.label, r.inTime, zone)
        section(this, "⚠️ " + Verdict.LATE.label, r.late, zone)
        section(this, "❓ " + Verdict.NO_EVIDENCE.label + " (ไม่ได้แปลว่าช้าจริง)", r.noEvidence, zone)
    }

    private fun section(sb: StringBuilder, title: String, cases: List<DelayCase>, zone: ZoneId) {
        if (cases.isEmpty()) return
        sb.append('\n').append(title).append('\n')
        cases.forEach { sb.append(caseLine(it, zone)).append('\n') }
    }

    fun caseLine(c: DelayCase, zone: ZoneId): String = buildString {
        append(c.gf)
        append(" ล่าช้า ").append(c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        c.doneAt?.let { append(" (เสร็จ ").append(Parsers.hhmm(it.toLocalTime())).append(')') }
        c.readyShot?.let { e ->
            append(" — อยู่ในแท็บ Ready ตั้งแต่ ").append(time(e.t, zone))
            e.status?.let { append(" \"").append(it).append('"') }
        }
        if (c.delayShot == null) append(" [ไม่มีภาพหน้าประวัติ]")
    }

    fun csv(r: DailyReport, zone: ZoneId): String = buildString {
        append('﻿') // BOM so Excel shows Thai correctly; Google Sheets ignores it
        append("date,gf,delay_min,done_at,verdict,ready_time,ready_status,ready_file,delay_file\n")
        val sets = Verdict.entries.flatMap { r.sets(it) }.associateBy { it.case }
        for (c in r.cases) {
            val set = sets[c]
            val row = listOf(
                r.date.toString(),
                c.gf,
                c.delayMin?.toString().orEmpty(),
                c.doneAt?.let { Parsers.hhmm(it.toLocalTime()) }.orEmpty(),
                c.verdict.name,
                c.readyShot?.let { time(it.t, zone) }.orEmpty(),
                c.readyShot?.status.orEmpty(),
                set?.readyName.orEmpty(),
                set?.delayName.orEmpty(),
            )
            append(row.joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
