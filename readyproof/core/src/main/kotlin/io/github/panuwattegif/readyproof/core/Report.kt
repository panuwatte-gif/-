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

/**
 * What the evidence says about a delayed order. The Ready tab tells Grab the shop was not late;
 * the Preparing tab keeps the shop from claiming that wrongly (owner, 22 Oct).
 */
enum class Verdict(val label: String) {
    /** Reached the Ready tab before its Preparing countdown ran out: send to Grab. */
    IN_TIME("กดทัน (เข้า Ready ก่อนหมดเวลา)"),
    /** The countdown ran out (red 0:00) before the order reached the Ready tab: the shop was late. */
    LATE("กดช้า (หมดเวลาก่อนกด)"),
    /** In the Ready tab, but its countdown was never read: cannot tell, check by hand first. */
    UNCHECKED("มีภาพ Ready แต่ไม่ได้ดูหน้ากำลังเตรียม"),
    /** No usable Ready-tab shot: no evidence or the phone missed it. Not proof of being late. */
    NO_EVIDENCE("ไม่มีหลักฐาน / ระบบจับไม่ได้"),
}

/** What the Preparing tab showed for an order before it reached the Ready tab. */
data class PrepCheck(
    /** The last reading of its countdown before it was first seen in the Ready tab. */
    val readAt: Long,
    val countdown: String,
    /** When that countdown reached 0:00 (for a 0:00 reading: when it was read, at the latest). */
    val deadline: Long,
    /** A photo of its red 0:00 card taken before it reached the Ready tab, if any. */
    val overdueShot: Record?,
) {
    val ranOut: Boolean get() = countdown.trim().let { it.startsWith("-") || it.trimStart('0', ':').isEmpty() }
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
    val verdict: Verdict = if (evidence.isNotEmpty()) Verdict.UNCHECKED else Verdict.NO_EVIDENCE,
    /** First moment the order was seen in the Ready tab (before its first photo, or with it). */
    val readyAt: Long? = evidence.firstOrNull()?.t,
    val prep: PrepCheck? = null,
) {
    /** How long after its countdown ran out it reached the Ready tab (negative = time to spare). */
    val lateMs: Long? get() = prep?.let { p -> readyAt?.let { it - p.deadline } }

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
    /** Grab's own totals at the top of History ("Completed 77 · Cancelled 0"); null = never read. */
    val grabCompleted: Int? = null,
    val grabCancelled: Int? = null,
) {
    val delayed: Int get() = cases.size
    val withEvidence: List<DelayCase> get() = cases.filter { it.hasEvidence }
    val withoutEvidence: List<DelayCase> get() = cases.filter { !it.hasEvidence }
    val inTime: List<DelayCase> get() = cases.filter { it.verdict == Verdict.IN_TIME }
    val late: List<DelayCase> get() = cases.filter { it.verdict == Verdict.LATE }
    val unchecked: List<DelayCase> get() = cases.filter { it.verdict == Verdict.UNCHECKED }
    val noEvidence: List<DelayCase> get() = cases.filter { it.verdict == Verdict.NO_EVIDENCE }
    val historyOrders: Int get() = completedSeen + cancelledSeen
    /** Delayed orders left once the ones proven done in time are taken out. */
    val actualDelayed: Int get() = delayed - inTime.size

    /** Grab's own order total when it was read, otherwise the rows the scanner counted. */
    val base: Int get() = if (grabCompleted != null) grabCompleted + (grabCancelled ?: 0) else historyOrders

    /** The rows read match Grab's own totals (unknown when the totals were never read). */
    val historyMatchesGrab: Boolean?
        get() = grabCompleted?.let { completedSeen >= it && cancelledSeen >= (grabCancelled ?: 0) }

    private fun pctOfBase(n: Int): Double? = base.takeIf { it > 0 && n <= it }?.let { n * 100.0 / it }

    /** Delay rate as Grab counts it: every delayed order. */
    val grabPct: Double? get() = pctOfBase(delayed)

    /** Delay rate the shop should get: orders proven done in time are not late. */
    val realPct: Double? get() = pctOfBase(actualDelayed)
    val pendingReadyProof: Int get() = maxOf(pendingReadyGfs.size, readySeenOrders - readyOrders, 0)
    val missingDelayProof: Int get() = cases.count { it.delayShot == null }
    val complete: Boolean get() = sweepReachedEnd && historyDateVerified &&
        unknownHistoryInstances.isEmpty() && missingHistoryInstances.isEmpty() &&
        missingDelayProof == 0 && pendingReadyProof == 0 && missingReadyInstances.isEmpty() &&
        historyMatchesGrab != false
    val readyVsCompletedMatch: Boolean get() = readyOrders == completedSeen && pendingReadyProof == 0

    /** Provisional percentage based on the History orders the scanner has actually counted. */
    fun pct(n: Int): Double? = historyOrders.takeIf { it > 0 && delayed <= it }?.let { n * 100.0 / it }

    /**
     * One set per proven order: GF-xxx_READY.jpg + GF-xxx_DELAY.jpg. When the same order number
     * was used twice that day, the finish time is added ("GF-613_1147_READY.jpg").
     */
    fun sets(): List<EvidenceSet> = setsOf(withEvidence)

    /** Sets of one verdict group (in time / late). */
    fun sets(verdict: Verdict): List<EvidenceSet> = setsOf(withEvidence.filter { it.verdict == verdict })

    private fun setsOf(chosen: List<DelayCase>): List<EvidenceSet> {
        val repeated = cases.groupingBy { it.gf }.eachCount().filterValues { it > 1 }.keys
        return chosen.map { c ->
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

    /** How long before a Ready photo an order must have been seen in the Ready tab to count as shown in it. */
    private const val SHOWN_LOOKBACK_MS = 90 * 60_000L

    /**
     * Reaching the Ready tab this soon after the countdown ran out still counts as in time: the app
     * notices an order in the Ready tab a few seconds after the press when it was looking at the
     * Preparing tab just then, and the countdown itself is read to the second.
     */
    const val PRESS_TOLERANCE_MS = 10_000L

    /**
     * Pressed in time or not, from the Preparing tab: the last reading of the countdown before the
     * order reached the Ready tab decides. A red 0:00 there, or reaching Ready after the time ran
     * out, is late; no reading at all leaves it unchecked.
     */
    fun judge(readyAt: Long?, prep: PrepCheck?): Verdict = when {
        readyAt == null -> Verdict.NO_EVIDENCE
        prep == null -> Verdict.UNCHECKED
        prep.ranOut -> Verdict.LATE
        readyAt > prep.deadline + PRESS_TOLERANCE_MS -> Verdict.LATE
        else -> Verdict.IN_TIME
    }

    /** What the Preparing tab said about [gf] between [from] and [until] (see [PrepCheck]). */
    fun prepCheck(sorted: List<Record>, gf: String, from: Long, until: Long): PrepCheck? {
        var last: Pair<Record, Item>? = null
        for (r in sorted) {
            if (r.t < from || r.t > until) continue
            val item = r.items.firstOrNull { it.gf == gf && it.type == ObsType.PREPARING && it.dueSeconds != null } ?: continue
            last = r to item
        }
        val (r, item) = last ?: return null
        val overdue = if (item.dueSeconds != 0) null else sorted.lastOrNull { o ->
            o.uri != null && o.t in from..until &&
                o.items.any { it.gf == gf && it.type == ObsType.PREPARING && it.dueSeconds == 0 }
        }
        return PrepCheck(r.t, item.countdown!!, r.t + item.dueSeconds!! * 1000L, overdue)
    }

    /**
     * A Ready photo proves every order listed on it, not only the ones it was taken for: on 10 Oct
     * the orders at the top of the list appear 18 times in photos (GF-102 at 13:18) but are named in none. An
     * order on the photo ([Record.visible]) that the app had seen in the Ready tab shortly before
     * counts as shown, with the status it had when it was seen there.
     */
    private fun withShownOrders(photo: Record, seen: List<Record>): Record {
        val named = photo.items.filter { it.type == ObsType.READY }.map { it.gf }.toSet()
        val shown = photo.visible.distinct().filter { it !in named }.mapNotNull { gf ->
            seen.lastOrNull { r -> r.t <= photo.t && photo.t - r.t <= SHOWN_LOOKBACK_MS && r.items.any { it.gf == gf && it.type == ObsType.READY } }
                ?.items?.first { it.gf == gf && it.type == ObsType.READY }
        }
        return if (shown.isEmpty()) photo else photo.copy(items = photo.items + shown)
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
                val explicitDate = (item.historyDate ?: r.historyDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val doneAt = item.doneAt?.let { TimeResolve.parseHHmm(it) }
                    ?.let { if (explicitDate != null) explicitDate.atTime(it) else TimeResolve.latestAtOrBefore(it, seen.plusMinutes(1)) }
                val day = doneAt?.toLocalDate() ?: seen.toLocalDate()
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
        val readySeenRecords = sorted.filter { r -> r.items.any { it.type == ObsType.READY } }
        val readyShots = sorted.filter { r -> r.uri != null && r.items.any { it.type == ObsType.READY } }
            .map { r -> withShownOrders(r, readySeenRecords) }
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
            // First sighting in the Ready tab (seen before its photo, or on a photo taken for another order).
            val readyAt = if (ev.isEmpty()) null else listOfNotNull(
                ev.first().t,
                readySeenRecords.firstOrNull { r -> r.t in from..to && r.items.any { it.gf == a.gf && it.type == ObsType.READY } }?.t,
            ).min()
            val prep = if (ev.isEmpty()) null else prepCheck(sorted, a.gf, from, readyAt!!)
            DelayCase(a.gf, a.delayMin, a.doneAt, a.firstSeen, ev, a.delayShots.sortedByDescending { it.t }, taps,
                if (ambiguous) "UNKNOWN_INSTANCE" else if (ev.isNotEmpty()) "READY_EVIDENCE" else "MISSING_READY",
                judge(readyAt, prep), readyAt, prep)
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
                ((i.historyDate ?: r.historyDate) == date.toString() || TimeResolve.toLocal(r.t, zone).toLocalDate() == date)
        } }
        // Grab's own totals, read from the top of History for this day (latest reading wins).
        val stats = sorted.lastOrNull { it.kind == RecordKind.STATS && it.historyDate == date.toString() && it.completed != null }
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
            grabCompleted = stats?.completed,
            grabCancelled = stats?.cancelled,
        )
    }
}

