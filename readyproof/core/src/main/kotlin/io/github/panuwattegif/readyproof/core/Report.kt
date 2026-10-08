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

/** A screenshot showing the order inside the Ready tab, i.e. already pressed ready. */
data class Evidence(val record: Record, val status: String?) {
    val t: Long get() = record.t
}

/** An order Grab marked as delayed, with the evidence found for it. */
data class DelayCase(
    val gf: String,
    val delayMin: Int?,
    val doneAt: LocalDateTime?,
    val firstSeen: Long,
    /** READY shots taken before the order finished, earliest first. */
    val evidence: List<Evidence>,
    /** History screenshots that show this order as delayed, latest first. */
    val delayShots: List<Record>,
    /** Logged taps on the ready button for this order (text only, for reference). */
    val presses: List<Long> = emptyList(),
    val matchStatus: String = if (evidence.isNotEmpty()) "READY_EVIDENCE" else "MISSING_READY",
) {
    val hasEvidence: Boolean get() = evidence.isNotEmpty()

    /** The earliest READY shot: closest to the press, most likely still "Finding a driver...". */
    val readyShot: Evidence? get() = evidence.firstOrNull()

    val delayShot: Record? get() = delayShots.firstOrNull()

    /** Last logged tap before the order finished. */
    val pressedAt: Long? get() = presses.maxOrNull()
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
    /** Completed order instances observed during a full History sweep. */
    val completedSeen: Int,
    val cases: List<DelayCase>,
    /** Distinct order numbers whose ready button tap was logged this date. */
    val pressedOrders: Int,
    /** How many of those also got a READY shot this date. */
    val pressedWithReady: Int,
    /** Distinct order numbers photographed in the Ready tab this date. */
    val readyOrders: Int,
    /** Cancelled order instances observed during the History sweep. */
    val cancelledSeen: Int = 0,
    /** Distinct GF numbers observed in Ready, including targets still waiting for a valid bitmap. */
    val readySeenOrders: Int = readyOrders,
    val shopId: String? = null,
    val pendingReadyGfs: List<String> = emptyList(),
    val missingHistoryInstances: List<String> = emptyList(),
    val unknownHistoryInstances: List<String> = emptyList(),
    val historyDateVerified: Boolean = false,
    val sweepReachedEnd: Boolean = false,
    val missingReadyInstances: List<String> = emptyList(),
) {
    val delayed: Int get() = cases.size
    val withEvidence: List<DelayCase> get() = cases.filter { it.hasEvidence }
    val withoutEvidence: List<DelayCase> get() = cases.filter { !it.hasEvidence }
    val historyOrders: Int get() = completedSeen + cancelledSeen
    val actualDelayed: Int get() = withoutEvidence.size
    val pendingReadyProof: Int get() = maxOf(pendingReadyGfs.size, readySeenOrders - readyOrders, 0)
    val missingDelayProof: Int get() = cases.count { it.delayShot == null }
    val complete: Boolean get() = sweepReachedEnd && historyDateVerified &&
        unknownHistoryInstances.isEmpty() && missingHistoryInstances.isEmpty() &&
        missingDelayProof == 0 && pendingReadyProof == 0 && missingReadyInstances.isEmpty()
    val readyVsCompletedMatch: Boolean get() = readyOrders == completedSeen && pendingReadyProof == 0

    /** Provisional percentage based on the History orders the scanner has actually counted. */
    fun pct(n: Int): Double? = historyOrders.takeIf { it > 0 && delayed <= it }?.let { n * 100.0 / it }

    /**
     * One set per proven order: GF-xxx_READY.jpg + GF-xxx_DELAY.jpg. When the same order number
     * was used twice that day, the finish time is added ("GF-613_1147_READY.jpg").
     */
    fun sets(): List<EvidenceSet> {
        val repeated = cases.groupingBy { it.gf }.eachCount().filterValues { it > 1 }.keys
        return withEvidence.map { c ->
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
    private class Acc(
        val gf: String,
        val doneAt: LocalDateTime?,
        val date: LocalDate,
        val firstSeen: Long,
    ) {
        var delayed = false
        var cancelled = false
        var completed = false
        var delayMin: Int? = null
        val delayShots = ArrayList<Record>()
    }

    /** [records] should cover the day before and after [date] too, so windows can cross midnight. */
    fun build(records: List<Record>, date: LocalDate, zone: ZoneId, cfg: Config,
              shopId: String? = null, sweepReachedEnd: Boolean = false): DailyReport {
        // Legacy records stay in a separate report; selecting a shop never reassigns old data.
        val sorted = records.filter { it.shopId == shopId }.sortedBy { it.t }
        val acc = LinkedHashMap<String, Acc>()
        for (r in sorted) {
            val seen = TimeResolve.toLocal(r.t, zone)
            for (item in r.items) {
                if (item.type != ObsType.DONE && item.type != ObsType.DELAY && item.type != ObsType.CANCELLED) continue
                if (r.kind == RecordKind.READY) continue
                val explicitDate = (item.historyDate ?: r.historyDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val doneAt = item.doneAt?.let { TimeResolve.parseHHmm(it) }
                    ?.let { if (explicitDate != null) explicitDate.atTime(it) else TimeResolve.latestAtOrBefore(it, seen.plusMinutes(1)) }
                val day = explicitDate ?: doneAt?.toLocalDate() ?: seen.toLocalDate()
                val key = "${item.gf}|${doneAt ?: "$day|${item.card.joinToString("|")}"}"
                val a = acc.getOrPut(key) { Acc(item.gf, doneAt, day, r.t) }
                if (item.type == ObsType.DONE || (item.type == ObsType.DELAY && doneAt != null)) a.completed = true
                if (item.type == ObsType.CANCELLED) a.cancelled = true
                if (item.type == ObsType.DELAY) {
                    a.delayed = true
                    item.delayMin?.let { m -> a.delayMin = maxOf(a.delayMin ?: 0, m) }
                    if (r.uri != null && a.delayShots.none { it.id == r.id }) a.delayShots += r
                }
            }
        }

        val onDate = acc.values.filter { it.date == date }
        val readyShots = sorted.filter { r -> r.uri != null && r.items.any { it.type == ObsType.READY } }
        val readySeenRecords = sorted.filter { r -> r.items.any { it.type == ObsType.READY } }
        val presses = sorted.filter { r -> r.items.any { it.type == ObsType.PRESS } }
        val windowMs = cfg.evidenceWindowHours * 3600_000L
        val dayStart = TimeResolve.toMillis(date.atStartOfDay(), zone)
        val dayEnd = TimeResolve.toMillis(date.plusDays(1).atStartOfDay(), zone)

        val cases = onDate.filter { it.delayed }.map { a ->
            val doneMs = a.doneAt?.let { TimeResolve.toMillis(it, zone) }
            // A GF can be reused. Never borrow a READY image from before its previous terminal row.
            val sameGf = acc.values.filter { it.gf == a.gf && it.date == date }
            val previousEnd = sameGf.mapNotNull { it.doneAt }.filter { a.doneAt != null && it < a.doneAt }
                .maxOrNull()?.let { TimeResolve.toMillis(it, zone) }
            val ambiguous = doneMs == null || sameGf.any { it.doneAt == null }
            val from = maxOf(dayStart, (doneMs ?: dayStart) - windowMs, previousEnd?.plus(1) ?: dayStart)
            val to = doneMs ?: a.firstSeen
            val ev = readyShots.filter { !ambiguous && it.t in from..to }.mapNotNull { r ->
                r.items.firstOrNull { it.gf == a.gf && it.type == ObsType.READY }?.let { Evidence(r, it.status) }
            }
            val taps = presses.filter { it.t in from..to && it.items.any { i -> i.gf == a.gf && i.type == ObsType.PRESS } }.map { it.t }
            DelayCase(a.gf, a.delayMin, a.doneAt, a.firstSeen, ev, a.delayShots.sortedByDescending { it.t }, taps,
                if (ambiguous) "UNKNOWN_INSTANCE" else if (ev.isNotEmpty()) "READY_EVIDENCE" else "MISSING_READY")
        }.sortedWith(compareBy<DelayCase>({ it.doneAt == null }, { it.doneAt }, { it.gf }))

        fun gfsOf(rs: List<Record>, type: ObsType) =
            rs.filter { it.t in dayStart until dayEnd }.flatMap { r -> r.items.filter { it.type == type }.map { it.gf } }.toSet()
        val pressed = gfsOf(presses, ObsType.PRESS)
        val ready = gfsOf(readyShots, ObsType.READY)
        val readySeen = gfsOf(readySeenRecords, ObsType.READY)
        fun collectReadyInstances(rs: List<Record>): Set<String> = rs.filter { it.t in dayStart until dayEnd }.flatMap { r ->
            r.items.filter { it.type == ObsType.READY }.map { i ->
                val local = TimeResolve.toLocal(r.t, zone)
                val terminal = onDate.filter { it.gf == i.gf && it.doneAt != null && it.doneAt >= local }
                    .minByOrNull { it.doneAt!! }
                i.gf + "@" + (terminal?.doneAt?.toLocalTime()?.toString() ?: "UNRESOLVED")
            }
        }.toSet()
        val readyInstances = collectReadyInstances(readyShots)
        val seenInstances = collectReadyInstances(readySeenRecords)
        val pendingGfs = seenInstances - readyInstances

        val completedSeen = onDate.count { !it.cancelled && it.completed }
        val cancelledSeen = onDate.count { it.cancelled }
        fun tag(a: Acc) = a.gf + "@" + (a.doneAt?.toLocalTime()?.toString() ?: "UNKNOWN")
        val missingReady = onDate.filter { !it.cancelled }.filter { a ->
            val end = a.doneAt?.let { TimeResolve.toMillis(it, zone) }
            val previous = onDate.filter { it.gf == a.gf && it.doneAt != null && a.doneAt != null && it.doneAt < a.doneAt }
                .map { TimeResolve.toMillis(it.doneAt!!, zone) }.maxOrNull()
            end == null || onDate.any { it.gf == a.gf && it.doneAt == null } || readyShots.none { r ->
                r.t >= maxOf(dayStart, end - windowMs, previous?.plus(1) ?: dayStart) && r.t <= end &&
                    r.items.any { it.gf == a.gf && it.type == ObsType.READY }
            }
        }.map(::tag)
        fun hasHistoryImage(a: Acc) = sorted.any { r -> r.uri != null && r.items.any { i ->
            i.gf == a.gf && i.doneAt == a.doneAt?.toLocalTime()?.let(Parsers::hhmm) &&
                (i.type == ObsType.DONE || i.type == ObsType.DELAY || i.type == ObsType.CANCELLED) &&
                (i.historyDate ?: r.historyDate ?: TimeResolve.toLocal(r.t, zone).toLocalDate().toString()) == date.toString()
        } }
        return DailyReport(
            date = date,
            completedSeen = completedSeen,
            cases = cases,
            pressedOrders = pressed.size,
            pressedWithReady = pressed.count { it in ready },
            readyOrders = readyInstances.size,
            cancelledSeen = cancelledSeen,
            readySeenOrders = seenInstances.size,
            shopId = shopId,
            pendingReadyGfs = pendingGfs.sorted(),
            missingHistoryInstances = onDate.filterNot(::hasHistoryImage).map(::tag),
            unknownHistoryInstances = onDate.filter { it.doneAt == null }.map(::tag),
            historyDateVerified = onDate.isNotEmpty() && onDate.all { a -> sorted.any { r ->
                r.items.any { i ->
                    (i.historyDate ?: r.historyDate) == date.toString() && i.gf == a.gf &&
                        i.doneAt == a.doneAt?.toLocalTime()?.let(Parsers::hhmm)
                }
            } },
            sweepReachedEnd = sweepReachedEnd,
            missingReadyInstances = missingReady,
        )
    }
}

/** Text forms of a report: the chat summary and the spreadsheet export. */
object ReportText {
    fun date(d: LocalDate): String = Parsers.pad2(d.dayOfMonth) + "/" + Parsers.pad2(d.monthValue) + "/" + d.year

    fun time(ms: Long, zone: ZoneId): String = Parsers.hhmm(TimeResolve.toLocal(ms, zone).toLocalTime())

    fun pct(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.1f%%", v)

    fun summary(r: DailyReport, zone: ZoneId): String = buildString {
        append("สรุปออเดอร์ล่าช้า วันที่ ").append(date(r.date)).append('\n')
        append("ร้าน: ").append(Shop.fromId(r.shopId)?.label ?: "UNKNOWN (ข้อมูลเดิม/ยังไม่เลือกร้าน)").append('\n')
        append("ความครบ: ").append(if (r.complete) "COMPLETE" else "INCOMPLETE / PROVISIONAL").append('\n')
        if (!r.complete) append("ยังตรวจ History ไม่ครบ — ยังสรุปว่าไม่มีออเดอร์ล่าช้าไม่ได้\n")
        append("วันที่ History: ").append(if (r.historyDateVerified) "ระบุวันไว้แล้ว (หน้าจอหรือวันที่ผู้ใช้เลือกตอนกวาด)" else "UNKNOWN — เวลาอย่างเดียวไม่ยืนยันวันที่").append('\n')
        append("ถึงท้ายรายการ: ").append(r.sweepReachedEnd).append('\n')
        append("History ขาดภาพ: ").append(r.missingHistoryInstances.joinToString(", ").ifEmpty { "-" }).append('\n')
        append("Instance UNKNOWN: ").append(r.unknownHistoryInstances.joinToString(", ").ifEmpty { "-" }).append('\n')
        append("Ready รอภาพ: ").append(r.pendingReadyProof).append(" ออเดอร์ (รายละเอียดใน manifest)\n")
        append("DELAY ขาดภาพ: ").append(r.cases.filter { it.delayShot == null }.joinToString { it.gf + "@" + (it.doneAt?.toLocalTime() ?: "UNKNOWN") }.ifEmpty { "-" }).append('\n')
        append("• History ที่แอปสแกนเห็น: ").append(r.historyOrders)
            .append(" ออเดอร์ (เสร็จ ").append(r.completedSeen)
            .append(" / ยกเลิก ").append(r.cancelledSeen).append(")\n")
        append("• Ready เห็น ").append(r.readySeenOrders)
            .append(" / มีภาพ ").append(r.readyOrders)
            .append(" / Pending ").append(r.pendingReadyProof).append('\n')
        append("• Ready proof ").append(r.readyOrders)
            .append(" / Completed: ").append(r.completedSeen)
            .append(if (r.readyVsCompletedMatch) " — MATCH\n" else " — MISMATCH\n")
        append("• Grab ระบุล่าช้า: ").append(r.delayed).append('\n')
        append("• มีภาพในแท็บ Ready (กดเสร็จแล้ว): ").append(r.withEvidence.size).append('\n')
        append("• ไม่มีภาพ Ready / เหลือล่าช้าตามหลักฐาน: ").append(r.withoutEvidence.size).append('\n')
        append("• % Grab จาก History: ").append(pct(r.pct(r.delayed))).append('\n')
        append("• % Actual จากหลักฐาน: ").append(pct(r.pct(r.actualDelayed))).append('\n')
        append("ตัวเลขเป็นออเดอร์ที่สแกนพบ ไม่ใช่จำนวนภาพ; ถ้ายังไม่ครบ Total ทั้งวัน = UNKNOWN\n")
        append("Actual เป็นการคำนวณหักหลักฐาน Ready ไม่ยืนยันว่า Grab ปรับยอดแล้ว\n")
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
        if (c.matchStatus == "UNKNOWN_INSTANCE") append(" [UNKNOWN_INSTANCE]")
        append(" ล่าช้า ").append(c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        c.doneAt?.let { append(" (เสร็จ ").append(Parsers.hhmm(it.toLocalTime())).append(')') }
        c.readyShot?.let { e ->
            append(" — อยู่ในแท็บ Ready ตั้งแต่ ").append(time(e.t, zone))
            e.status?.let { append(" \"").append(it).append('"') }
        }
    }

    fun csv(r: DailyReport, zone: ZoneId): String = buildString {
        append('﻿') // BOM so Excel shows Thai correctly; Google Sheets ignores it
        append("date,gf,delay_min,done_at,has_ready_shot,ready_time,ready_status,pressed_at_log,ready_file,delay_file,shop_id,match_status\n")
        val sets = r.sets().associateBy { it.case }
        for (c in r.cases) {
            val set = sets[c]
            val row = listOf(
                r.date.toString(),
                c.gf,
                c.delayMin?.toString().orEmpty(),
                c.doneAt?.let { Parsers.hhmm(it.toLocalTime()) }.orEmpty(),
                if (c.hasEvidence) "yes" else "no",
                c.readyShot?.let { time(it.t, zone) }.orEmpty(),
                c.readyShot?.status.orEmpty(),
                c.pressedAt?.let { time(it, zone) }.orEmpty(),
                set?.readyName.orEmpty(),
                set?.delayName.orEmpty(),
                r.shopId ?: "UNKNOWN",
                c.matchStatus,
            )
            append(row.joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
