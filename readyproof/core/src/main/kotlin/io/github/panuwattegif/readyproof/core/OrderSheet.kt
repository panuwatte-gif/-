package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** A moment as the phone saw it; [exact]: known between two looks, not only "by then". */
data class Seen(val at: Long, val exact: Boolean)

/** Where the driver was when the shop closed the order (its card's line on reaching the Ready tab). */
enum class AtClose(val label: String) {
    NO_DRIVER("ยังไม่มีคนขับ"),
    COMING("มีคนขับแล้ว กำลังมา"),
    WAITING("คนขับรออยู่ที่ร้านแล้ว");

    companion object {
        fun of(status: String?, cfg: Config = Config.DEFAULT): AtClose? {
            val s = status?.takeIf { it.isNotBlank() } ?: return null
            fun any(words: List<String>) = words.any { s.contains(it, ignoreCase = true) }
            return when {
                any(cfg.arrivedAny) -> WAITING
                any(cfg.searchingAny) -> NO_DRIVER
                any(cfg.driverFoundAny) -> COMING
                else -> null
            }
        }
    }
}

/**
 * One order with the owner's times (3 Nov): when each step happened and how long it took from the
 * step before. Times are those the phone saw ([Timeline]); a duration is only given when both of
 * its ends are known between two looks, never from a "by then" alone.
 */
data class OrderRow(
    val date: LocalDate,
    val gf: String,
    val cancelled: Boolean = false,
    val grabDelayMin: Int? = null,
    /** Within the time the shop set itself (IN_TIME), over it (LATE), or cannot tell (UNCHECKED). */
    val setTime: Verdict? = null,
    val orderIn: Seen? = null,
    val ready: Seen? = null,
    val found: Seen? = null,
    val arrived: Seen? = null,
    val pickedUp: Seen? = null,
    /** History's finish time (to the minute). */
    val deliveredAt: Long? = null,
    /** The food was ready before the driver came (see [Timeline.driverCameFirst]). */
    val readyBeforeDriver: Boolean? = null,
    /** Driver or not when the shop closed it (known since the first day, unlike the driver-found time). */
    val atClose: AtClose? = null,
) {
    private fun between(a: Seen?, b: Seen?): Long? = if (a != null && b != null && a.exact && b.exact) b.at - a.at else null

    /** Order in → ready (how long the shop took). */
    val prepMs: Long? get() = between(orderIn, ready)
    /** Order in → a driver took the job. */
    val toFoundMs: Long? get() = between(orderIn, found)
    /** Driver found → at the shop. */
    val toArriveMs: Long? get() = between(found, arrived)
    /** Ready → driver at the shop (the food waited; negative: the driver came first). */
    val foodWaitMs: Long? get() = between(ready, arrived)
    /** Driver at the shop → took the food. */
    val driverWaitMs: Long? get() = between(arrived, pickedUp)
    /** Took the food → delivered (History gives the minute only). */
    val rideMs: Long? get() = pickedUp?.takeIf { it.exact }?.let { p -> deliveredAt?.let { maxOf(0L, it - p.at) } }
    /** Order in → delivered. */
    val totalMs: Long? get() = orderIn?.takeIf { it.exact }?.let { o -> deliveredAt?.let { maxOf(0L, it - o.at) } }
    /** Closed without a driver → a driver took the job (Grab's search after the shop's photo). */
    val closeToFoundMs: Long? get() = if (atClose == AtClose.NO_DRIVER) between(ready, found) else null
    /**
     * Closed → delivered: the first sighting in the Ready tab (seconds after the shop closed it,
     * the app watches that tab) to History's minute. Known for every day since the first.
     */
    val closeToDeliveredMs: Long? get() = ready?.let { r -> deliveredAt?.let { maxOf(0L, it - r.at) } }
}

/** One day: Grab's own totals, the delay rate three ways, and every order. */
data class OrderDay(
    val date: LocalDate,
    val shopId: String?,
    val grabCompleted: Int?,
    val grabCancelled: Int?,
    val completedSeen: Int,
    val cancelledSeen: Int,
    /** Grab's order total when it was read, otherwise the rows read (see [DailyReport.base]). */
    val base: Int,
    val delayed: Int,
    val inTime: Int,
    val driverFirst: Int,
    val driverUnknown: Int,
    /** History read to the end, its date shown, its rows matching Grab's totals. */
    val historyComplete: Boolean,
    val rows: List<OrderRow>,
) {
    /** Delayed once the orders finished within the time the shop set are taken out. */
    val actualDelayed: Int get() = delayed - inTime
}

