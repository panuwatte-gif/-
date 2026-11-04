package io.github.panuwattegif.readyproof.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderSheetTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private var seq = 0

    private fun ms(d: Int, h: Int, mi: Int, s: Int = 0) = TimeResolve.toMillis(LocalDateTime.of(2026, 11, d, h, mi, s), zone)

    private fun rec(t: Long, kind: RecordKind, vararg items: Item, uri: String? = null) =
        Record(id = "r${seq++}", t = t, kind = kind, items = items.toList(), uri = uri, shopId = Shop.KAPRAO.id)

    /** 2 Nov: GF-033 (Grab: 4 minutes late, the food was ready 17 minutes before its driver came) and GF-864F. */
    private fun day2(): DailyReport {
        val records = listOf(
            rec(ms(2, 10, 21, 16), RecordKind.SEEN, Item("GF-033", ObsType.PREPARING, countdown = "7:00", prevAt = ms(2, 10, 19, 37))),
            rec(ms(2, 10, 23, 5), RecordKind.READY, Item("GF-033", ObsType.READY, status = "GF-033 กำลังค้นหาคนขับ... 1 รายการ",
                prevAt = ms(2, 10, 22, 50)), uri = "content://a"),
            rec(ms(2, 10, 28, 45), RecordKind.SEEN, Item("GF-033", ObsType.DRIVER_FOUND, prevAt = ms(2, 10, 28, 20))),
            rec(ms(2, 10, 40, 12), RecordKind.SEEN, Item("GF-033", ObsType.ARRIVED, prevAt = ms(2, 10, 40, 12))),
            rec(ms(2, 10, 44, 9), RecordKind.SEEN, Item("GF-033", ObsType.LEFT_READY, prevAt = ms(2, 10, 43, 44))),
            rec(ms(2, 9, 10), RecordKind.SEEN, Item("GF-864F", ObsType.PREPARING, countdown = "8:00")),
            rec(ms(2, 9, 13, 3), RecordKind.SEEN, Item("GF-864F", ObsType.ARRIVED, prevAt = ms(2, 9, 9, 48))),
            rec(ms(2, 9, 15, 16), RecordKind.SEEN, Item("GF-864F", ObsType.PREPARING, countdown = "2:30")),
            rec(ms(2, 9, 16, 21), RecordKind.READY, Item("GF-864F", ObsType.READY, status = "GF-864F คนขับของคุณมาถึงแล้ว 1 รายการ",
                prevAt = ms(2, 9, 15, 16)), uri = "content://b"),
            rec(ms(2, 9, 19, 40), RecordKind.SEEN, Item("GF-864F", ObsType.LEFT_READY, prevAt = ms(2, 9, 18, 59))),
            rec(ms(2, 19, 5), RecordKind.DELAY,
                Item("GF-033", ObsType.DELAY, delayMin = 4, doneAt = "10:59"),
                Item("GF-864F", ObsType.DONE, doneAt = "09:41"), uri = "content://h").copy(historyDate = "2026-11-02"),
            Record("st", ms(2, 19, 6), RecordKind.STATS, historyDate = "2026-11-02", completed = 2, cancelled = 0, shopId = Shop.KAPRAO.id),
        )
        return ReportBuilder.build(records, LocalDate.of(2026, 11, 2), zone, Config.DEFAULT, Shop.KAPRAO.id, sweepReachedEnd = true)
    }

    @Test
    fun eachStepWithHowLongItTookFromTheStepBefore() {
        val day = OrderRows.day(day2(), zone)
        assertTrue(day.historyComplete) // Grab counted 2, both read
        assertEquals(2, day.base)
        val r = day.rows.first { it.gf == "GF-033" }
        // order in 10:21:16 (seen), ready 10:23:05: 1:49 to make
        assertEquals(109_000, r.prepMs)
        assertEquals(449_000, r.toFoundMs) // → driver found 10:28:45
        assertEquals(687_000, r.toArriveMs) // → at the shop 10:40:12
        assertEquals(1_027_000, r.foodWaitMs) // the food waited 17:07 for its driver
        assertEquals(237_000, r.driverWaitMs) // the driver took it 3:57 after arriving
        assertEquals(891_000, r.rideMs) // delivered 10:59 (to the minute)
        assertEquals(2_264_000, r.totalMs)
        assertEquals(listOf(
            "02/11/2026", "GF-033", "เสร็จ", "4", "ทันเวลาที่ตั้ง", "10:21:16", "10:23:05 (1:49)", "10:28:45 (7:29)",
            "10:40:12 (11:27)", "10:44:09 (3:57)", "10:59 (14:51)", "37:44", "ใช่ (อาหารรอคนขับ 17:07)",
            "ยังไม่มีคนขับ", "35:55",
        ), OrderRows.cells(r, zone))
        // closed with no driver yet: Grab found one 5:40 after the photo; delivered 35:55 after it
        assertEquals(AtClose.NO_DRIVER, r.atClose)
        assertEquals(340_000, r.closeToFoundMs)
        assertEquals(2_155_000, r.closeToDeliveredMs)
        // the driver was at the shop before the food was ready; a "by then" alone gives no minutes
        val f = day.rows.first { it.gf == "GF-864F" }
        assertEquals(false, f.readyBeforeDriver)
        assertNull(f.prepMs) // order in only "by 09:10:00"
        assertEquals("ไม่เกิน 09:10:00", OrderRows.cells(f, zone)[5])
        assertEquals("ไม่ — คนขับมาถึงก่อน", OrderRows.cells(f, zone)[12])
        assertEquals(AtClose.WAITING, f.atClose)
        assertNull(f.closeToFoundMs) // a driver was already there
        assertEquals(1, day.driverFirst)
        // kept on the phone and read back the same
        assertEquals(day, OrderDayCodec.decode(OrderDayCodec.encode(day)))
    }

    @Test
    fun theWorkbookHasTheSummaryTheTablesAndOpensAsXlsx() {
        val d2 = OrderRows.day(day2(), zone)
        // an older day, 40 days before: only in "all" and the year
        // its one order read before the app saw drivers arrive: never seen arriving is not "food first" there
        val early = d2.rows.first { it.gf == "GF-864F" }.copy(date = LocalDate.of(2026, 9, 23), gf = "GF-111", setTime = null,
            arrived = null, pickedUp = null, readyBeforeDriver = true)
        val old = d2.copy(date = LocalDate.of(2026, 9, 23), delayed = 2, inTime = 0, driverFirst = 0, base = 10, rows = listOf(early))
        val sheets = ShopWorkbook.sheets(listOf(old, d2), "กะเพรา", LocalDateTime.of(2026, 11, 2, 19, 30), zone)
        assertEquals(listOf("สรุป", "ออเดอร์ล่าช้า", "เวลาทุกออเดอร์", "สรุปรายวัน", "วิธีอ่าน"), sheets.map { it.name })
        fun texts(s: Xlsx.Sheet) = s.rows.map { row -> row.map { it?.text ?: it?.number?.toString() } }
        val summary = texts(sheets[0])
        assertTrue(summary.contains(listOf("ออเดอร์ทั้งหมดตาม Grab", "เสร็จ 2 · ยกเลิก 0")), summary.toString())
        assertTrue(summary.contains(listOf("1) Grab ว่าล่าช้า", "1 ออเดอร์ = 50.00%")), summary.toString())
        assertTrue(summary.contains(listOf("3) ที่ถูกต้อง (คนขับมาถึงแล้วอาหารยังไม่เสร็จ)", "1 ออเดอร์ = 50.00%")), summary.toString())
        assertTrue(summary.contains(listOf("ร้านปิดงานตอนยังไม่มีคนขับ", "1 จาก 2 ออเดอร์ = 50%")), summary.toString())
        assertTrue(summary.any { it.firstOrNull() == "ร้านปิดงาน → เจอคนขับ (เฉพาะที่ปิดงานตอนยังไม่มีคนขับ)" && it[1] == "5:40" }, summary.toString())
        // 7 days: only 2 Nov (1 of 2); all: 3 of 12
        assertTrue(summary.contains(listOf("1) แบบ Grab", "50.00%", "50.00%", "25.00%", "25.00%", "25.00%", "25.00%")), summary.toString())
        // the drivers waited 3:57 (GF-033) and 6:37 (GF-864F)
        assertTrue(summary.any { it.firstOrNull() == "คนขับมาถึงร้าน → รับอาหาร (คนขับรอ)" && it[1] == "5:17" }, summary.toString())
        // the old day has no set time and no arrivals: 2) and 3) come from 2 Nov alone, beside Grab's rate of that day
        val i = summary.indexOf(listOf("เทียบ 3 แบบ เฉพาะวันที่แอปจดเวลาที่ร้านตั้งและเวลาคนขับถึงร้านได้"))
        assertTrue(i > 0, summary.toString())
        assertEquals(listOf(
            listOf("จำนวนวัน", "1", "1", "1", "1", "1", "1"),
            listOf("ออเดอร์ทั้งหมด (ตาม Grab)", "2", "2", "2", "2", "2", "2"),
            listOf("1) แบบ Grab", "50.00%", "50.00%", "50.00%", "50.00%", "50.00%", "50.00%"),
            listOf("2) ตัดที่ร้านเสร็จทันเวลาที่ตั้ง", "0.00%", "0.00%", "0.00%", "0.00%", "0.00%", "0.00%"), // GF-033 was in time
            listOf("3) ที่ถูกต้อง (คนขับมาถึงแล้วอาหารยังไม่เสร็จ)", "50.00%", "50.00%", "50.00%", "50.00%", "50.00%", "50.00%"),
        ), summary.subList(i + 1, i + 6))
        // the daily tab says "-" for what the old day cannot tell
        val daily = texts(sheets[3])
        assertEquals(listOf("2.0", "20.00%", "-", "-", "-", "-", "-"), daily.first { it[0] == "23/09/2026" }.subList(3, 10))
        assertEquals(listOf("1.0", "50.00%", "0.0", "0.00%", "1.0", "50.00%", "0.0"), daily.first { it[0] == "02/11/2026" }.subList(3, 10))
        // Grab's delayed orders only; every order with numbers to compute with
        assertEquals(listOf("GF-033"), texts(sheets[1]).drop(1).map { it[1] })
        assertEquals(3, texts(sheets[2]).size - 1)
        assertEquals("บอกไม่ได้", texts(sheets[2]).first { it[1] == "GF-111" }[12])
        assertEquals("ไม่ — คนขับมาถึงก่อน", texts(sheets[2]).first { it[1] == "GF-864F" }[12])
        assertEquals("1.82", sheets[2].rows[2][15]?.number?.toString()?.take(4)) // GF-033 (made after GF-864F): 1:49 = 1.82 min

        val xlsx = Xlsx.write(sheets)
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(xlsx)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml",
            "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet5.xml")), entries.keys.toString())
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        entries.values.forEach { parser.parse(ByteArrayInputStream(it)) } // every part is well-formed XML
        val workbook = String(entries.getValue("xl/workbook.xml"), Charsets.UTF_8)
        assertTrue(workbook.contains("""<sheet name="ออเดอร์ล่าช้า" sheetId="2" r:id="rId2"/>"""), workbook)
        assertTrue(String(entries.getValue("xl/worksheets/sheet2.xml"), Charsets.UTF_8).contains("10:23:05 (1:49)"))
    }

    @Test
    fun theTableSentToGrabPutsEachTimeAboveItsMinutes() {
        val r = day2()
        val rows = GrabTable.rows(r.cases, r.date, zone)
        assertEquals(1, rows.size) // only GF-033 is delayed
        val row = rows.single()
        assertEquals(GrabTable.HEADER.size, row.size)
        assertEquals(listOf("GF-033"), row[0].lines)
        assertEquals(listOf("4"), row[1].lines)
        assertEquals(GrabTable.Cell(listOf("ทัน"), GrabTable.Tone.GOOD), row[2])
        assertEquals(listOf("10:21:16"), row[3].lines)
        assertEquals(listOf("10:23:05", "(1:49)"), row[4].lines)
        assertEquals(listOf("10:28:45", "(7:29)"), row[5].lines)
        assertEquals(listOf("10:40:12", "(11:27)"), row[6].lines)
        assertEquals(listOf("10:44:09", "(3:57)"), row[7].lines)
        assertEquals(listOf("10:59", "(14:51)"), row[8].lines)
        assertEquals(listOf("37:44"), row[9].lines)
        assertEquals(GrabTable.Cell(listOf("ใช่", "(อาหารรอคนขับ 17:07)"), GrabTable.Tone.GOOD), row[10])
        assertEquals(listOf("ไม่เกิน", "09:10:00"), GrabTable.split("ไม่เกิน 09:10:00"))
        assertEquals(listOf("-"), GrabTable.split(""))
        assertEquals("ออเดอร์ที่ Grab ระบุล่าช้า · 02/11/2026 · 20 ออเดอร์ (หน้า 1/2)", GrabTable.title(r.date, 20, 1, 2))
    }

    @Test
    fun theDriverWhenTheShopClosedItFromTheCardsLine() {
        assertEquals(AtClose.NO_DRIVER, AtClose.of("GF-033 กำลังค้นหาคนขับ... 1 รายการ"))
        assertEquals(AtClose.COMING, AtClose.of("GF-820 คนขับจะมารับใน 3 นาที 2 รายการ"))
        assertEquals(AtClose.WAITING, AtClose.of("GF-137 คนขับของคุณมาถึงแล้ว 1 รายการ"))
        assertEquals(AtClose.NO_DRIVER, AtClose.of("Finding a driver..."))
        assertNull(AtClose.of(null))
        assertNull(AtClose.of("GF-1 อื่นๆ"))
    }

    @Test
    fun xlsxNamesAndText() {
        assertEquals("A", Xlsx.column(0))
        assertEquals("Z", Xlsx.column(25))
        assertEquals("AA", Xlsx.column(26))
        assertEquals("BA", Xlsx.column(52))
        assertEquals("a b", Xlsx.safeName("a/b"))
        val x = Xlsx.write(listOf(Xlsx.Sheet("s", listOf(listOf(Xlsx.text("<&\"\u0001>"))))))
        var sheet = ""
        ZipInputStream(ByteArrayInputStream(x)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/worksheets/sheet1.xml") sheet = String(z.readBytes(), Charsets.UTF_8)
            }
        }
        assertTrue(sheet.contains("&lt;&amp;&quot;&gt;"), sheet)
    }
}