/** Text forms of a report: the chat summary and the spreadsheet export. */
object ReportText {
    fun date(d: LocalDate): String = Parsers.pad2(d.dayOfMonth) + "/" + Parsers.pad2(d.monthValue) + "/" + d.year

    fun time(ms: Long, zone: ZoneId): String = Parsers.hhmm(TimeResolve.toLocal(ms, zone).toLocalTime())

    /** "15:49:33". */
    fun timeSec(ms: Long, zone: ZoneId): String = TimeResolve.toLocal(ms, zone).toLocalTime().let {
        Parsers.pad2(it.hour) + ":" + Parsers.pad2(it.minute) + ":" + Parsers.pad2(it.second)
    }

    /** "2:33" for a duration. */
    fun minSec(ms: Long): String = (ms / 1000).let { "${it / 60}:" + Parsers.pad2((it % 60).toInt()) }

    fun pct(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.2f%%", v)

    fun summary(r: DailyReport, zone: ZoneId): String = buildString {
        append("สรุปออเดอร์ล่าช้า วันที่ ").append(date(r.date)).append('\n')
        append("ร้าน: ").append(Shop.fromId(r.shopId)?.label ?: "UNKNOWN (ข้อมูลเดิม/ยังไม่เลือกร้าน)").append('\n')
        append("ความครบ: ").append(if (r.complete) "COMPLETE" else "INCOMPLETE / PROVISIONAL").append('\n')
        if (!r.complete) append("ยังตรวจ History ไม่ครบ — ยังสรุปว่าไม่มีออเดอร์ล่าช้าไม่ได้\n")
        append("วันที่ History: ").append(if (r.historyDateVerified) "ยืนยันจากหน้าจอ" else "UNKNOWN — เวลาอย่างเดียวไม่ยืนยันวันที่").append('\n')
        append("ถึงท้ายรายการ: ").append(r.sweepReachedEnd).append('\n')
        append("History ขาดภาพ: ").append(r.missingHistoryInstances.joinToString(", ").ifEmpty { "-" }).append('\n')
        append("Instance UNKNOWN: ").append(r.unknownHistoryInstances.joinToString(", ").ifEmpty { "-" }).append('\n')
        // By order number, so the owner sees which orders lack a Ready photo without opening the manifest.
        append("Ready ไม่มีภาพ (เสร็จแล้ว แต่ไม่มีภาพตอนอยู่ในแท็บ Ready): ").append(r.missingReadyInstances.size).append(" ออเดอร์")
            .append(r.missingReadyInstances.joinToString(", ").let { if (it.isEmpty()) "" else " — $it" }).append('\n')
        append("Ready เห็นในแท็บแต่ถ่ายไม่ได้: ").append(r.pendingReadyGfs.joinToString(", ").ifEmpty { "-" }).append('\n')
        append("DELAY ขาดภาพ: ").append(r.cases.filter { it.delayShot == null }.joinToString { it.gf + "@" + (it.doneAt?.toLocalTime() ?: "UNKNOWN") }.ifEmpty { "-" }).append('\n')
        if (r.grabCompleted != null) {
            append("• ยอดจาก Grab (หัวหน้าประวัติ): เสร็จสมบูรณ์ ").append(r.grabCompleted)
                .append(" · ยกเลิก ").append(r.grabCancelled ?: 0)
                .append(if (r.historyMatchesGrab == true) " — อ่านรายการได้ครบ\n" else " — ⚠ อ่านรายการได้ไม่ครบ\n")
        } else {
            append("• ยอดจาก Grab (หัวหน้าประวัติ): ยังอ่านไม่ได้\n")
        }
        append("• History ที่แอปสแกนเห็น: ").append(r.historyOrders)
            .append(" ออเดอร์ (เสร็จ ").append(r.completedSeen)
            .append(" / ยกเลิก ").append(r.cancelledSeen).append(")\n")
        append("• Ready เห็น ").append(r.readySeenOrders)
            .append(" / มีภาพ ").append(r.readyOrders)
            .append(" / Pending ").append(r.pendingReadyProof).append('\n')
        append("• Ready proof ").append(r.readyOrders)
            .append(" / Completed: ").append(r.completedSeen)
            .append(if (r.readyVsCompletedMatch) " — MATCH\n" else " — MISMATCH\n")
        append("\nGrab ระบุล่าช้า ").append(r.delayed).append(" ออเดอร์ = ").append(pct(r.grabPct)).append(" (แบบ Grab คิด)\n")
        append("• ").append(Verdict.IN_TIME.label).append(": ").append(r.inTime.size).append('\n')
        append("• ").append(Verdict.LATE.label).append(": ").append(r.late.size).append('\n')
        append("• ").append(Verdict.UNCHECKED.label).append(": ").append(r.unchecked.size).append('\n')
        append("• ").append(Verdict.NO_EVIDENCE.label).append(": ").append(r.noEvidence.size).append('\n')
        append("% ล่าช้าที่ร้านควรได้ (ตัดออเดอร์ที่มีหลักฐานกดทันออก) = (").append(r.delayed).append(" − ")
            .append(r.inTime.size).append(") / ").append(r.base).append(" = ").append(pct(r.realPct)).append('\n')
        append("ตัวเลขเป็นออเดอร์ที่สแกนพบ ไม่ใช่จำนวนภาพ; ถ้ายังไม่ครบ Total ทั้งวัน = UNKNOWN\n")
        append("% ที่ร้านควรได้ เป็นการคำนวณจากหลักฐาน ไม่ยืนยันว่า Grab ปรับยอดแล้ว\n")
        section(this, "✅ " + Verdict.IN_TIME.label + " — ส่ง Grab ได้", r.inTime, zone)
        section(this, "❌ " + Verdict.LATE.label + " — ห้ามส่ง Grab", r.late, zone)
        section(this, "⚠️ " + Verdict.UNCHECKED.label + " — ตรวจเองก่อนส่ง", r.unchecked, zone)
        section(this, "❓ " + Verdict.NO_EVIDENCE.label + " (ไม่ได้แปลว่าช้าจริง)", r.noEvidence, zone)
    }

    private fun section(sb: StringBuilder, title: String, cases: List<DelayCase>, zone: ZoneId) {
        if (cases.isEmpty()) return
        sb.append('\n').append(title).append('\n')
        cases.forEach { sb.append(caseLine(it, zone)).append('\n') }
    }

    fun caseLine(c: DelayCase, zone: ZoneId): String = buildString {
        append(c.gf)
        if (c.matchStatus == "UNKNOWN_INSTANCE") append(" [UNKNOWN_INSTANCE]")
        append(" ล่าช้า ").append(c.delayMin?.let { "$it นาที" } ?: "(ไม่ระบุนาที)")
        c.doneAt?.let { append(" (เสร็จ ").append(Parsers.hhmm(it.toLocalTime())).append(')') }
        c.readyShot?.let { e ->
            append(" — อยู่ในแท็บ Ready ตั้งแต่ ").append(timeSec(c.readyAt ?: e.t, zone))
            e.status?.let { append(" \"").append(it).append('"') }
        }
        val p = c.prep
        when {
            c.readyShot == null -> {}
            p == null -> append(" · ไม่มีข้อมูลหน้ากำลังเตรียม")
            p.ranOut -> {
                append(" · หน้ากำลังเตรียมขึ้น 0:00 (หมดเวลา) ตอน ").append(timeSec(p.readAt, zone))
                p.overdueShot?.let { append(" มีภาพ") }
            }
            else -> {
                append(" · ต้องกดภายใน ").append(timeSec(p.deadline, zone))
                    .append(" (อ่านได้ ").append(p.countdown).append(" ตอน ").append(timeSec(p.readAt, zone)).append(')')
                c.lateMs?.let { ms ->
                    when {
                        ms <= 0 -> append(" เหลือเวลา ").append(minSec(-ms))
                        c.verdict == Verdict.IN_TIME -> append(" ทันพอดี (ห่าง ").append(minSec(ms)).append(')')
                        else -> append(" ช้าไป ").append(minSec(ms))
                    }
                }
            }
        }
    }

    fun csv(r: DailyReport, zone: ZoneId): String = buildString {
        append('﻿') // BOM so Excel shows Thai correctly; Google Sheets ignores it
        append("date,gf,delay_min,done_at,has_ready_shot,ready_time,ready_status,pressed_at_log,ready_file,delay_file,shop_id,match_status,verdict,ready_first_seen,prep_read_at,prep_countdown,prep_deadline,late_by_sec\n")
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
                c.verdict.name,
                c.readyAt?.let { timeSec(it, zone) }.orEmpty(),
                c.prep?.let { timeSec(it.readAt, zone) }.orEmpty(),
                c.prep?.countdown.orEmpty(),
                c.prep?.let { timeSec(it.deadline, zone) }.orEmpty(),
                c.lateMs?.let { (it / 1000).toString() }.orEmpty(),
            )
            append(row.joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