object OrderRows {
    fun of(date: LocalDate, o: OrderTimeline, zone: ZoneId, cfg: Config = Config.DEFAULT): OrderRow =
        row(date, o.gf, o.cancelled, o.delayMin, o.setTime, o.timeline, o.doneAt, zone, cfg)

    /** A delayed order as the report judged it (for the table sent to Grab). */
    fun of(date: LocalDate, c: DelayCase, zone: ZoneId, cfg: Config = Config.DEFAULT): OrderRow =
        row(date, c.gf, false, c.delayMin, c.verdict, c.timeline, c.doneAt, zone, cfg)

    private fun row(date: LocalDate, gf: String, cancelled: Boolean, delayMin: Int?, setTime: Verdict?,
                    t: Timeline, doneAt: LocalDateTime?, zone: ZoneId, cfg: Config) = OrderRow(
        date = date, gf = gf, cancelled = cancelled, grabDelayMin = delayMin,
        setTime = setTime?.takeIf { it != Verdict.NO_EVIDENCE },
        orderIn = t.prepFirstAt?.let { Seen(it, t.prepFirstAfter != null) },
        ready = t.pressedBy?.let { Seen(it, t.pressedAfter != null) },
        found = t.foundAt?.let { Seen(it, t.foundAfter != null) },
        arrived = t.arrivedAt?.let { Seen(it, t.arrivedAfter != null) },
        pickedUp = t.leftAt?.let { Seen(it, t.leftAfter != null) },
        deliveredAt = doneAt?.let { TimeResolve.toMillis(it, zone) },
        readyBeforeDriver = t.driverCameFirst?.not(),
        atClose = AtClose.of(t.readyStatus, cfg),
    )

    fun day(r: DailyReport, zone: ZoneId, cfg: Config = Config.DEFAULT): OrderDay = OrderDay(
        date = r.date, shopId = r.shopId, grabCompleted = r.grabCompleted, grabCancelled = r.grabCancelled,
        completedSeen = r.completedSeen, cancelledSeen = r.cancelledSeen, base = r.base, delayed = r.delayed,
        inTime = r.inTime.size, driverFirst = r.driverWaitedForFood.size, driverUnknown = r.driverWaitUnknown.size,
        historyComplete = r.complete, rows = r.orders.map { of(r.date, it, zone, cfg) },
    )

    /** "2:33", "-1:05" for a duration. */
    fun minSec(ms: Long): String {
        val s = Math.abs(ms) / 1000
        return (if (ms < 0) "-" else "") + "${s / 60}:" + Parsers.pad2((s % 60).toInt())
    }

    fun clock(ms: Long, zone: ZoneId): String = ReportText.timeSec(ms, zone)

    private fun moment(s: Seen?, zone: ZoneId): String? = s?.let { (if (it.exact) "" else "ไม่เกิน ") + clock(it.at, zone) }

    /** "10:23:05 (1:49)": when it happened, and how long it took from the step before. */
    private fun step(s: Seen?, took: Long?, zone: ZoneId): String =
        listOfNotNull(moment(s, zone), took?.let { "(" + minSec(it) + ")" }).joinToString(" ")

    val HEADER = listOf(
        "วันที่", "ออเดอร์", "สถานะ", "Grab ว่าล่าช้า (นาที)", "เวลาที่ร้านตั้ง", "ออเดอร์เข้า",
        "ร้านทำเสร็จ (เตรียมกี่นาที)", "เจอคนขับ (นาทีนับจากออเดอร์เข้า)", "คนขับมาถึงร้าน (นาทีนับจากเจอคนขับ)",
        "คนขับรับอาหาร (คนขับรอกี่นาที)", "ลูกค้าได้รับ (นาทีนับจากรับอาหาร)", "รวมทั้งหมด (นาทีนับจากออเดอร์เข้า)",
        "อาหารเสร็จก่อนคนขับมาถึง", "ตอนร้านปิดงาน", "ร้านปิดงาน → ลูกค้าได้รับ (นาที)",
    )

