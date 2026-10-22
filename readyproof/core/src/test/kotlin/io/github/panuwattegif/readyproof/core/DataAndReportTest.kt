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
        val c = Config(closeTime = "20:30", recheckMinutes = 7, retentionDays = 45, diagnostics = true)
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
        // in the Ready tab before it finished, but its countdown had run out first (red 0:00)
        val overdue = rec(ms(2026, 9, 28, 12, 15), RecordKind.PREPARING, Item("GF-533", ObsType.PREPARING, countdown = "0:00"))
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
            // the Preparing tab: GF-613 due 11:03, GF-777 due 11:31
            rec(ms(2026, 9, 28, 10, 55), RecordKind.SEEN, Item("GF-613", ObsType.PREPARING, countdown = "8:00"), uri = null),
            rec(ms(2026, 9, 28, 11, 25), RecordKind.SEEN, Item("GF-777", ObsType.PREPARING, countdown = "6:00"), uri = null),
            readyShot,
            laterShot,
            manualInReadyTab,
            overdue,
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
        // GF-533 reached the Ready tab only after its countdown ran out: late, Ready photo or not
        assertEquals(listOf("GF-613", "GF-777"), r.inTime.map { it.gf })
        assertEquals(listOf("GF-533"), r.late.map { it.gf })
        assertEquals(overdue.id, r.late.single().prep?.overdueShot?.id)
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

        // one set per order pressed in time, named like the shop's Drive folder
        val sets = r.sets(Verdict.IN_TIME)
        assertEquals(listOf("GF-613_READY.jpg", "GF-777_READY.jpg"), sets.map { it.readyName })
        assertEquals(listOf("GF-613_DELAY.jpg", "GF-777_DELAY.jpg"), sets.map { it.delayName })
        assertEquals(listOf(readyShot.id, manualInReadyTab.id), sets.map { it.ready.id })
        assertEquals(listOf(history.id, history.id), sets.map { it.delay?.id })
        assertTrue(r.sets(Verdict.NO_EVIDENCE).isEmpty())

        val text = ReportText.summary(r, zone)
        assertTrue(text.contains("วันที่ 28/09/2026"))
        assertTrue(text.contains("ยอดจาก Grab (หัวหน้าประวัติ): เสร็จสมบูรณ์ 8 · ยกเลิก 0 — ⚠ อ่านรายการได้ไม่ครบ"))
        assertTrue(text.contains("Grab ระบุล่าช้า 5 ออเดอร์ = 62.50%"))
        assertTrue(text.contains("= (5 − 2) / 8 = 37.50%"))
        assertFalse(text.contains("ช้าจริง:"))
        assertTrue(text.contains("GF-613 ล่าช้า 4 นาที (เสร็จ 11:20) — อยู่ในแท็บ Ready ตั้งแต่ 11:01:00 \"Finding a driver...\" · ต้องกดภายใน 11:03:00 (อ่านได้ 8:00 ตอน 10:55:00) เหลือเวลา 2:00"))
        assertTrue(text.contains("❌ กดช้า (หมดเวลาก่อนกด) — ห้ามส่ง Grab\nGF-533 ล่าช้า 7 นาที (เสร็จ 12:35) — อยู่ในแท็บ Ready ตั้งแต่ 12:20:00 \"โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง\" · หน้ากำลังเตรียมขึ้น 0:00 (หมดเวลา) ตอน 12:15:00 มีภาพ\n"))
        assertTrue(text.contains("❓ ไม่มีหลักฐาน / ระบบจับไม่ได้ (ไม่ได้แปลว่าช้าจริง)\nGF-888 ล่าช้า 2 นาที (เสร็จ 12:10)\nGF-156 ล่าช้า 6 นาที (เสร็จ 12:40)\n"))

        val csv = ReportText.csv(r, zone)
        assertTrue(csv.contains("date,gf,"))
        assertEquals(6, csv.trim().lines().size)
        assertTrue(csv.contains("2026-09-28,GF-613,4,11:20,yes,11:01,Finding a driver...,10:58,GF-613_READY.jpg,GF-613_DELAY.jpg,UNKNOWN,READY_EVIDENCE,IN_TIME"))
        assertTrue(csv.contains("2026-09-28,GF-533,7,12:35,yes,12:20,โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง,,GF-533_READY.jpg,GF-533_DELAY.jpg,UNKNOWN,READY_EVIDENCE,LATE,12:20:00,12:15:00,0:00,12:15:00,300"))
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
        assertEquals(listOf("GF-100"), r.withEvidence.map { it.gf })
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
        val sets = ReportBuilder.build(records, day, zone, cfg).sets()
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
        val old = ConfigCodec.encode(Config(closeTime = "20:00")).replace("\"v\":${ConfigCodec.VERSION}", "\"v\":4")
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

    @Test
    fun aDeliveredOrdersDetailPageIsNeverReadyEvidence() {
        // A detail page photographed on 9 Oct: delivered late, and it mentions the driver.
        val page = Trees.n(
            cls = "android.view.View",
            kids = listOf(
                Trees.n("GF-700"),
                Trees.n("จัดส่งแล้ว 8 ต.ค., พ., 19:09 คำสั่งซื้อล่าช้า คำสั่งซื้อยังเตรียมไม่เสร็จตอนที่คนขับไปถึงร้าน ทำให้การจัดส่งล่าช้า"),
            ),
        )
        val a = ScreenAnalyzer.analyze(listOf(page), Config.DEFAULT)
        assertTrue(a.readyGfs().isEmpty())
        // saved settings gain the new word
        val old = ConfigCodec.encode(Config(readyNone = listOf("Ready in"))).replace("\"v\":${ConfigCodec.VERSION}", "\"v\":5")
        assertTrue("จัดส่งแล้ว" in ConfigCodec.decode(old).readyNone)
    }

    @Test
    fun anOrderOnAReadyPhotoTakenForAnotherOrderIsEvidenceToo() {
        // 10 Oct, shop phone: GF-102 sat at the top of the list (seen 13:16), the 13:18 photo was taken for GF-158
        val day = LocalDate.of(2026, 10, 10)
        val records = listOf(
            rec(ms(2026, 10, 10, 13, 16, 37), RecordKind.SEEN, Item("GF-102", ObsType.READY, status = "GF-102 คนขับจะมารับใน 6 นาที 2 รายการ"), uri = null),
            rec(ms(2026, 10, 10, 13, 18, 46), RecordKind.READY, Item("GF-158", ObsType.READY, status = "GF-158 กำลังค้นหาคนขับ... 2 รายการ"),
                visible = listOf("GF-102", "GF-158")),
            // on the same photo but never seen in the Ready tab (a number in a banner ...): proves nothing
            rec(ms(2026, 10, 10, 13, 20), RecordKind.READY, Item("GF-457", ObsType.READY, status = "GF-457 คนขับจะมารับใน 7 นาที 1 รายการ"),
                visible = listOf("GF-999", "GF-457")),
            rec(ms(2026, 10, 10, 16, 39), RecordKind.DELAY,
                Item("GF-102", ObsType.DELAY, delayMin = 4, doneAt = "13:58"),
                Item("GF-999", ObsType.DELAY, delayMin = 5, doneAt = "13:50")).copy(historyDate = "2026-10-10"),
        )
        val r = ReportBuilder.build(records, day, zone, cfg)
        val c102 = r.cases.first { it.gf == "GF-102" }
        assertTrue(c102.hasEvidence)
        // its countdown was never read (no Preparing visits on 10 Oct): not claimed automatically
        assertEquals(Verdict.UNCHECKED, c102.verdict)
        assertEquals(ms(2026, 10, 10, 13, 16, 37), c102.readyAt)
        assertEquals(ms(2026, 10, 10, 13, 18, 46), c102.readyShot?.t)
        assertEquals("GF-102 คนขับจะมารับใน 6 นาที 2 รายการ", c102.readyShot?.status)
        assertEquals(Verdict.NO_EVIDENCE, r.cases.first { it.gf == "GF-999" }.verdict)
        assertTrue("GF-102@13:58" !in r.missingReadyInstances)
    }

    @Test
    fun anOrderPressedAfterItsCountdownRanOutIsLateEvenWithAReadyPhoto() {
        // 19 Oct GF-022: red 0:00 on the Preparing tab (15:47), photographed in Ready at 15:49:33,
        // finished 16:27, Grab: 6 minutes late. 1.0.164 said "pressed in time".
        val day = LocalDate.of(2026, 10, 19)
        val overdue = rec(ms(2026, 10, 19, 15, 47, 5), RecordKind.PREPARING, Item("GF-022", ObsType.PREPARING, countdown = "0:00"))
        val records = listOf(
            rec(ms(2026, 10, 19, 15, 38, 10), RecordKind.SEEN, Item("GF-022", ObsType.PREPARING, countdown = "8:50"), uri = null),
            overdue,
            rec(ms(2026, 10, 19, 15, 49, 33), RecordKind.READY, Item("GF-022", ObsType.READY, status = "GF-022 คนขับของคุณมาถึงแล้ว 1 รายการ")),
            // pressed with time to spare
            rec(ms(2026, 10, 19, 11, 5), RecordKind.SEEN, Item("GF-204", ObsType.PREPARING, countdown = "6:30"), uri = null),
            rec(ms(2026, 10, 19, 11, 9, 40), RecordKind.READY, Item("GF-204", ObsType.READY, status = "GF-204 กำลังค้นหาคนขับ... 2 รายการ")),
            // never seen at 0:00, but reached Ready 45 s after its time ran out
            rec(ms(2026, 10, 19, 12, 0), RecordKind.SEEN, Item("GF-328", ObsType.PREPARING, countdown = "1:40"), uri = null),
            rec(ms(2026, 10, 19, 12, 2, 25), RecordKind.READY, Item("GF-328", ObsType.READY, status = "GF-328 กำลังค้นหาคนขับ... 1 รายการ")),
            // 5 s after: closer than the app can tell apart
            rec(ms(2026, 10, 19, 13, 0), RecordKind.SEEN, Item("GF-674", ObsType.PREPARING, countdown = "2:00"), uri = null),
            rec(ms(2026, 10, 19, 13, 2, 5), RecordKind.READY, Item("GF-674", ObsType.READY)),
            // given more time after it ran out: the last reading before Ready counts
            rec(ms(2026, 10, 19, 14, 0), RecordKind.PREPARING, Item("GF-234", ObsType.PREPARING, countdown = "0:00")),
            rec(ms(2026, 10, 19, 14, 1), RecordKind.SEEN, Item("GF-234", ObsType.PREPARING, countdown = "5:00"), uri = null),
            rec(ms(2026, 10, 19, 14, 4), RecordKind.READY, Item("GF-234", ObsType.READY)),
            // its countdown was never read
            rec(ms(2026, 10, 19, 10, 40), RecordKind.READY, Item("GF-191", ObsType.READY)),
            rec(ms(2026, 10, 19, 19, 5, 20), RecordKind.DELAY,
                Item("GF-022", ObsType.DELAY, delayMin = 6, doneAt = "16:27"),
                Item("GF-204", ObsType.DELAY, delayMin = 4, doneAt = "11:42"),
                Item("GF-328", ObsType.DELAY, delayMin = 5, doneAt = "12:22"),
                Item("GF-674", ObsType.DELAY, delayMin = 7, doneAt = "13:17"),
                Item("GF-234", ObsType.DELAY, delayMin = 4, doneAt = "14:12"),
                Item("GF-191", ObsType.DELAY, delayMin = 3, doneAt = "11:51")).copy(historyDate = "2026-10-19"),
        )
        val r = ReportBuilder.build(records, day, zone, cfg)
        assertEquals(listOf("GF-204", "GF-674", "GF-234"), r.inTime.map { it.gf })
        assertEquals(listOf("GF-328", "GF-022"), r.late.map { it.gf })
        assertEquals(listOf("GF-191"), r.unchecked.map { it.gf })
        val c022 = r.cases.first { it.gf == "GF-022" }
        assertTrue(c022.prep!!.ranOut)
        assertEquals(overdue.id, c022.prep!!.overdueShot?.id)
        // only orders pressed in time go to Grab
        assertEquals(listOf("GF-204", "GF-674", "GF-234"), r.sets(Verdict.IN_TIME).map { it.case.gf })
        assertEquals(50.0, r.realPct) // (6 − 3) / 6
        val text = ReportText.summary(r, zone)
        assertTrue(text.contains("GF-022 ล่าช้า 6 นาที (เสร็จ 16:27) — อยู่ในแท็บ Ready ตั้งแต่ 15:49:33 \"GF-022 คนขับของคุณมาถึงแล้ว 1 รายการ\" · หน้ากำลังเตรียมขึ้น 0:00 (หมดเวลา) ตอน 15:47:05 มีภาพ"), text)
        assertTrue(text.contains("GF-328 ล่าช้า 5 นาที (เสร็จ 12:22) — อยู่ในแท็บ Ready ตั้งแต่ 12:02:25 \"GF-328 กำลังค้นหาคนขับ... 1 รายการ\" · ต้องกดภายใน 12:01:40 (อ่านได้ 1:40 ตอน 12:00:00) ช้าไป 0:45"), text)
        assertTrue(text.contains("GF-204 ล่าช้า 4 นาที (เสร็จ 11:42) — อยู่ในแท็บ Ready ตั้งแต่ 11:09:40 \"GF-204 กำลังค้นหาคนขับ... 2 รายการ\" · ต้องกดภายใน 11:11:30 (อ่านได้ 6:30 ตอน 11:05:00) เหลือเวลา 1:50"), text)
        assertTrue(text.contains("· ต้องกดภายใน 13:02:00 (อ่านได้ 2:00 ตอน 13:00:00) ทันพอดี (ห่าง 0:05)"), text)
        assertTrue(text.contains("GF-191 ล่าช้า 3 นาที (เสร็จ 11:51) — อยู่ในแท็บ Ready ตั้งแต่ 10:40:00 · ไม่มีข้อมูลหน้ากำลังเตรียม"), text)
    }

    @Test
    fun theCountdownIsReadAsSecondsLeft() {
        assertEquals(353, Item("GF-1", ObsType.PREPARING, countdown = "5:53").dueSeconds)
        assertEquals(0, Item("GF-1", ObsType.PREPARING, countdown = "0:00").dueSeconds)
        assertEquals(0, Item("GF-1", ObsType.PREPARING, countdown = "-1:20").dueSeconds)
        assertEquals(1101, Item("GF-1", ObsType.PREPARING, countdown = "18:21").dueSeconds)
        assertNull(Item("GF-1", ObsType.PREPARING).dueSeconds)
        // kept in the log and read back
        val r = Record("p", 1L, RecordKind.PREPARING, listOf(Item("GF-961", ObsType.PREPARING, status = "พร้อมจัดส่งใน: 0:00 นาที", countdown = "0:00")), uri = "content://x")
        assertEquals(r, RecordCodec.decode(RecordCodec.encode(r)))
    }

    @Test
    fun evidenceForGrabIsSentInBatchesThatFitWhatGrabAccepted() {
        // 20 combined images of 200 KB: 15 in the first send (3.0 MB), 5 in the second
        val b = GrabLimits.batches(List(20) { 200_000L })
        assertEquals(listOf(15, 5), b.map { it.size })
        assertEquals((0 until 20).toList(), b.flatten())
        // bigger images: the 3 MB limit splits earlier than the 15-file one
        assertEquals(listOf(12, 8), GrabLimits.batches(List(20) { 250_000L }).map { it.size })
        assertTrue(GrabLimits.batches(emptyList()).isEmpty())
    }
}
