package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.ZoneId

/**
 * The table sent to Grab before the photos (owner, 3 Nov): one delayed order a row, each step's
 * time with the minutes it took from the step before, so it can be read at a glance. The image
 * itself is drawn in the app (GrabPack); what it says is decided here.
 */
object GrabTable {
    enum class Tone { PLAIN, GOOD, BAD }

    /** One cell: its lines top to bottom, and whether it speaks for (GOOD) or against (BAD) the shop. */
    data class Cell(val lines: List<String>, val tone: Tone = Tone.PLAIN)

    val HEADER: List<List<String>> = listOf(
        listOf("ออเดอร์"),
        listOf("Grab ว่า", "ล่าช้า (นาที)"),
        listOf("เวลาที่", "ร้านตั้ง"),
        listOf("ออเดอร์เข้า"),
        listOf("ร้านทำเสร็จ", "(เตรียม)"),
        listOf("เจอคนขับ", "(จากออเดอร์เข้า)"),
        listOf("คนขับถึงร้าน", "(จากเจอคนขับ)"),
        listOf("คนขับรับอาหาร", "(คนขับรอ)"),
        listOf("ลูกค้าได้รับ", "(จากรับอาหาร)"),
        listOf("รวมทั้งหมด", "(จากออเดอร์เข้า)"),
        listOf("อาหารเสร็จก่อน", "คนขับถึงร้าน"),
    )

    val NOTES: List<String> = listOf(
        "เวลา = ตอนที่ขั้นนั้นเกิด · (นาที:วินาที) = ใช้เวลานานเท่าไรนับจากขั้นที่บอกในหัวคอลัมน์",
        "\"ไม่เกิน\" = จอแสดงแล้วตอนนั้น แต่ไม่รู้ว่าเกิดก่อนนานแค่ไหน จึงไม่คิดนาทีจากเวลานี้",
        "เวลาทั้งหมดจากจอแอป Grab Merchant ในเครื่องร้าน · ลูกค้าได้รับ = เวลาจากหน้าประวัติของ Grab (ระดับนาที)",
        "อาหารเสร็จก่อนคนขับถึงร้าน = ใช่: ร้านปิดงาน (ถ่ายรูป/กดพร้อมจัดส่ง) ก่อนคนขับมาถึง",
    )

    fun title(day: LocalDate, orders: Int, part: Int, parts: Int): String =
        "ออเดอร์ที่ Grab ระบุล่าช้า · ${ReportText.date(day)} · $orders ออเดอร์" + if (parts > 1) " (หน้า $part/$parts)" else ""

    /** The orders in this send, earliest in first. */
    fun rows(cases: List<DelayCase>, day: LocalDate, zone: ZoneId): List<List<Cell>> =
        cases.map { OrderRows.of(day, it, zone) }
            .sortedBy { it.orderIn?.at ?: it.ready?.at ?: it.deliveredAt ?: Long.MAX_VALUE }
            .map { row(it, zone) }

    fun row(r: OrderRow, zone: ZoneId): List<Cell> {
        val c = OrderRows.cells(r, zone)
        return listOf(
            Cell(listOf(r.gf)),
            Cell(listOf(r.grabDelayMin?.toString() ?: "-")),
            when (r.setTime) {
                Verdict.IN_TIME -> Cell(listOf("ทัน"), Tone.GOOD)
                Verdict.LATE -> Cell(listOf("ไม่ทัน"), Tone.BAD)
                else -> Cell(listOf("บอกไม่ได้"))
            },
            Cell(split(c[6])),
            Cell(split(c[7])),
            Cell(split(c[8])),
            Cell(split(c[9])),
            Cell(split(c[10])),
            Cell(split(c[11])),
            Cell(split(c[12])),
            when (r.readyBeforeDriver) {
                true -> Cell(split(c[13]), Tone.GOOD)
                false -> Cell(listOf("ไม่", "คนขับมาถึงก่อน"), Tone.BAD)
                null -> Cell(listOf("บอกไม่ได้"))
            },
        )
    }

    /** "ไม่เกิน 10:21:16 (1:49)" → "ไม่เกิน" / "10:21:16" / "(1:49)"; nothing known → "-". */
    fun split(s: String): List<String> {
        if (s.isBlank()) return listOf("-")
        val out = ArrayList<String>()
        var rest = s.trim()
        if (rest.startsWith("ไม่เกิน ")) {
            out += "ไม่เกิน"
            rest = rest.removePrefix("ไม่เกิน ")
        }
        val i = rest.indexOf(" (")
        if (i >= 0) {
            out += rest.substring(0, i)
            out += rest.substring(i + 1)
        } else {
            out += rest
        }
        return out
    }
}