    /** One order as the owner reads it: each step "time (minutes)" (see [HEADER]). */
    fun cells(r: OrderRow, zone: ZoneId): List<String> = listOf(
        ReportText.date(r.date),
        r.gf,
        if (r.cancelled) "ยกเลิก" else "เสร็จ",
        r.grabDelayMin?.toString().orEmpty(),
        ReportText.setTimeText(r.setTime).orEmpty(),
        moment(r.orderIn, zone).orEmpty(),
        step(r.ready, r.prepMs, zone),
        step(r.found, r.toFoundMs, zone),
        step(r.arrived, r.toArriveMs, zone),
        step(r.pickedUp, r.driverWaitMs, zone),
        listOfNotNull(r.deliveredAt?.let { Parsers.hhmm(TimeResolve.toLocal(it, zone).toLocalTime()) }, r.rideMs?.let { "(" + minSec(it) + ")" })
            .joinToString(" "),
        r.totalMs?.let { minSec(it) }.orEmpty(),
        when (r.readyBeforeDriver) {
            true -> "ใช่" + (r.foodWaitMs?.takeIf { it >= 0 }?.let { " (อาหารรอคนขับ ${minSec(it)})" } ?: "")
            false -> "ไม่ — คนขับมาถึงก่อน"
            null -> "บอกไม่ได้"
        },
        r.atClose?.label ?: "บอกไม่ได้",
        r.closeToDeliveredMs?.let { minSec(it) }.orEmpty(),
    )
}

/** Day files kept on the phone (one per shop and day): never deleted, so averages can go back years. */
object OrderDayCodec {
    /** Raised when a day file gains something the old ones lack: every day is built again once. */
    const val VERSION = 2

    private fun seen(s: Seen?): Map<String, Any?>? = s?.let { linkedMapOf("at" to it.at, "x" to it.exact) }

    @Suppress("UNCHECKED_CAST")
    private fun seen(m: Any?): Seen? = (m as? Map<String, Any?>)?.let { v -> v.long("at")?.let { Seen(it, v.bool("x") == true) } }

    fun encode(d: OrderDay): String = Json.write(linkedMapOf(
        "v" to VERSION, "date" to d.date.toString(), "shopId" to d.shopId,
        "grabCompleted" to d.grabCompleted, "grabCancelled" to d.grabCancelled,
        "completedSeen" to d.completedSeen, "cancelledSeen" to d.cancelledSeen, "base" to d.base,
        "delayed" to d.delayed, "inTime" to d.inTime, "driverFirst" to d.driverFirst, "driverUnknown" to d.driverUnknown,
        "historyComplete" to d.historyComplete,
        "rows" to d.rows.map { r -> linkedMapOf(
            "gf" to r.gf, "cancelled" to r.cancelled, "delayMin" to r.grabDelayMin, "setTime" to r.setTime?.name,
            "in" to seen(r.orderIn), "ready" to seen(r.ready), "found" to seen(r.found), "arrived" to seen(r.arrived),
            "picked" to seen(r.pickedUp), "delivered" to r.deliveredAt, "readyFirst" to r.readyBeforeDriver,
            "atClose" to r.atClose?.name,
        ) },
    ))

    /** Null for a broken file (it is rebuilt from the day's records). */
    fun decode(text: String): OrderDay? = runCatching {
        val m = Json.parseObject(text)
        val date = LocalDate.parse(m.str("date")!!)
        OrderDay(
            date = date, shopId = m.str("shopId"), grabCompleted = m.int("grabCompleted"), grabCancelled = m.int("grabCancelled"),
            completedSeen = m.int("completedSeen") ?: 0, cancelledSeen = m.int("cancelledSeen") ?: 0, base = m.int("base") ?: 0,
            delayed = m.int("delayed") ?: 0, inTime = m.int("inTime") ?: 0, driverFirst = m.int("driverFirst") ?: 0,
            driverUnknown = m.int("driverUnknown") ?: 0, historyComplete = m.bool("historyComplete") == true,
            rows = m.objList("rows").mapNotNull { r ->
                OrderRow(
                    date = date, gf = r.str("gf") ?: return@mapNotNull null, cancelled = r.bool("cancelled") == true,
                    grabDelayMin = r.int("delayMin"), setTime = r.str("setTime")?.let { s -> Verdict.entries.firstOrNull { it.name == s } },
                    orderIn = seen(r["in"]), ready = seen(r["ready"]), found = seen(r["found"]), arrived = seen(r["arrived"]),
                    pickedUp = seen(r["picked"]), deliveredAt = r.long("delivered"), readyBeforeDriver = r.bool("readyFirst"),
                    atClose = r.str("atClose")?.let { a -> AtClose.entries.firstOrNull { it.name == a } },
                )
            },
        )
    }.getOrNull()
}

