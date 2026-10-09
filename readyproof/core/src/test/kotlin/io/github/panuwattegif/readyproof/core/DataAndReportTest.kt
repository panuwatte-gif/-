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
        val stats = Record("s", 7, RecordKind.STATS, historyDate = "2026-10-08", completed = 77, cancelled = 0, shopId = "kaprao")
        assertEquals(stats, RecordCodec.decode(RecordCodec.encode(stats)))
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
        val c = Config(closeTime = "20:30", recheckMinutes = 7, lateStatus = listOf("arrived"), retentionDays = 45, diagnostics = true)
        assertEquals(c, ConfigCodec.decode(ConfigCodec.encode(c)))
        assertEquals(Config.DEFAULT, ConfigCodec.decodeOrDefault("{broken"))
        assertEquals(Config.DEFAULT, ConfigCodec.decodeOrDefault(null))
        // only the broken field is replaced
        val fixed = ConfigCodec.decodeOrDefault("""{"v":4,"gfPattern":"([","retentionDays":999,"closeTime":"7pm","showToast":true}""")
        assertEquals(Config.DEFAULT.gfPattern, fixed.gfPattern)
        assertEquals(365, fixed.retentionDays)
        assertEquals("19:00", fixed.closeTime)
        assertTrue(fixed.showToast)
        assertTrue(Config(gfPattern = "([").validate().isNotEmpty())
        assertTrue(Config.DEFAULT.validate().isEmpty())
        assertFalse(Config.isUsablePattern(""))
        assertFalse(Config.isUsablePattern("""\d*"""))
        assertTrue(Config.isUsablePattern(Config.DEFAULT.gfPattern))
        assertEquals(Config.DEFAULT.gfPattern, ConfigCodec.decodeOrDefault("""{"gfPattern":"x?"}""").gfPattern)
        assertEquals(2, Config(jpegQuality = -1, closeTime = "25:00").validate().size)
        assertEquals(listOf("a", "b c"), Config.lines(" a \n\n b   c \na"))
        assertEquals(java.time.LocalTime.of(19, 5), Config.DEFAULT.endOfDayAt())
        assertEquals(java.time.LocalTime.of(7, 5), Config.parseTime("7.05"))
        assertNull(Config.parseTime("19:60"))
    }

    @Test
    fun olderSavesUpgrade() {
        // v1 (Thai only, screenshot on every tap): keeps its words, gains the English ones, no pop-ups
        val v1 = ConfigCodec.decode("""{"v":1,"pressTriggers":["พร้อมจัดส่ง","Serve"],"capturePress":true,"doneAny":["เสร็จสมบูรณ์"],"delayAny":["ล่าช้าไป"],"showToast":true}""")
        assertTrue("Completed" in v1.doneAny && "Delayed by" in v1.delayAny)
        assertFalse(v1.showToast)
        assertEquals(Config.DEFAULT.readyTabLabels, v1.readyTabLabels)
        assertTrue(v1.autoScroll && v1.autoEndOfDay)
        // v3 (the previous release) did not know letter-suffixed order numbers (GF-398F)
        val v2 = ConfigCodec.decode("""{"v":3,"gfPattern":"(?<![A-Za-z0-9])GF\\s*-\\s*(\\d{2,5})(?!\\d)","readyAny":["Waiting"],"pressTriggers":["Ready"],"readyRepeatMinutes":90,"cancelAny":["Void"],"showToast":true}""")
        assertEquals(Config.DEFAULT.gfPattern, v2.gfPattern)
        assertEquals("Waiting", v2.readyAny.first())
        assertTrue("Finding a driver" in v2.readyAny)
        assertEquals(listOf("GF-398F"), v2.gfExtractor().extract("GF-398F"))
        assertEquals("Void", v2.cancelAny.first())
        assertTrue("Cancelled" in v2.cancelAny)
        assertFalse(v2.showToast)
        // a pattern the user changed is kept
        val custom = ConfigCodec.decode("""{"v":3,"gfPattern":"ORD-(\\d+)"}""")
        assertEquals("ORD-(\\d+)", custom.gfPattern)
        // current saves are left exactly as they are
        val v3 = Config(showToast = true, readyAny = listOf("x"))
        assertEquals(v3, ConfigCodec.decode(ConfigCodec.encode(v3)))
    }

    @Test
    fun dedupeWindows() {
        val d = Deduper()
        val ready = Item("GF-613", ObsType.READY)
        val t0 = 1_000_000L
        assertEquals(listOf(ready), d.fresh(listOf(ready), t0, cfg))
        d.mark(listOfNotNull(Deduper.keyOf(ready)), t0)
        assertTrue(d.fresh(listOf(ready), t0 + 5 * 60_000L, cfg).isEmpty())
        assertEquals(listOf(ready), d.fresh(listOf(ready), t0 + 11 * 60_000L, cfg))
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
        // A text-only delayed observation must not block a later valid screenshot.
        val delayed = Item("GF-900", ObsType.DELAY, delayMin = 3, doneAt = "14:20")
        val textOnlySeed = Deduper().apply { seed(listOf(rec(t0, RecordKind.SEEN, delayed, uri = null))) }
        assertEquals(listOf(delayed), textOnlySeed.fresh(listOf(delayed), t0 + 1000, cfg))
    }

    @Test
    fun resolveFinishTimeAcrossMidnight() {
        val ref = LocalDateTime.of(2026, 9, 24, 0, 30)
        assertEquals(LocalDateTime.of(2026, 9, 23, 23, 50), TimeResolve.latestAtOrBefore(LocalTime.of(23, 50), ref))
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 10), TimeResolve.latestAtOrBefore(LocalTime.of(0, 10), ref))
        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 30), TimeResolve.latestAtOrBefore(LocalTime.of(0, 30), ref))
    }

    @Test
    fun dailyReportSortsDelaysByEvidence() {
        val day = LocalDate.of(2026, 9, 28)
        val readyShot = rec(ms(2026, 9, 28, 11, 1), RecordKind.READY,
            Item("GF-613", ObsType.READY, status = "Finding a driver..."), Item("GF-396", ObsType.READY, status = "Finding a driver..."))
        // a later shot of the same stay does not replace the first one
        val laterShot = rec(ms(2026, 9, 28, 11, 9), RecordKind.READY, Item("GF-613", ObsType.READY, status = "Your driver has arrived"))
        val manualInReadyTab = rec(ms(2026, 9, 28, 11, 30), RecordKind.MANUAL, Item("GF-777", ObsType.READY, status = "คนขับจะมารับใน 3 นาที"))
        // first seen in the Ready tab when the rider was already there: the shop really was late
        val lateShot = rec(ms(2026, 9, 28, 12, 20), RecordKind.READY, Item("GF-533", ObsType.READY, status = "โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง"))
        val history = rec(ms(2026, 9, 28, 19, 14), RecordKind.DELAY,
            Item("GF-613", ObsType.DELAY, delayMin = 4, doneAt = "11:20"),
            Item("GF-156", ObsType.DELAY, delayMin = 6, doneAt = "12:40"),
            Item("GF-777", ObsType.DELAY, delayMin = null, doneAt = "12:00"),
            Item("GF-888", ObsType.DELAY, delayMin = 2, doneAt = "12:10"),
            Item("GF-533", ObsType.DELAY, delayMin = 7, doneAt = "12:35")).copy(historyDate = "2026-09-28")
        val records = listOf(
            // yesterday: same number, must not count as evidence for today's order
            rec(ms(2026, 9, 27, 12, 30), RecordKind.READY, Item("GF-156", ObsType.READY, status = "Finding a driver...")),
            // a log line from an older version (no image)
            rec(ms(2026, 9, 28, 10, 58), RecordKind.PRESS, Item("GF-613", ObsType.PRESS, countdown = "5:53"), uri = null),
            readyShot,
            laterShot,
            manualInReadyTab,
            lateShot,
            // a hand capture outside the Ready tab proves nothing
            rec(ms(2026, 9, 28, 11, 40), RecordKind.MANUAL, Item("GF-888", ObsType.VISIBLE, status = "Ready in: 2:00 min")),
            history,
            rec(ms(2026, 9, 28, 19, 14), RecordKind.SEEN,
                Item("GF-613", ObsType.DONE, doneAt = "11:20", delayMin = 4),
                Item("GF-156", ObsType.DONE, doneAt = "12:40", delayMin = 6),
                Item("GF-777", ObsType.DONE, doneAt = "12:00"),
                Item("GF-888", ObsType.DONE, doneAt = "12:10", delayMin = 2),
                Item("GF-533", ObsType.DONE, doneAt = "12:35", delayMin = 7),
                Item("GF-396", ObsType.DONE, doneAt = "11:25"),
                Item("GF-722", ObsType.DONE, doneAt = "12:18"), uri = null).copy(historyDate = "2026-09-28"),
            Record("st", ms(2026, 9, 28, 19, 13), RecordKind.STATS, historyDate = "2026-09-28", completed = 8, cancelled = 0),
            // read again the next morning: must not double count
            rec(ms(2026, 9, 29, 8, 0), RecordKind.SEEN, Item("GF-722", ObsType.DONE, doneAt = "12:18"), uri = null).copy(historyDate = "2026-09-28"),
        )
        val r = ReportBuilder.build(records, day, zone, cfg, sweepReachedEnd = true)
        assertEquals(8, r.grabCompleted)
        assertEquals(0, r.grabCancelled)
        assertEquals(7, r.completedSeen)
        assertEquals(false, r.historyMatchesGrab)
        assertFalse(r.complete)
        assertEquals(5, r.delayed)
        assertEquals(listOf("GF-613", "GF-777"), r.inTime.map { it.gf })
        assertEquals(listOf("GF-533"), r.late.map { it.gf })
        assertEquals(listOf("GF-888", "GF-156"), r.noEvidence.map { it.gf })
        val c613 = r.cases.first { it.gf == "GF-613" }
        assertEquals(4, c613.delayMin)
        assertEquals(readyShot.id, c613.readyShot?.record?.id)
        assertEquals(2, c613.evidence.size)
        assertEquals(history.id, c613.delayShot?.id)
        // Ready shots for 4 of the 7 finished orders
        assertEquals(listOf("GF-156@12:40", "GF-888@12:10", "GF-722@12:18"), r.missingReadyInstances)
        assertEquals(4, r.readyOrders)
        assertEquals(3, r.actualDelayed)
        assertEquals(62.5, r.grabPct)
        assertEquals(37.5, r.realPct)

        // one set per order with a Ready shot, named like the shop's Drive folder
        val sets = r.sets(Verdict.IN_TIME)
        assertEquals(listOf("GF-613_READY.jpg", "GF-777_READY.jpg"), sets.map { it.readyName })
        assertEquals(listOf("GF-613_DELAY.jpg", "GF-777_DELAY.jpg"), sets.map { it.delayName })
        assertEquals(listOf(readyShot.id, manualInReadyTab.id), sets.map { it.ready.id })
        assertEquals(listOf(history.id, history.id), sets.map { it.delay?.id })
        assertEquals(listOf("GF-533_READY.jpg"), r.sets(Verdict.LATE).map { it.readyName })
        assertTrue(r.sets(Verdict.NO_EVIDENCE).isEmpty())

        val text = ReportText.summary(r, zone)
        assertTrue(text.contains("วันที่ 28/09/2026"))
        assertTrue(text.contains("ยอดจาก Grab (หัวหน้าประวัติ): เสร็จสมบูรณ์ 8 · ยกเลิก 0 — ⚠ อ่านรายการได้ไม่ครบ"))
        assertTrue(text.contains("Grab ระบุล่าช้า 5 ออเดอร์ = 62.50%"))
        assertTrue(text.contains("= (5 − 2) / 8 = 37.50%"))
        assertTrue(text.contains("GF-613 ล่าช้า 4 นาที (เสร็จ 11:20) — อยู่ในแท็บ Ready ตั้งแต่ 11:01 \"Finding a driver...\""))
        assertTrue(text.contains("❓ ไม่มีหลักฐาน / ระบบจับไม่ได้ (ไม่ได้แปลว่าช้าจริง)\nGF-888 ล่าช้า 2 นาที (เสร็จ 12:10)\nGF-156 ล่าช้า 6 นาที (เสร็จ 12:40)\n"))

        val csv = ReportText.csv(r, zone)
        assertTrue(csv.contains("date,gf,"))
        assertEquals(6, csv.trim().lines().size)
        assertTrue(csv.contains("2026-09-28,GF-613,4,11:20,yes,11:01,Finding a driver...,10:58,GF-613_READY.jpg,GF-613_DELAY.jpg,UNKNOWN,READY_EVIDENCE,IN_TIME"))
        assertTrue(csv.contains("2026-09-28,GF-533,7,12:35,yes,12:20,โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง,,GF-533_READY.jpg,GF-533_DELAY.jpg,UNKNOWN,READY_EVIDENCE,LATE"))
        assertTrue(csv.contains("2026-09-28,GF-156,6,12:40,no,,,,,,UNKNOWN,MISSING_READY,NO_EVIDENCE"))
    }

    @Test
    fun historyReadAfterMidnightStillBelongsToItsDay() {
        // the end-of-day sweep ran at 00:20, History showed "Mon, 28 Sep 2026"
        val records = listOf(
            rec(ms(2026, 9, 28, 23, 40), RecordKind.READY, Item("GF-100", ObsType.READY, status = "Finding a driver...")),
            rec(ms(2026, 9, 29, 0, 20), RecordKind.DELAY, Item("GF-100", ObsType.DELAY, delayMin = 3, doneAt = "23:55"))
                .copy(historyDate = "2026-09-28"),
        )
        val r = ReportBuilder.build(records, LocalDate.of(2026, 9, 28), zone, cfg)
        assertEquals(listOf("GF-100"), r.inTime.map { it.gf })
        assertEquals(LocalDateTime.of(2026, 9, 28, 23, 55), r.cases.single().doneAt)
    }

    @Test
    fun repeatedOrderNumberGetsTheFinishTimeInItsFileNames() {
        val day = LocalDate.of(2026, 9, 28)
        val records = listOf(
            rec(ms(2026, 9, 28, 11, 30), RecordKind.READY, Item("GF-613", ObsType.READY)),
            rec(ms(2026, 9, 28, 17, 50), RecordKind.READY, Item("GF-613", ObsType.READY)),
            rec(ms(2026, 9, 28, 21, 0), RecordKind.DELAY,
                Item("GF-613", ObsType.DELAY, delayMin = 4, doneAt = "11:47"),
                Item("GF-613", ObsType.DELAY, delayMin = 7, doneAt = "18:05")),
        )
        val sets = ReportBuilder.build(records, day, zone, cfg).sets(Verdict.IN_TIME)
        assertEquals(listOf("GF-613_1147_READY.jpg", "GF-613_1805_READY.jpg"), sets.map { it.readyName })
        assertEquals(listOf("GF-613_1147_DELAY.jpg", "GF-613_1805_DELAY.jpg"), sets.map { it.delayName })
        // each set uses the READY shot taken before its own finish time
        assertEquals(listOf(ms(2026, 9, 28, 11, 30), ms(2026, 9, 28, 17, 50)), sets.map { it.ready.t })
    }

    @Test
    fun reportForEmptyDay() {
        val r = ReportBuilder.build(emptyList(), LocalDate.of(2026, 9, 28), zone, cfg)
        assertEquals(0, r.historyOrders)
        assertNull(r.pct(0))
        assertNull(r.grabCompleted)
        assertNull(r.realPct)
        assertTrue(ReportText.summary(r, zone).contains("History ที่แอปสแกนเห็น: 0 ออเดอร์"))
        assertTrue(ReportText.summary(r, zone).contains("ยังอ่านไม่ได้"))
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
        assertEquals("GF-613_READY.jpg", Naming.setName("GF-613", "READY"))
        assertEquals("GF-613_1147_DELAY.jpg", Naming.setName("GF-613_1147", "DELAY"))
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

    @Test
    fun openingHoursRunFromOpenTimeToTheEndOfDay() {
        val c = Config(openTime = "09:00", closeTime = "19:00", endBufferMinutes = 5)
        assertTrue(c.inShopHours(java.time.LocalTime.of(9, 0)))
        assertTrue(c.inShopHours(java.time.LocalTime.of(19, 4)))
        assertFalse(c.inShopHours(java.time.LocalTime.of(19, 5)))
        assertFalse(c.inShopHours(java.time.LocalTime.of(8, 59)))
        assertFalse(c.inShopHours(java.time.LocalTime.of(23, 30)))
        // a broken time never blocks anything
        assertTrue(Config(openTime = "x").inShopHours(java.time.LocalTime.of(3, 0)))
    }

    @Test
    fun version4SettingsGainOpeningHoursAndNightSwitchOff() {
        val old = ConfigCodec.encode(Config(closeTime = "20:00")).replace("\"v\":5", "\"v\":4")
            .replace(Regex(",\"openTime\":\"[^\"]*\",\"autoOffAfterClose\":(true|false),\"readyBatchSeconds\":\\d+"), "")
        assertFalse(old.contains("openTime"))
        val c = ConfigCodec.decode(old)
        assertEquals("20:00", c.closeTime)
        assertEquals("09:00", c.openTime)
        assertTrue(c.autoOffAfterClose)
        assertEquals(12, c.readyBatchSeconds)
        val again = ConfigCodec.decode(ConfigCodec.encode(c.copy(openTime = "10:30", autoOffAfterClose = false, readyBatchSeconds = 0)))
        assertEquals("10:30", again.openTime)
        assertFalse(again.autoOffAfterClose)
        assertEquals(0, again.readyBatchSeconds)
    }
}
