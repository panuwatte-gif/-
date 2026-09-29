package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataAndReportTest {
    private val zone = ZoneId.of("Asia/Bangkok")
    private val cfg = Config.DEFAULT
    private var seq = 0

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        TimeResolve.toMillis(LocalDateTime.of(y, mo, d, h, mi, s), zone)

    private fun rec(t: Long, kind: RecordKind, vararg items: Item, uri: String? = "content://x/${seq}", visible: List<String> = emptyList()) =
        Record(id = "r${seq++}", t = t, kind = kind, items = items.toList(), visible = visible, uri = uri, file = "f$seq.jpg")

    @Test
    fun jsonRoundTrip() {
        val value = linkedMapOf(
            "s" to "ไทย \"quoted\" \\ back\nline\t\u0001",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "l" to listOf("a", 1L, false, listOf<Any?>()),
            "o" to mapOf("x" to "y"),
        )
        val text = Json.write(value)
        assertEquals(value, Json.parse(text))
        assertEquals(mapOf("k" to null), Json.parse("""{"k":null}"""))
        assertEquals("é😀", (Json.parse("\"\\u00e9\\ud83d\\ude00\"")))
        assertEquals(-3L, Json.parse(" -3 "))
        assertFailsWith<Json.ParseException> { Json.parse("{\"a\":1,}") }
        assertFailsWith<Json.ParseException> { Json.parse("[1 2]") }
        assertFailsWith<Json.ParseException> { Json.parse("\"open") }
        assertFailsWith<Json.ParseException> { Json.parse("{} x") }
        // nulls inside maps are omitted when writing
        assertEquals("{\"a\":1}", Json.write(mapOf("a" to 1, "b" to null)))
    }

    @Test
    fun recordRoundTrip() {
        val r = Record(
            id = "1-1", t = 1_790_000_000_000, kind = RecordKind.READY,
            items = listOf(Item("GF-613", ObsType.READY, status = "กำลังค้นหาคนขับ...", card = listOf("GF-613", "8 \"รายการ\""))),
            visible = listOf("GF-396", "GF-613"), uri = "content://media/1", file = "GF-613_READY.jpg",
        )
        assertEquals(r, RecordCodec.decode(RecordCodec.encode(r)))
        val press = Record("2", 5, RecordKind.PRESS, listOf(Item("GF-1", ObsType.PRESS, countdown = "5:53", delayMin = null)), click = "พร้อมจัดส่ง")
        assertEquals(press, RecordCodec.decode(RecordCodec.encode(press)))
        assertFalse(RecordCodec.encode(press).contains('\n'))
        assertNull(RecordCodec.decode("garbage"))
        assertNull(RecordCodec.decode(""))
        assertNull(RecordCodec.decode("""{"id":"x","t":1,"kind":"FUTURE_KIND"}"""))
        // unknown item types are dropped, unknown fields ignored
        val partial = RecordCodec.decode("""{"id":"x","t":1,"kind":"MANUAL","items":[{"gf":"GF-1","type":"NEW"},{"gf":"GF-2","type":"VISIBLE"}],"extra":5}""")
        assertEquals(listOf("GF-2"), partial?.gfs)
    }

    @Test
    fun configRoundTripAndRecovery() {
        val c = Config(pressTriggers = listOf("พร้อมจัดส่ง", "Mark as ready"), retentionDays = 45, diagnostics = true)
        assertEquals(c, ConfigCodec.decode(ConfigCodec.encode(c)))
        assertEquals(Config.DEFAULT, ConfigCodec.decodeOrDefault("{broken"))
        assertEquals(Config.DEFAULT, ConfigCodec.decodeOrDefault(null))
        // only the broken field is replaced
        val fixed = ConfigCodec.decodeOrDefault("""{"gfPattern":"([","retentionDays":999,"showToast":false}""")
        assertEquals(Config.DEFAULT.gfPattern, fixed.gfPattern)
        assertEquals(365, fixed.retentionDays)
        assertFalse(fixed.showToast)
        assertTrue(Config(gfPattern = "([").validate().isNotEmpty())
        assertTrue(Config.DEFAULT.validate().isEmpty())
        assertFalse(Config.isUsablePattern(""))
        assertFalse(Config.isUsablePattern("""\d*"""))
        assertTrue(Config.isUsablePattern(Config.DEFAULT.gfPattern))
        assertEquals(Config.DEFAULT.gfPattern, ConfigCodec.decodeOrDefault("""{"gfPattern":"x?"}""").gfPattern)
        assertTrue(Config(jpegQuality = -1, pressTriggers = emptyList()).validate().size == 2)
        assertEquals(listOf("a", "b c"), Config.lines(" a \n\n b   c \na"))
    }

    @Test
    fun dedupeWindows() {
        val d = Deduper()
        val ready = Item("GF-613", ObsType.READY)
        val t0 = 1_000_000L
        assertEquals(listOf(ready), d.fresh(listOf(ready), t0, cfg))
        d.mark(listOfNotNull(Deduper.keyOf(ready)), t0)
        assertTrue(d.fresh(listOf(ready), t0 + 60 * 60_000L, cfg).isEmpty())
        assertEquals(listOf(ready), d.fresh(listOf(ready), t0 + 91 * 60_000L, cfg))
        // same order number finished at another time is a different order
        val done1 = Item("GF-613", ObsType.DONE, doneAt = "11:20")
        val done2 = Item("GF-613", ObsType.DONE, doneAt = "18:05")
        d.mark(listOfNotNull(Deduper.keyOf(done1)), t0)
        assertEquals(listOf(done2), d.fresh(listOf(done1, done2), t0 + 1000, cfg))
        d.forget(listOfNotNull(Deduper.keyOf(done1)))
        assertEquals(1, d.fresh(listOf(done1), t0 + 1000, cfg).size)
        // presses are never deduplicated here
        assertEquals(1, d.fresh(listOf(Item("GF-1", ObsType.PRESS)), t0, cfg).size)
        val seeded = Deduper().apply { seed(listOf(rec(t0, RecordKind.READY, ready))) }
        assertTrue(seeded.fresh(listOf(ready), t0 + 1000, cfg).isEmpty())
    }

    @Test
    fun resolveFinishTimeAcrossMidnight() {
        val ref = LocalDateTime.of(2026, 9, 24, 0, 30)
        assertEquals(LocalDateTime.of(2026, 9, 23, 23, 50), TimeResolve.latestAtOrBefore(LocalTime.of(23, 50), ref))
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 10), TimeResolve.latestAtOrBefore(LocalTime.of(0, 10), ref))
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 30), TimeResolve.latestAtOrBefore(LocalTime.of(0, 30), ref))
    }

    @Test
    fun dailyReportMatchesEvidenceToDelays() {
        val day = LocalDate.of(2026, 9, 28)
        val records = listOf(
            // yesterday: same number, must not count as evidence for today's order
            rec(ms(2026, 9, 27, 12, 30), RecordKind.READY, Item("GF-156", ObsType.READY, status = "กำลังค้นหาคนขับ...")),
            rec(ms(2026, 9, 28, 10, 58), RecordKind.PRESS, Item("GF-613", ObsType.PRESS, countdown = "5:53")).copy(click = "พร้อมจัดส่ง"),
            rec(ms(2026, 9, 28, 11, 1), RecordKind.READY,
                Item("GF-613", ObsType.READY, status = "กำลังค้นหาคนขับ..."), Item("GF-396", ObsType.READY, status = "กำลังค้นหาคนขับ...")),
            rec(ms(2026, 9, 28, 11, 30), RecordKind.MANUAL, visible = listOf("GF-777")),
            // history review at 22:14 on the same day
            rec(ms(2026, 9, 28, 22, 14), RecordKind.DELAY,
                Item("GF-613", ObsType.DELAY, delayMin = 4, doneAt = "11:20"),
                Item("GF-156", ObsType.DELAY, delayMin = 6, doneAt = "12:40"),
                Item("GF-777", ObsType.DELAY, delayMin = null, doneAt = "12:00")),
            rec(ms(2026, 9, 28, 22, 14), RecordKind.SEEN,
                Item("GF-613", ObsType.DONE, doneAt = "11:20", delayMin = 4),
                Item("GF-156", ObsType.DONE, doneAt = "12:40", delayMin = 6),
                Item("GF-777", ObsType.DONE, doneAt = "12:00"),
                Item("GF-722", ObsType.DONE, doneAt = "12:18"), uri = null),
            // seen again the next morning: must not double count
            rec(ms(2026, 9, 29, 8, 0), RecordKind.SEEN, Item("GF-722", ObsType.DONE, doneAt = "12:18"), uri = null),
        )
        val r = ReportBuilder.build(records, day, zone, cfg)
        assertEquals(4, r.completedSeen)
        assertEquals(3, r.delayed)
        assertEquals(listOf("GF-613", "GF-777"), r.withEvidence.map { it.gf })
        assertEquals(listOf("GF-156"), r.withoutEvidence.map { it.gf })
        val c613 = r.cases.first { it.gf == "GF-613" }
        assertEquals(4, c613.delayMin)
        assertEquals(ObsType.READY, c613.evidence.first().type)
        assertEquals(2, c613.bestShots().size)
        assertEquals(1, c613.delayShots.size)
        assertEquals(ObsType.VISIBLE, r.cases.first { it.gf == "GF-777" }.evidence.single().type)
        assertEquals(1, r.pressedOrders)
        assertEquals(2, r.readyOrders)
        assertEquals(75.0, r.pct(r.delayed))
        // history shot + READY + PRESS for 613 + manual for 777
        assertEquals(4, r.shareRecords().size)

        val text = ReportText.summary(r, zone)
        assertTrue(text.contains("วันที่ 28/09/2026"))
        assertTrue(text.contains("GF-613 ล่าช้า 4 นาที (เสร็จ 11:20) — READY 11:01 \"กำลังค้นหาคนขับ...\" · กดพร้อมจัดส่ง 10:58 (เหลือ 5:53)"))
        assertTrue(text.contains("❌ ไม่มีหลักฐาน\nGF-156 ล่าช้า 6 นาที (เสร็จ 12:40)"))
        assertTrue(text.contains("(75.0%)"))

        val csv = ReportText.csv(r, zone)
        assertTrue(csv.startsWith("﻿date,gf,"))
        assertEquals(4, csv.trim().lines().size)
        assertTrue(csv.contains("2026-09-28,GF-613,4,11:20,yes,11:01,กำลังค้นหาคนขับ...,10:58,5:53,"))
        assertTrue(csv.contains("2026-09-28,GF-156,6,12:40,no,"))
    }

    @Test
    fun latestPressWinsOverAnEarlierMisread() {
        val day = LocalDate.of(2026, 9, 28)
        val early = rec(ms(2026, 9, 28, 10, 40), RecordKind.PRESS, Item("GF-613", ObsType.PRESS, countdown = "15:00"))
        val real = rec(ms(2026, 9, 28, 10, 58), RecordKind.PRESS, Item("GF-613", ObsType.PRESS, countdown = "5:53"))
        val history = rec(ms(2026, 9, 28, 22, 0), RecordKind.DELAY, Item("GF-613", ObsType.DELAY, delayMin = 4, doneAt = "11:20"))
        val c = ReportBuilder.build(listOf(early, real, history), day, zone, cfg).cases.single()
        assertEquals(real.id, c.pick(ObsType.PRESS)?.record?.id)
        assertEquals(listOf(real.id), c.bestShots().map { it.id })
        assertTrue(ReportText.caseLine(c, zone).contains("กดพร้อมจัดส่ง 10:58 (เหลือ 5:53)"))
    }

    @Test
    fun reportForEmptyDay() {
        val r = ReportBuilder.build(emptyList(), LocalDate.of(2026, 9, 28), zone, cfg)
        assertEquals(0, r.completedSeen)
        assertNull(r.pct(0))
        assertTrue(ReportText.summary(r, zone).contains("(-)"))
    }

    @Test
    fun fileNames() {
        val at = LocalDateTime.of(2026, 9, 23, 22, 15, 12)
        assertEquals(
            "GF-450_DELAY-10m_GF-697_DELAY-6m_2026-09-23_22-15-12.jpg",
            Naming.fileName(RecordKind.DELAY, listOf(
                Item("GF-450", ObsType.DELAY, delayMin = 10), Item("GF-450", ObsType.DONE),
                Item("GF-697", ObsType.DELAY, delayMin = 6),
            ), emptyList(), at),
        )
        assertEquals("GF-613_READY_2026-09-23_22-15-12.jpg",
            Naming.fileName(RecordKind.READY, listOf(Item("GF-613", ObsType.READY)), emptyList(), at))
        assertEquals("GF-1_GF-2_GF-3_GF-4_plus1_MANUAL_2026-09-23_22-15-12.jpg",
            Naming.fileName(RecordKind.MANUAL, emptyList(), listOf("GF-1", "GF-2", "GF-3", "GF-4", "GF-5"), at))
        assertEquals("NOGF_PRESS_2026-09-23_22-15-12.jpg", Naming.fileName(RecordKind.PRESS, emptyList(), emptyList(), at))
        assertEquals("X_Y_PRESS_2026-09-23_22-15-12.jpg",
            Naming.fileName(RecordKind.PRESS, listOf(Item("X/Y", ObsType.PRESS)), emptyList(), at))
    }

    @Test
    fun searchTextIncludesEverything() {
        val r = rec(1, RecordKind.PRESS, Item("GF-613", ObsType.PRESS, card = listOf("ข้าวมันไก่"))).copy(click = "พร้อมจัดส่ง")
        val s = r.searchText()
        assertTrue(s.contains("GF-613") && s.contains("ข้าวมันไก่") && s.contains("กดพร้อมจัดส่ง"))
        assertNotNull(r.file)
    }
}