/**
 * The shop's Google Sheet (owner, 3 Nov): a summary page, Grab's delayed orders, every order with
 * its times, a line per day, and how to read them. Rebuilt whole from the day files every night.
 */
object ShopWorkbook {
    const val SUMMARY = "สรุป"
    const val DELAYED = "ออเดอร์ล่าช้า"
    const val ALL = "เวลาทุกออเดอร์"
    const val DAILY = "สรุปรายวัน"
    const val HELP = "วิธีอ่าน"

    /** A span of days ending on the latest day with data. */
    data class Period(val label: String, val from: LocalDate?, val to: LocalDate)

    fun periods(end: LocalDate): List<Period> = listOf(
        Period("7 วัน", end.minusDays(6), end),
        Period("1 เดือน", end.minusDays(29), end),
        Period("2 เดือน", end.minusDays(59), end),
        Period("3 เดือน", end.minusDays(89), end),
        Period("ปี ${end.year}", LocalDate.of(end.year, 1, 1), end),
        Period("ทั้งหมด", null, end),
    )

    private fun pct(n: Int, base: Int): String = if (base <= 0) "-" else String.format(Locale.ROOT, "%.2f%%", n * 100.0 / base)

    private fun avg(values: List<Long>): String = if (values.isEmpty()) "-" else OrderRows.minSec(values.sum() / values.size)

    private fun share(n: Int, of: Int): String = if (of <= 0) "-" else String.format(Locale.ROOT, "%.0f%%", n * 100.0 / of)

    private fun minutes(ms: Long?): Xlsx.Cell? = ms?.let { Xlsx.number(Math.round(it / 600.0) / 100.0) }

    fun sheets(days: List<OrderDay>, shopLabel: String, updatedAt: LocalDateTime, zone: ZoneId): List<Xlsx.Sheet> {
        val sorted = days.sortedByDescending { it.date }
        val rows = sorted.flatMap { d -> d.rows.sortedBy { it.orderIn?.at ?: it.ready?.at ?: it.deliveredAt ?: Long.MAX_VALUE } }
        return listOf(
            summary(sorted, shopLabel, updatedAt),
            orders(DELAYED, rows.filter { it.grabDelayMin != null }, zone, numbers = false),
            orders(ALL, rows, zone, numbers = true),
            daily(sorted),
            help(),
        )
    }

    private fun bold(s: String) = Xlsx.text(s, bold = true)
    private fun t(s: String) = Xlsx.text(s)

