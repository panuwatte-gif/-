package io.github.panuwattegif.readyproof.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    // ---- v3: is the order number really in the picture? ------------------------------------

    private fun laid(root: UiNode, listHeight: Int? = null, scrollY: Int = 0, ctx: ScanContext = ScanContext()) =
        ScreenAnalyzer.analyze(listOf(Layout.of(root, listHeight, scrollY)), cfg, ctx)

    private fun full(a: ScreenAnalysis) = a.views.filter { it.full }.map { it.gf }

    @Test
    fun ordersFullyOnScreenAreReadyToShoot() {
        val a = laid(Trees.readyTabEn(withBanner = false))
        assertEquals(listOf("GF-231", "GF-439"), a.readyGfs())
        assertEquals(listOf("GF-231", "GF-439"), full(a))
        assertTrue(a.views.all { it.keyBoxes.size == 2 })
        assertFalse(a.canScroll)
    }

    @Test
    fun orderCutAtTheBottomIsNotShot() {
        // GF-439's number is on screen but its status line is below the edge
        val a = laid(Trees.readyTabEn(withBanner = false), listHeight = 200)
        assertEquals(listOf("GF-231", "GF-439"), a.readyGfs())
        assertEquals(listOf("GF-231"), full(a))
        assertTrue(a.canScrollForward)
        assertFalse(a.canScrollBackward)
    }

    @Test
    fun orderCutAtTheTopIsNotShot() {
        val a = laid(Trees.readyTabEn(withBanner = false), listHeight = 200, scrollY = 100)
        assertEquals(listOf("GF-439"), full(a))
        assertTrue(a.canScrollBackward)
        assertFalse(a.canScrollForward)
    }

    @Test
    fun somethingCoveringTheNumberMeansNoShot() {
        // another app's window (a floating button, the keyboard ...) over GF-231's number
        val covered = laid(Trees.readyTabEn(withBanner = false), ctx = ScanContext(listOf(Box(0, 470, 720, 500))))
        assertEquals(listOf("GF-439"), full(covered))
        // Grab's own floating button drawn over GF-439's number
        val fab = Layout.fix(Trees.n(desc = "Scan", cls = "android.widget.ImageButton", clickable = true), Box(560, 600, 700, 700))
        assertEquals(listOf("GF-231"), full(laid(Trees.readyTabEn(withBanner = false, fab = fab))))
        // ... its box reaches under the button but the letters are clear of it: still a good shot
        val fab2 = Layout.fix(Trees.n(desc = "Scan", cls = "android.widget.ImageButton", clickable = true), Box(560, 560, 700, 700))
        val ink = ScanContext { n -> if (n.text?.startsWith("GF-") == true || n.text?.startsWith("Finding") == true) Box(n.left, n.top + 5, n.left + 200, n.bottom - 5) else null }
        val laidFab = Layout.of(Trees.readyTabEn(withBanner = false, fab = fab2))
        assertEquals(listOf("GF-231"), full(ScreenAnalyzer.analyze(listOf(laidFab), cfg)))
        assertEquals(listOf("GF-231", "GF-439"), full(ScreenAnalyzer.analyze(listOf(laidFab), cfg, ink)))
        // ... but only over the "2 items" line: still a good shot
        val low = Layout.fix(Trees.n(desc = "Scan", cls = "android.widget.ImageButton", clickable = true), Box(560, 660, 700, 700))
        assertEquals(listOf("GF-231", "GF-439"), full(laid(Trees.readyTabEn(withBanner = false, fab = low))))
    }

    @Test
    fun hiddenCardsAreNotOnScreen() {
        val hidden = Trees.box(Trees.n("GF-999", shown = false), Trees.n("Finding a driver...", shown = false), clickable = true)
        val a = laid(Trees.readyTabEn(withBanner = false, extra = listOf(hidden)))
        assertEquals(listOf("GF-231", "GF-439"), a.visible)
        assertEquals(listOf("GF-231", "GF-439"), a.readyGfs())
    }

    @Test
    fun numberOnScreenButStatusLineScrolledAwayIsNotFull() {
        val cut = Trees.box(Trees.n("GF-500"), Trees.n("Finding a driver...", shown = false), clickable = true)
        val a = laid(Trees.readyTabEn(withBanner = false, extra = listOf(cut)))
        assertTrue("GF-500" in a.readyGfs())
        assertFalse("GF-500" in full(a))
        assertEquals("Finding a driver...", a.views.first { it.gf == "GF-500" }.status)
    }

    @Test
    fun aShortListInsideTheTabPagerIsNotScrollable() {
        // Grab's tabs as a sideways pager: scrolling it would switch tab, so it is never "the list"
        val pager = UiNode(className = "androidx.viewpager.widget.ViewPager", scrollable = true, canScrollForward = true, horizontal = true)
        val shortList = UiNode(className = Trees.RECYCLER, collection = true)
        shortList.add(Trees.box(Trees.n("GF-231"), Trees.n("Finding a driver..."), clickable = true))
        pager.add(shortList)
        val root = Trees.n(cls = "android.widget.FrameLayout", kids = listOf(Trees.box(Trees.tab("Preparing", null), Trees.tab("Ready", null, selected = true), Trees.tab("History", null)), pager))
        val a = analyze(root)
        assertEquals(listOf("GF-231"), a.readyGfs())
        assertTrue(a.scroller !== pager)
        assertFalse(a.canScroll)
    }

    @Test
    fun treesWithoutPositionsNeverCountAsFullyVisible() {
        val a = analyze(Trees.readyTabEn())
        assertTrue(a.views.none { it.full })
    }

    @Test
    fun longThaiReadyListAndItsBadge() {
        val a = laid(Trees.longReadyTabTh(), listHeight = 600)
        assertEquals(OrderTab.READY, a.tab)
        assertEquals(5, a.readyCount)
        assertEquals(listOf("GF-577", "GF-176", "GF-371", "GF-081", "GF-437"), a.readyGfs())
        assertEquals(listOf("GF-577", "GF-176", "GF-371", "GF-081"), full(a).take(4))
        assertFalse("GF-437" in full(a))
        assertTrue(a.canScrollForward)
        assertEquals("คนขับของคุณมาถึงแล้ว", a.views.first().status)
        assertTrue(a.tabsVisible)
    }

    @Test
    fun flattenedListNeedsTheStatusLineToo() {
        val a = laid(Trees.flattenedList())
        val v396 = a.views.first { it.gf == "GF-396" }
        assertTrue(v396.full)
        assertEquals(2, v396.keyBoxes.size)
    }

    @Test
    fun historyRowsNeedTheDelayLine() {
        val a = laid(Trees.historyWithHeaderEn())
        assertEquals(OrderTab.HISTORY, a.tab)
        val v700 = a.views.first { it.gf == "GF-700" }
        assertTrue(v700.full)
        assertTrue(v700.has(ObsType.DELAY))
        assertEquals(listOf("GF-133" to "19:32", "GF-700" to "19:09"), a.historyViews().map { it.gf to it.items.first { i -> i.type == ObsType.DONE }.doneAt })
    }

    @Test
    fun historyHeaderTotalsAndDate() {
        val h = laid(Trees.historyWithHeaderEn()).header!!
        assertEquals(java.time.LocalDate.of(2026, 10, 8), h.date)
        assertEquals(77, h.completed)
        assertEquals(0, h.cancelled)
        // without positions the number right after the label is used
        val plain = HistoryReader.read(listOf(Trees.historyWithHeaderEn()), cfg)
        assertEquals(77, plain.completed)
        assertEquals(0, plain.cancelled)
    }

    @Test
    fun historyHeaderLaidOutAsAGrid() {
        // Thai screen: labels on one row, numbers on the next
        val f = Layout::fix
        val root = Trees.n(
            cls = "android.widget.FrameLayout",
            kids = listOf(
                Trees.n("ส. 3 ต.ค. 2569"),
                Trees.box(
                    f(Trees.n("เสร็จสมบูรณ์"), Box(80, 760, 235, 790)),
                    f(Trees.n("ยกเลิก"), Box(296, 760, 436, 790)),
                    f(Trees.n("39"), Box(80, 810, 125, 850)),
                    f(Trees.n("2"), Box(296, 810, 320, 850)),
                ),
                Trees.list(Trees.box(Trees.n("GF-716"), Trees.n("เสร็จสมบูรณ์เมื่อ 3:48 PM"))),
            ),
        )
        val h = HistoryReader.read(listOf(Layout.of(root)), cfg)
        assertEquals(java.time.LocalDate.of(2026, 10, 3), h.date)
        assertEquals(39, h.completed)
        assertEquals(2, h.cancelled)
    }

    @Test
    fun tapTargetsAreOnlyEverTabsOrTheBottomBar() {
        val gfx = cfg.gfExtractor()
        val ready = TabDetector.tapTarget(listOf(Trees.preparingTabEn()), cfg.readyTabLabels, cfg.tabLabels, gfx)
        assertNotNull(ready)
        assertEquals("androidx.appcompat.app.ActionBar.Tab", ready.className)
        assertEquals("Ready", ready.children.first().text)
        // a screen with the order's "Ready" button but no tab bar: nothing to tap
        val noBar = Trees.n(
            cls = "android.widget.FrameLayout",
            kids = listOf(Trees.list(Trees.box(Trees.n("GF-861"), Trees.n("Ready in: 9:32 min"), Trees.button("Ready")))),
        )
        assertNull(TabDetector.tapTarget(listOf(noBar), cfg.readyTabLabels, cfg.tabLabels, gfx))
        val history = TabDetector.tapTarget(listOf(Trees.readyTab()), cfg.historyTabLabels, cfg.tabLabels, gfx)
        assertEquals("ประวัติ", history?.children?.first()?.text)
        // "Orders": the bottom bar item, not the page title
        val orders = TabDetector.tapTarget(listOf(Trees.historyWithHeaderEn()), cfg.ordersNavLabels, cfg.navLabels, gfx)
        assertNotNull(orders)
        assertTrue(orders.parent!!.children.any { it.text == "Home" })
        assertTrue(TabDetector.barVisible(listOf(Trees.readyTabEn()), cfg.tabLabels, gfx))
        assertFalse(TabDetector.barVisible(listOf(noBar), cfg.tabLabels, gfx))
    }

    @Test
    fun badgeCountOnTheReadyTab() {
        assertEquals(2, TabDetector.readyCount(listOf(Trees.readyTabEn()), cfg))
        assertEquals(3, TabDetector.readyCount(listOf(Trees.readyTab()), cfg))
        val inline = Trees.box(Trees.tab("Preparing", null), Trees.n("Ready (4)", selected = true), Trees.tab("History", null))
        assertEquals(4, TabDetector.readyCount(listOf(inline), cfg))
        val none = Trees.box(Trees.tab("Preparing", null), Trees.tab("Ready", null, selected = true), Trees.tab("History", null))
        assertNull(TabDetector.readyCount(listOf(none), cfg))
    }
}
