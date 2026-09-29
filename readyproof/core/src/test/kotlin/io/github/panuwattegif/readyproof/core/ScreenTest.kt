package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenTest {
    private val cfg = Config.DEFAULT

    private fun analyze(root: UiNode) = ScreenAnalyzer.analyze(listOf(root), cfg)

    private fun ofType(a: ScreenAnalysis, type: ObsType) = a.items.filter { it.type == type }

    @Test
    fun readyTabFindsEveryOrderWaitingForOrWithDriver() {
        val a = analyze(Trees.readyTab())
        assertEquals(listOf("GF-396", "GF-521", "GF-613"), a.visible)
        val ready = ofType(a, ObsType.READY)
        assertEquals(listOf("GF-396", "GF-521", "GF-613"), ready.map { it.gf })
        assertEquals("กำลังค้นหาคนขับ...", ready[0].status)
        assertEquals("คนขับจะมารับใน 0 นาที", ready[1].status)
        // decomposed "ํา" in the source is normalised to "ำ"
        assertEquals("กำลังค้นหาคนขับ...", ready[2].status)
        assertEquals(listOf("GF-613", "กำลังค้นหาคนขับ...", "8 รายการ"), ready[2].card)
        assertTrue(ofType(a, ObsType.DONE).isEmpty())
    }

    @Test
    fun preparingTabIsNeverReadyEvenWithADriverAssigned() {
        val a = analyze(Trees.preparingTab())
        assertEquals(listOf("GF-961", "GF-147", "GF-697", "GF-450"), a.visible)
        assertTrue(a.items.isEmpty(), "unexpected ${a.items}")
    }

    @Test
    fun historyTabReadsFinishTimesAndDelays() {
        val a = analyze(Trees.historyTab())
        val done = ofType(a, ObsType.DONE)
        assertEquals(listOf("GF-610", "GF-722", "GF-380", "GF-161", "GF-024"), done.map { it.gf })
        assertEquals(listOf("12:56", "12:18", "12:24", "12:42", "12:19"), done.map { it.doneAt })
        val delay = ofType(a, ObsType.DELAY)
        assertEquals(listOf("GF-610" to 4, "GF-380" to 9, "GF-161" to 15), delay.map { it.gf to it.delayMin })
        assertTrue(ofType(a, ObsType.READY).isEmpty())
    }

    @Test
    fun flattenedListStillGroupsTextsByOrder() {
        val a = analyze(Trees.flattenedList())
        assertEquals(listOf("GF-396", "GF-521"), ofType(a, ObsType.READY).map { it.gf })
        assertEquals(listOf("GF-613" to "19:28"), ofType(a, ObsType.DONE).map { it.gf to it.doneAt })
    }

    @Test
    fun mergedCardsWork() {
        val a = analyze(Trees.mergedCards())
        assertEquals(listOf("GF-396"), ofType(a, ObsType.READY).map { it.gf })
        val delay = ofType(a, ObsType.DELAY).single()
        assertEquals("GF-610", delay.gf)
        assertEquals(4, delay.delayMin)
        assertEquals("12:56", delay.doneAt)
    }

    @Test
    fun detailPageWithOneOrderUsesWholeScreenAsCard() {
        val a = analyze(Trees.detailPage())
        assertEquals(listOf("GF-613"), ofType(a, ObsType.READY).map { it.gf })
    }

    @Test
    fun pressFindsTheCardOfTheTappedButton() {
        val p = ScreenAnalyzer.analyzePress(listOf(Trees.preparingTab(pressOn = "GF-147")), cfg)
        assertEquals("GF-147", p.gf)
        assertEquals("5:53", p.countdown)
        assertTrue(p.card.contains("พร้อมจัดส่งใน: 5:53 นาที"))
        assertEquals(4, p.visible.size)
    }

    @Test
    fun pressWithoutMarkedNodeAndManyOrdersIsUnknown() {
        val p = ScreenAnalyzer.analyzePress(listOf(Trees.preparingTab(pressOn = null)), cfg)
        assertNull(p.gf)
        assertEquals(4, p.visible.size)
    }

    @Test
    fun pressOnDetailPageButtonOutsideCard() {
        val p = ScreenAnalyzer.analyzePress(listOf(Trees.detailPage(markButton = true)), cfg)
        assertEquals("GF-613", p.gf)
    }

    @Test
    fun visibleItemsForManualCapture() {
        val a = analyze(Trees.readyTab())
        val items = ScreenAnalyzer.visibleItems(a, cfg)
        assertEquals(listOf("GF-396", "GF-521", "GF-613"), items.map { it.gf })
        assertTrue(items.all { it.type == ObsType.VISIBLE })
        assertEquals("กำลังค้นหาคนขับ...", items[0].status)
    }

    @Test
    fun cardNeverCrossesIntoNeighbour() {
        val root = Trees.readyTab()
        val finder = CardFinder(cfg.gfExtractor(), root)
        val cards = finder.cards()
        assertEquals(3, cards.size)
        assertTrue(cards.none { c -> c.texts.any { it.startsWith("GF-") && it != c.gf } })
    }

    @Test
    fun treeDumpContainsTextsAndFlags() {
        val dump = TreeDump.dump(Trees.preparingTab(pressOn = "GF-961"))
        assertTrue(dump.contains("\"GF-961\""))
        assertTrue(dump.contains("[Button]{CM} \"พร้อมจัดส่ง\""))
        assertFalse(dump.contains("truncated"))
    }
}