    private fun summary(days: List<OrderDay>, shopLabel: String, updatedAt: LocalDateTime): Xlsx.Sheet {
        val out = ArrayList<List<Xlsx.Cell?>>()
        out += listOf(bold("สรุปการรับ-ส่งออเดอร์ — ร้าน$shopLabel"))
        val first = days.lastOrNull()?.date
        val latest = days.firstOrNull()
        out += listOf(t("อัปเดตล่าสุด " + ReportText.date(updatedAt.toLocalDate()) + " " + Parsers.hhmm(updatedAt.toLocalTime()) +
            (if (first != null && latest != null) " · ข้อมูล ${days.size} วัน (${ReportText.date(first)} – ${ReportText.date(latest.date)})" else " · ยังไม่มีข้อมูล")))
        out += emptyList<Xlsx.Cell?>()
        if (latest == null) return Xlsx.Sheet(SUMMARY, out, listOf(46.0, 22.0))
        out += listOf(bold("วันล่าสุด " + ReportText.date(latest.date)))
        out += listOf(t("ออเดอร์ทั้งหมดตาม Grab"), t(latest.grabCompleted?.let { "เสร็จ $it · ยกเลิก ${latest.grabCancelled ?: 0}" }
            ?: "ยังอ่านยอดของ Grab ไม่ได้ (แอปเห็น ${latest.completedSeen})"))
        out += listOf(t("1) Grab ว่าล่าช้า"), t("${latest.delayed} ออเดอร์ = ${pct(latest.delayed, latest.base)}"))
        out += listOf(t("2) ตัดที่ร้านเสร็จทันเวลาที่ตั้ง"), t("${latest.actualDelayed} ออเดอร์ = ${pct(latest.actualDelayed, latest.base)}"))
        out += listOf(t("3) ที่ถูกต้อง (คนขับมาถึงแล้วอาหารยังไม่เสร็จ)"), t("${latest.driverFirst} ออเดอร์ = ${pct(latest.driverFirst, latest.base)}" +
            if (latest.driverUnknown > 0) " · บอกไม่ได้ ${latest.driverUnknown}" else ""))
        val closed = latest.rows.filter { !it.cancelled && it.atClose != null }
        out += listOf(t("ร้านปิดงานตอนยังไม่มีคนขับ"), t(if (closed.isEmpty()) "-" else
            "${closed.count { it.atClose == AtClose.NO_DRIVER }} จาก ${closed.size} ออเดอร์ = " +
                share(closed.count { it.atClose == AtClose.NO_DRIVER }, closed.size)))
        out += listOf(t("อ่านหน้าประวัติครบ"), t(if (latest.historyComplete) "ครบ" else "ไม่ครบ — ตัวเลขยังไม่ยืนยัน"))
        out += emptyList<Xlsx.Cell?>()

        val periods = periods(latest.date)
        fun inP(p: Period) = days.filter { (p.from == null || !it.date.isBefore(p.from)) && !it.date.isAfter(p.to) }
        out += listOf(bold("% ล่าช้า")) + periods.map { bold(it.label) }
        out += listOf(t("จำนวนวัน")) + periods.map { t(inP(it).size.toString()) }
        out += listOf(t("ออเดอร์ทั้งหมด (ตาม Grab)")) + periods.map { p -> t(inP(p).sumOf { it.base }.toString()) }
        out += listOf(t("1) แบบ Grab")) + periods.map { p -> inP(p).let { d -> t(pct(d.sumOf { it.delayed }, d.sumOf { it.base })) } }
        out += listOf(t("2) ตัดที่ร้านเสร็จทันเวลาที่ตั้ง")) + periods.map { p -> inP(p).let { d -> t(pct(d.sumOf { it.actualDelayed }, d.sumOf { it.base })) } }
        out += listOf(t("3) ที่ถูกต้อง (คนขับมาถึงแล้วอาหารยังไม่เสร็จ)")) + periods.map { p -> inP(p).let { d -> t(pct(d.sumOf { it.driverFirst }, d.sumOf { it.base })) } }
        out += emptyList<Xlsx.Cell?>()

        out += listOf(bold("ค่าเฉลี่ย (นาที:วินาที)")) + periods.map { bold(it.label) }
        val byPeriod = periods.associateWith { p -> inP(p).flatMap { it.rows }.filter { !it.cancelled } }
        fun orders(p: Period) = byPeriod.getValue(p)
        fun metric(label: String, f: (OrderRow) -> Long?) {
            out += listOf(t(label)) + periods.map { p -> t(avg(orders(p).mapNotNull(f))) }
        }
        metric("เตรียมอาหาร (ออเดอร์เข้า → ร้านทำเสร็จ)") { it.prepMs }
        metric("ออเดอร์เข้า → เจอคนขับ") { it.toFoundMs }
        metric("เจอคนขับ → คนขับมาถึงร้าน") { it.toArriveMs }
        metric("ร้านทำเสร็จ → คนขับมาถึงร้าน (อาหารรอคนขับ; ติดลบ = คนขับมาก่อน)") { it.foodWaitMs }
        metric("คนขับมาถึงร้าน → รับอาหาร (คนขับรอ)") { it.driverWaitMs }
        metric("รับอาหาร → ลูกค้าได้รับ") { it.rideMs }
        metric("รวมทั้งหมด (ออเดอร์เข้า → ลูกค้าได้รับ)") { it.totalMs }
        metric("ร้านปิดงาน → เจอคนขับ (เฉพาะที่ปิดงานตอนยังไม่มีคนขับ)") { it.closeToFoundMs }
        metric("ร้านปิดงาน → ลูกค้าได้รับ") { it.closeToDeliveredMs }
        out += listOf(t("% ร้านปิดงานตอนยังไม่มีคนขับ")) + periods.map { p ->
            orders(p).let { o -> t(share(o.count { it.atClose == AtClose.NO_DRIVER }, o.count { it.atClose != null })) }
        }
        out += listOf(t("% ร้านทำเสร็จทันเวลาที่ตั้ง")) + periods.map { p ->
            orders(p).let { o -> t(share(o.count { it.setTime == Verdict.IN_TIME }, o.count { it.setTime == Verdict.IN_TIME || it.setTime == Verdict.LATE })) }
        }
        out += listOf(t("% อาหารเสร็จก่อนคนขับมาถึง")) + periods.map { p ->
            orders(p).let { o -> t(share(o.count { it.readyBeforeDriver == true }, o.count { it.readyBeforeDriver != null })) }
        }
        out += listOf(t("ออเดอร์ที่มีเวลาเตรียมครบ")) + periods.map { p -> t(orders(p).count { it.prepMs != null }.toString()) }
        out += emptyList<Xlsx.Cell?>()
        out += listOf(t("เวลาทั้งหมดเป็นเวลาที่จอ Grab ในเครื่องร้านแสดง · รายละเอียดดูแท็บ \"$HELP\""))
        return Xlsx.Sheet(SUMMARY, out, listOf(52.0) + List(periods.size) { 13.0 })
    }

