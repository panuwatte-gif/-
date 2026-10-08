package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchAndParseTest {
    private val cfg = Config.DEFAULT
    private val COUNTDOWN = listOf("Ready in", "พร้อมจัดส่งใน")

    @Test
    fun englishHistoryWording() {
        assertEquals(4, Parsers.delayMinutes("Delayed by 4 mins", cfg.delayAny))
        assertEquals(1, Parsers.delayMinutes("Delayed by 1 min", cfg.delayAny))
        assertEquals(65, Parsers.delayMinutes("Delayed by 1 hr 5 mins", cfg.delayAny))
        assertEquals("11:47", Parsers.doneAt("Completed at 11:47 AM", cfg.doneAny))
        assertEquals("13:05", Parsers.doneAt("Completed at 1:05 PM", cfg.doneAny))
        assertNull(Parsers.doneAt("Cancelled at 1:34 AM", cfg.doneAny))
        assertEquals("9:32", Parsers.countdown("Ready in: 9:32 min", COUNTDOWN))
    }

    @Test
    fun clickLabel() {
        assertEquals("พร้อมจัดส่ง", ClickInfo(ownText = "พร้อมจัดส่ง").label())
        assertEquals("GF-1 | ข้าว", ClickInfo(eventTexts = listOf("GF-1", "ข้าว")).label())
        assertEquals(listOf("Ready"), ClickInfo(eventTexts = listOf(" Ready ")).candidates())
        assertTrue(ClickInfo(eventTexts = listOf("GF-1", "Ready")).candidates().isEmpty())
    }

    @Test
    fun historyDates() {
        assertEquals(LocalDate.of(2026, 10, 8), HistoryReader.parseDate("Today, 08 Oct 2026"))
        assertEquals(LocalDate.of(2026, 10, 3), HistoryReader.parseDate("Sat, 03 Oct 2026"))
        assertEquals(LocalDate.of(2026, 10, 3), HistoryReader.parseDate("ส. 3 ต.ค. 2569"))
        assertEquals(LocalDate.of(2026, 10, 8), HistoryReader.parseDate("วันนี้, 8 ตุลาคม 2569"))
        assertEquals(LocalDate.of(2026, 9, 28), HistoryReader.parseDate("Sep 28, 2026"))
        assertEquals(LocalDate.of(2026, 9, 28), HistoryReader.parseDate("28 September 2026"))
        assertNull(HistoryReader.parseDate("Closed until Fri, 9:00 AM"))
        assertNull(HistoryReader.parseDate("฿16,035.00"))
        assertNull(HistoryReader.parseDate("31 Feb 2026"))
    }

    @Test
    fun orderNumbersWithLetterAndStrip() {
        val g = cfg.gfExtractor()
        assertEquals(listOf("GF-398F"), g.extract("GF-398F"))
        assertEquals(listOf("GF-398F"), g.extract("gf-398f"))
        assertEquals(", Finding a driver...", g.strip("GF-396, Finding a driver..."))
        assertEquals("", g.strip("GF-396"))
    }

    @Test
    fun clocks() {
        assertEquals(LocalTime.of(12, 56), Parsers.clock("12:56 PM"))
        assertEquals(LocalTime.of(19, 28), Parsers.clock("7:28 PM"))
        assertEquals(LocalTime.of(0, 18), Parsers.clock("12:18 AM"))
        assertEquals(LocalTime.of(9, 5), Parsers.clock("9:05 a.m."))
        assertEquals(LocalTime.of(19, 0), Parsers.clock("7:00 หลังเที่ยง"))
        assertEquals(LocalTime.of(19, 28), Parsers.clock("19.28 น."))
        assertNull(Parsers.clock("223.00"))
        assertNull(Parsers.clock("25:10"))
        assertEquals("12:56", Parsers.doneAt("เสร็จสมบูรณ์เมื่อ 12:56 PM", cfg.doneAny))
        assertEquals("12:42", Parsers.doneAt("GF-161, เสร็จสมบูรณ์เมื่อ 12:42 PM ล่าช้าไป 15 นาที", cfg.doneAny))
        assertNull(Parsers.doneAt("เสร็จสมบูรณ์", cfg.doneAny))
    }

    @Test
    fun delays() {
        assertEquals(4, Parsers.delayMinutes("ล่าช้าไป 4 นาที", cfg.delayAny))
        assertEquals(65, Parsers.delayMinutes("ล่าช้าไป 1 ชม. 5 นาที", cfg.delayAny))
        assertEquals(60, Parsers.delayMinutes("ล่าช้าไป 1 ชั่วโมง", cfg.delayAny))
        assertEquals(15, Parsers.delayMinutes("12:42 PM ล่าช้าไป 15 นาที", cfg.delayAny))
        assertNull(Parsers.delayMinutes("ล่าช้าไป", cfg.delayAny))
        assertNull(Parsers.delayMinutes("ตรงเวลา", cfg.delayAny))
    }

    @Test
    fun countdowns() {
        assertEquals("5:53", Parsers.countdown("พร้อมจัดส่งใน: 5:53 นาที", COUNTDOWN))
        assertEquals("0:00", Parsers.countdown("พร้อมจัดส่งใน 0:00 นาที", COUNTDOWN))
        assertNull(Parsers.countdown("พร้อมจัดส่ง", COUNTDOWN))
    }

    @Test
    fun orderNumbers() {
        val g = cfg.gfExtractor()
        assertEquals(listOf("GF-613"), g.extract("GF-613"))
        assertEquals(listOf("GF-061"), g.extract("gf - 061"))
        assertEquals(listOf("GF-450", "GF-697"), g.extract("GF-450_DELAY-10m GF-697"))
        assertEquals(listOf("GF-367"), g.extract("GF-367 @"))
        assertEquals(emptyList(), g.extract("223.00"))
        assertEquals(emptyList(), g.extract(null))
    }

    @Test
    fun textNormalisation() {
        assertEquals("กำลัง ค้นหา", TextNorm.clean("  กําลัง​   ค้นหา "))
        assertTrue(TextNorm.containsAny("กําลังค้นหาคนขับ...", listOf("กำลังค้นหาคนขับ")))
        assertFalse(TextNorm.containsAny("abc", listOf("", "  ")))
    }
}
