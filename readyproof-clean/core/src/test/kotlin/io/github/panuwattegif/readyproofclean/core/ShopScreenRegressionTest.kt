package io.github.panuwattegif.readyproofclean.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shop's OPPO dump: all four labels exist, none announces selection. */
class ShopScreenRegressionTest {
    private fun screen(shift: Int = 0, selectedReady: Boolean = false): UiNode {
        val root = UiNode(className = "FrameLayout", right = 720, bottom = 1604)
        val tabs = UiNode(className = "HorizontalScrollView", top = 193, bottom = 289, right = 720)
        tabs.addAll(UiNode(desc = "กำลังเตรียม"), UiNode(desc = "พร้อมจัดส่ง", selected = selectedReady),
            UiNode(desc = "ที่กำลังจะถึง"), UiNode(desc = "ประวัติ"))
        val list = UiNode(className = "ScrollView", top = 291, bottom = 1494, right = 720, scrollable = false)
        list.addAll(UiNode(desc = "พฤ., 08 ต.ค. 2026", top = 307, bottom = 387, right = 720),
            UiNode(desc = "เสร็จสมบูรณ์ 77 ยกเลิก 0", top = 419, bottom = 810, right = 720),
            UiNode(desc = "GF-133 เสร็จสมบูรณ์เมื่อ 7:32 PM โฆษณา", top = 942 + shift, bottom = 1194 + shift, right = 720),
            UiNode(desc = "GF-700 เสร็จสมบูรณ์เมื่อ 7:09 PM ล่าช้าไป 3 นาที ลูกค้าใหม่ โฆษณา",
                top = 1194 + shift, bottom = 1494 + shift, right = 720))
        return root.addAll(tabs, list)
    }
    @Test fun historyWorksWithoutSelectedFlagOrScrollableFlag() {
        val root = screen()
        assertTrue(HistoryScreen.isOpen(listOf(root), Config.DEFAULT))
        val a = ScreenAnalyzer.analyze(listOf(root), Config.DEFAULT, true)
        assertEquals(listOf("GF-133", "GF-700"), a.cards.map { it.gf })
        assertEquals(listOf("GF-700"), a.items.filter { it.type == ObsType.DELAY }.map { it.gf })
        assertEquals("19:09", a.items.first { it.type == ObsType.DELAY }.doneAt)
        val totals = HistoryTotalsParser.parseRoots(listOf(root), Config.DEFAULT)
        assertEquals(77, totals?.total)
        assertEquals(0, totals?.cancelled)
        assertEquals(LocalDate.of(2026, 10, 8), HistoryDates.parse("พฤ., 08 ต.ค. 2026", LocalDate.of(2026, 10, 9)))
        assertFalse(SweepPolicy.complete(totals?.total, 2, 2, true, false))
    }
    @Test fun motionComesFromActualCardPositions() {
        val a = ScreenAnalyzer.analyze(listOf(screen()), Config.DEFAULT, true)
        val b = ScreenAnalyzer.analyze(listOf(screen(-200)), Config.DEFAULT, true)
        assertEquals(291, ListMotion.viewport(a)?.top)
        assertEquals(1494, ListMotion.viewport(a)?.bottom)
        assertFalse(ListMotion.changed(ListMotion.key(a), ListMotion.key(a)))
        assertTrue(ListMotion.changed(ListMotion.key(a), ListMotion.key(b)))
        assertNotEquals(ListMotion.key(a), ListMotion.key(b))
        assertFalse(ListMotion.changed(ListMotion.key(a), ""))
    }
    @Test fun selectedReadyCannotCreateDelayFromStaleTerminalText() {
        val root = screen(selectedReady = true)
        assertFalse(HistoryScreen.isOpen(listOf(root), Config.DEFAULT))
        assertTrue(ScreenAnalyzer.analyze(listOf(root), Config.DEFAULT, true).items.none { it.type == ObsType.DELAY })
    }
    @Test fun clippedRowsAreNotPhotoCoverage() {
        val a = ScreenAnalyzer.analyze(listOf(screen(100)), Config.DEFAULT, true)
        assertFalse(ListMotion.proofVisible(a.cards.first { it.gf == "GF-700" }, a))
    }
}