    private val NUMBER_HEADER = listOf(
        "เตรียม (นาที)", "เข้า→เจอคนขับ (นาที)", "เจอคนขับ→ถึงร้าน (นาที)", "อาหารรอคนขับ (นาที)",
        "คนขับรอ (นาที)", "รับอาหาร→ส่งถึง (นาที)", "รวม (นาที)", "ปิดงาน→เจอคนขับ (นาที)", "ปิดงาน→ส่งถึง (นาที)",
    )

    private fun orders(name: String, rows: List<OrderRow>, zone: ZoneId, numbers: Boolean): Xlsx.Sheet {
        val header = (OrderRows.HEADER + if (numbers) NUMBER_HEADER else emptyList()).map { bold(it) }
        // Each row's cells are made only when written: years of orders never sit in memory as cells.
        val out = object : AbstractList<List<Xlsx.Cell?>>() {
            override val size: Int get() = rows.size + 1
            override fun get(index: Int): List<Xlsx.Cell?> {
                if (index == 0) return header
                val r = rows[index - 1]
                return OrderRows.cells(r, zone).map { t(it) } + if (numbers) listOf(
                    minutes(r.prepMs), minutes(r.toFoundMs), minutes(r.toArriveMs), minutes(r.foodWaitMs),
                    minutes(r.driverWaitMs), minutes(r.rideMs), minutes(r.totalMs),
                    minutes(r.closeToFoundMs), minutes(r.closeToDeliveredMs),
                ) else emptyList()
            }
        }
        val widths = listOf(11.0, 10.0, 7.0, 10.0, 16.0, 18.0, 22.0, 22.0, 22.0, 22.0, 16.0, 12.0, 26.0, 20.0, 14.0) +
            if (numbers) List(NUMBER_HEADER.size) { 12.0 } else emptyList()
        return Xlsx.Sheet(name, out, widths, frozenRows = 1)
    }

    private fun daily(days: List<OrderDay>): Xlsx.Sheet {
        val out = ArrayList<List<Xlsx.Cell?>>()
        out += listOf(
            "วันที่", "เสร็จ (Grab)", "ยกเลิก (Grab)", "Grab ว่าล่าช้า", "% แบบ Grab", "หลังตัดที่ทันเวลาที่ตั้ง", "%",
            "ที่ถูกต้อง", "%", "บอกไม่ได้", "เตรียมเฉลี่ย", "คนขับรอเฉลี่ย", "รวมเฉลี่ย",
            "ปิดงานตอนยังไม่มีคนขับ", "%", "ปิดงาน→เจอคนขับ เฉลี่ย", "ปิดงาน→ลูกค้าได้รับ เฉลี่ย", "อ่านประวัติครบ",
        ).map { bold(it) }
        for (d in days) {
            val o = d.rows.filter { !it.cancelled }
            out += listOf(
                t(ReportText.date(d.date)),
                Xlsx.number((d.grabCompleted ?: d.completedSeen).toDouble()),
                Xlsx.number((d.grabCancelled ?: d.cancelledSeen).toDouble()),
                Xlsx.number(d.delayed.toDouble()), t(pct(d.delayed, d.base)),
                Xlsx.number(d.actualDelayed.toDouble()), t(pct(d.actualDelayed, d.base)),
                Xlsx.number(d.driverFirst.toDouble()), t(pct(d.driverFirst, d.base)),
                Xlsx.number(d.driverUnknown.toDouble()),
                t(avg(o.mapNotNull { it.prepMs })), t(avg(o.mapNotNull { it.driverWaitMs })), t(avg(o.mapNotNull { it.totalMs })),
                Xlsx.number(o.count { it.atClose == AtClose.NO_DRIVER }.toDouble()),
                t(share(o.count { it.atClose == AtClose.NO_DRIVER }, o.count { it.atClose != null })),
                t(avg(o.mapNotNull { it.closeToFoundMs })), t(avg(o.mapNotNull { it.closeToDeliveredMs })),
                t(if (d.historyComplete) "ครบ" else "ไม่ครบ"),
            )
        }
        return Xlsx.Sheet(DAILY, out, listOf(11.0, 10.0, 10.0, 11.0, 10.0, 14.0, 9.0, 10.0, 9.0, 9.0, 11.0, 12.0, 10.0, 14.0, 7.0, 14.0, 16.0, 12.0), frozenRows = 1)
    }

