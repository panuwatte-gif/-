package io.github.panuwattegif.readyproof.core

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchAndParseTest {
    private val cfg = Config.DEFAULT

    @Test
    fun buttonTapMatches() {
        assertTrue(ClickMatcher.matches(ClickInfo(ownText = "พร้อมจัดส่ง", className = "android.widget.Button"), cfg))
        // clickable container whose only child is the label
        assertTrue(ClickMatcher.matches(ClickInfo(eventTexts = listOf(" พร้อมจัดส่ง "), className = "android.widget.FrameLayout"), cfg))
    }

    @Test
    fun tabAndCardTapsDoNotMatch() {
        val tab = ClickInfo(eventTexts = listOf("พร้อมจัดส่ง"), className = "androidx.appcompat.app.ActionBar.Tab")
        assertFalse(ClickMatcher.matches(tab, cfg))
        val materialTab = ClickInfo(ownText = "พร้อมจัดส่ง", className = "com.google.android.material.tabs.TabLayout\$TabView")
        assertFalse(ClickMatcher.matches(materialTab, cfg))
        val composeTab = ClickInfo(ownText = "พร้อมจัดส่ง", className = "android.view.View", roleDesc = "Tab")
        assertFalse(ClickMatcher.matches(composeTab, cfg))
        val tabWithCount = ClickInfo(ownText = "พร้อมจัดส่ง 3", className = "android.widget.TextView")
        assertFalse(ClickMatcher.matches(tabWithCount, cfg))
        val card = ClickInfo(
            eventTexts = listOf("GF-147", "พร้อมจัดส่งใน: 5:53 นาที", "2 รายการ", "พร้อมจัดส่ง"),
            className = "android.widget.LinearLayout",
        )
        assertFalse(ClickMatcher.matches(card, cfg))
    }

    @Test
    fun triggerSyntax() {
        val c = listOf("mark as ready")
        assertTrue(ClickMatcher.matchTrigger("Mark as ready", c, ""))
        assertTrue(ClickMatcher.matchTrigger("mark*", c, ""))
        assertTrue(ClickMatcher.matchTrigger("*ready", c, ""))
        assertTrue(ClickMatcher.matchTrigger("*as*", c, ""))
        assertFalse(ClickMatcher.matchTrigger("ready", c, ""))
        assertFalse(ClickMatcher.matchTrigger("*", c, ""))
        assertTrue(ClickMatcher.matchTrigger("id:btn_ready", emptyList(), "com.grab.merchant:id/btn_ready_order"))
        assertFalse(ClickMatcher.matchTrigger("id:", emptyList(), "anything"))
    }

    @Test
    fun classTokens() {
        assertTrue(ClickMatcher.classHasToken("androidx.appcompat.app.ActionBar.Tab", "tab"))
        assertTrue(ClickMatcher.classHasToken("android.widget.TabWidget", "tab"))
        assertFalse(ClickMatcher.classHasToken("com.example.TableButton", "tab"))
        assertFalse(ClickMatcher.classHasToken("com.google.android.material.tabs", "tab"))
        assertFalse(ClickMatcher.classHasToken(null, "tab"))
    }

    @Test
    fun clickLabel() {
        assertEquals("พร้อมจัดส่ง", ClickInfo(ownText = "พร้อมจัดส่ง").label())
        assertEquals("GF-1 | ข้าว", ClickInfo(eventTexts = listOf("GF-1", "ข้าว")).label())
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
        assertEquals("5:53", Parsers.countdown("พร้อมจัดส่งใน: 5:53 นาที", cfg.countdownKeywords))
        assertEquals("0:00", Parsers.countdown("พร้อมจัดส่งใน 0:00 นาที", cfg.countdownKeywords))
        assertNull(Parsers.countdown("พร้อมจัดส่ง", cfg.countdownKeywords))
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
