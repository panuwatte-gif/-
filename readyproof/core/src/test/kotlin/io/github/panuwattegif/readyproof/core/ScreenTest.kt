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

    // ---- English screens (the shop's phone today) ------------------------------------------

    @Test
    fun englishReadyTabCountsEveryListedOrderButNotTheBanner() {
        val a = analyze(Trees.readyTabEn())
        assertEquals(true, a.readyTab)
        assertEquals(listOf("GF-941", "GF-231", "GF-439"), a.visible)
        val ready = ofType(a, ObsType.READY)
        assertEquals(listOf("GF-231", "GF-439"), ready.map { it.gf })
        assertEquals(listOf("Finding a driver...", "Finding a driver..."), ready.map { it.status })
        assertEquals(listOf("GF-231", "Finding a driver...", "1 item"), ready[0].card)
    }

    @Test
    fun englishPreparingTabIsNeverReadyEvenWithDriverText() {
        val a = analyze(Trees.preparingTabEn())
        assertEquals(false, a.readyTab)
        assertTrue(a.items.isEmpty(), "unexpected ${a.items}")
    }

    @Test
    fun englishHistoryReadsCompletedAndDelayedButNotCancelled() {
        val a = analyze(Trees.historyTabEn())
        assertEquals(false, a.readyTab)
        assertEquals(
            listOf("GF-613" to "11:47", "GF-028" to "12:06", "GF-944" to "11:44", "GF-120" to "13:05"),
            ofType(a, ObsType.DONE).map { it.gf to it.doneAt },
        )
        assertEquals(listOf("GF-613" to 4, "GF-120" to 1), ofType(a, ObsType.DELAY).map { it.gf to it.delayMin })
        assertTrue(ofType(a, ObsType.READY).isEmpty())
    }

    @Test
    fun englishReadyButtonTapFindsItsOrder() {
        val p = ScreenAnalyzer.analyzePress(listOf(Trees.preparingTabEn(pressOn = "GF-861")), cfg)
        assertEquals("GF-861", p.gf)
        assertEquals("9:32", p.countdown)
    }

    @Test
    fun readyTabCountsOrdersWhateverTheirStatusSays() {
        val root = Trees.n(
            cls = "android.widget.FrameLayout",
            kids = listOf(
                Trees.box(Trees.tab("Preparing", null), Trees.tab("Ready", "1", selected = true)),
                Trees.list(Trees.box(Trees.n("GF-500"), Trees.n("Waiting for pickup"), Trees.n("3 items"))),
            ),
        )
        assertEquals(listOf("GF-500"), ofType(analyze(root), ObsType.READY).map { it.gf })
    }

    @Test
    fun tabLabels() {
        assertTrue(TabDetector.isLabel("Ready", "Ready"))
        assertTrue(TabDetector.isLabel("Ready 2", "Ready"))
        assertTrue(TabDetector.isLabel("Ready (2)", "Ready"))
        assertTrue(TabDetector.isLabel("ready, 2", "Ready"))
        assertTrue(TabDetector.isLabel("พร้อมจัดส่ง 3", "พร้อมจัดส่ง"))
        assertFalse(TabDetector.isLabel("Ready in: 9:32 min", "Ready"))
        assertFalse(TabDetector.isLabel("พร้อมจัดส่งใน: 5:53 นาที", "พร้อมจัดส่ง"))
        assertFalse(TabDetector.isLabel("Already", "Ready"))
        assertFalse(TabDetector.isLabel("Ready", ""))
    }

    // ---- Thai screens (earlier evidence) ---------------------------------------------------

    @Test
    fun thaiReadyTabWithSelectionReported() {
        val a = analyze(Trees.readyTab(selectionReported = true))
        assertEquals(true, a.readyTab)
        assertEquals(listOf("GF-396", "GF-521", "GF-613"), ofType(a, ObsType.READY).map { it.gf })
    }

    @Test
    fun withoutSelectionTheStatusWordsDecide() {
        val a = analyze(Trees.readyTab())
        assertNull(a.readyTab)
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
    fun manualCaptureKeepsWhatTheScreenProves() {
        val ready = ScreenAnalyzer.manualItems(analyze(Trees.readyTabEn()), cfg)
        // READY for the listed orders, VISIBLE for the banner's order number
        assertEquals(listOf("GF-231" to ObsType.READY, "GF-439" to ObsType.READY, "GF-941" to ObsType.VISIBLE), ready.map { it.gf to it.type })
        val preparing = ScreenAnalyzer.manualItems(analyze(Trees.preparingTabEn()), cfg)
        assertTrue(preparing.all { it.type == ObsType.VISIBLE })
        assertEquals("Ready in: 9:32 min", preparing.first { it.gf == "GF-861" }.status)
    }

    @Test
    fun cardNeverCrossesIntoNeighbour() {
        val root = Trees.readyTab()
        val finder = CardFinder(cfg.gfExtractor(), root)
        val cards = finder.cards()
        assertEquals(3, cards.size)
        assertTrue(cards.all { it.inList })
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