    private fun help(): Xlsx.Sheet = Xlsx.Sheet(HELP, listOf(
        "วิธีอ่านตาราง",
        "• เวลาทุกช่องคือเวลาที่จอ Grab ในเครื่องร้านแสดง (แอปเฝ้าจออยู่ตลอด) ส่งเสร็จมาจากหน้าประวัติของ Grab ซึ่งบอกแค่ระดับนาที",
        "• รูปแบบ \"10:23:05 (1:49)\" = เกิดตอน 10:23:05 และใช้เวลา 1 นาที 49 วินาที นับจากขั้นก่อนหน้า (บอกในหัวคอลัมน์ว่านับจากไหน)",
        "• \"ไม่เกิน 10:23:05\" = แอปเห็นตอนนั้น แต่ไม่รู้ว่าเกิดก่อนนานแค่ไหน จึงไม่คำนวณนาทีจากเวลานี้",
        "• ร้านทำเสร็จ = ร้านปิดงาน (ถ่ายรูปยืนยันในแอป Grab หรือกดพร้อมจัดส่ง) ออเดอร์ย้ายไปหน้าพร้อมจัดส่ง",
        "• คนขับมาถึงร้าน = การ์ด Grab ขึ้น \"คนขับของคุณมาถึงแล้ว\" · คนขับรับอาหาร = ออเดอร์หายจากหน้าพร้อมจัดส่ง",
        "• เวลาที่ร้านตั้ง: บอกแค่ร้านทำเสร็จทันเวลาที่ตั้งไว้หรือเกิน (การ์ดแดง 0:00 = เกิน)",
        "• อาหารเสร็จก่อนคนขับมาถึง: ใช่ = ร้านปิดงานก่อนคนขับถึงร้าน เวลาหลังจากนั้นไม่ใช่เวลาที่ร้านทำให้ช้า",
        "• ตอนร้านปิดงาน = ข้อความบนการ์ดตอนออเดอร์เข้าหน้าพร้อมจัดส่ง (ยังไม่มีคนขับ = \"กำลังค้นหาคนขับ\") มีทุกวันตั้งแต่ 7 ต.ค. 2026",
        "• ร้านปิดงาน → เจอคนขับ: เฉพาะออเดอร์ที่ร้านปิดงานตอนยังไม่มีคนขับ มีตั้งแต่ 29 ต.ค. 2026 (ก่อนหน้านั้นแอปยังไม่ได้จดเวลาเจอคนขับ)",
        "• ร้านปิดงาน → ลูกค้าได้รับ: นับจากที่แอปเห็นออเดอร์ในหน้าพร้อมจัดส่ง ถึงเวลาส่งเสร็จในหน้าประวัติ (ระดับนาที) มีทุกวัน",
        "• % ล่าช้า 3 แบบ ใช้ฐานเดียวกัน (ออเดอร์ทั้งหมดตามยอดของ Grab): 1) แบบ Grab 2) ตัดออเดอร์ที่ร้านเสร็จทันเวลาที่ตั้ง " +
            "3) ที่ถูกต้อง = นับเฉพาะออเดอร์ที่คนขับมาถึงร้านแล้วอาหารยังไม่เสร็จ",
        "• ค่าเฉลี่ยคิดเฉพาะออเดอร์ที่มีเวลาครบทั้งสองฝั่ง · ช่วงเวลานับย้อนจากวันล่าสุดที่มีข้อมูล",
        "• ตารางนี้แอปเขียนทับใหม่ทุกคืนหลังปิดร้าน (แก้ไขในชีตเองจะหาย)",
    ).map { listOf(t(it)) }, listOf(140.0))
}
