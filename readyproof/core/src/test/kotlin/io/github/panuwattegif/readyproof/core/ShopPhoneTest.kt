package io.github.panuwattegif.readyproof.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screens copied element by element from the shop's phone (OPPO CPH2773, Android 14, Grab in
 * Thai), from the dumps it uploaded on 8–9 Oct. Grab there marks no order tab as selected, puts
 * both totals in one element and reads bottom-bar items out with their position.
 */
class ShopPhoneTest {
    private val cfg = Config.DEFAULT

    private fun v(
        desc: String? = null,
        top: Int,
        bottom: Int,
        left: Int = 0,
        right: Int = 720,
        cls: String = "android.view.View",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        horizontal: Boolean = false,
        vararg kids: UiNode,
    ): UiNode {
        val n = UiNode(
            desc = desc, className = cls, clickable = clickable, scrollable = scrollable, selected = selected,
            left = left, top = top, right = right, bottom = bottom, horizontal = horizontal,
        )
        kids.forEach { n.add(it) }
        return n
    }

    private fun tabs() = v(
        top = 193, bottom = 289,
        kids = arrayOf(
            v(
                cls = "android.widget.HorizontalScrollView", top = 193, bottom = 289, scrollable = true, horizontal = true,
                kids = arrayOf(
                    v("กำลังเตรียม", 193, 289, 0, 180, clickable = true),
                    v("พร้อมจัดส่ง", 193, 289, 180, 360, clickable = true),
                    v("ที่กำลังจะถึง", 193, 289, 360, 540, clickable = true),
                    v("ประวัติ", 193, 289, 540, 720, clickable = true),
                ),
            ),
        ),
    )

    private fun bottomBar() = listOf(
        v("หน้าแรก, แท็บที่ 1 จาก 6", 1494, 1604, 0, 120, cls = "android.widget.ImageView", clickable = true),
        v("คำสั่งซื้อ, แท็บที่ 2 จาก 6", 1494, 1604, 120, 240, clickable = true, selected = true),
        v("เมนู, แท็บที่ 3 จาก 6", 1494, 1604, 240, 360, cls = "android.widget.ImageView", clickable = true),
        v("แคชเชียร์, แท็บที่ 4 จาก 6", 1494, 1604, 360, 480, cls = "android.widget.ImageView", clickable = true),
        v("เพิ่มเติม, แท็บที่ 5 จาก 6", 1494, 1604, 480, 600, cls = "android.widget.ImageView", clickable = true),
    )

    /** History of 8 Oct at the top of its list (dump HISTORY_CONFIRMED 2026-10-08T20:12:57). */
    private fun history(): UiNode {
        val list = v(
            cls = "android.widget.ScrollView", top = 291, bottom = 1494, scrollable = true,
            kids = arrayOf(
                v(
                    top = 291, bottom = 419,
                    kids = arrayOf(v("วันนี้, 08 ต.ค. 2026", 307, 387, 120, 600, clickable = true)),
                ),
                v(
                    "เสร็จสมบูรณ์ 77 ยกเลิก 0", 419, 810,
                    kids = arrayOf(
                        v("ยอดขายสุทธิ ฿ 16,035.00", 467, 572, cls = "android.widget.ImageView", clickable = true),
                        v("ดูข้อมูลเชิงลึกด้านการดำเนินงานร้าน", 714, 810, cls = "android.widget.ImageView", clickable = true),
                    ),
                ),
                v("คำสั่งซื้อ", 810, 942),
                v(
                    "GF-133 เสร็จสมบูรณ์เมื่อ 7:32 PM โฆษณา", 942, 1194, clickable = true,
                    kids = arrayOf(v("99.00", 1027, 1075, 560, 700, cls = "android.widget.ImageView", clickable = true)),
                ),
                v(
                    "GF-700 เสร็จสมบูรณ์เมื่อ 7:09 PM ล่าช้าไป 3 นาที ลูกค้าใหม่ โฆษณา", 1194, 1494, clickable = true,
                    kids = arrayOf(v("337.00", 1303, 1351, 560, 700, cls = "android.widget.ImageView", clickable = true)),
                ),
            ),
        )
        val page = v(
            top = 0, bottom = 1604,
            kids = arrayOf(
                v(top = 0, bottom = 193, kids = arrayOf(v("คำสั่งซื้อ", 97, 161, 40, 300), v("ปิดถึง 9:00 ก่อนเที่ยง", 90, 168, 400, 700, clickable = true))),
                tabs(),
                v(top = 291, bottom = 1494, kids = arrayOf(v(top = 291, bottom = 1494, scrollable = true, kids = arrayOf(list)))),
                v(top = 1318, bottom = 1462, left = 560, right = 704, clickable = true),
                *bottomBar().toTypedArray(),
            ),
        )
        return v(cls = "android.widget.FrameLayout", top = 0, bottom = 1604, kids = arrayOf(v(top = 0, bottom = 1604, clickable = true, scrollable = true, kids = arrayOf(page))))
    }

    @Test
    fun historyIsRecognisedWithoutASelectedTab() {
        val roots = listOf(history())
        assertEquals(OrderTab.HISTORY, TabDetector.openTab(roots, cfg))
        val a = ScreenAnalyzer.analyze(roots, cfg)
        assertEquals(OrderTab.HISTORY, a.tab)
        val h = assertNotNull(a.header)
        assertEquals(LocalDate.of(2026, 10, 8), h.date)
        assertEquals(77, h.completed)
        assertEquals(0, h.cancelled)
        assertEquals(listOf("GF-133", "GF-700"), a.historyViews().map { it.gf })
        val late = a.items.first { it.type == ObsType.DELAY }
        assertEquals("GF-700", late.gf)
        assertEquals(3, late.delayMin)
        assertEquals("19:09", late.doneAt.toString())
        // the list that moves is the ScrollView, never the tab strip
        assertEquals("android.widget.ScrollView", a.scroller?.className)
        assertTrue(a.motionKey().isNotEmpty())
        assertTrue(a.tabsVisible)
    }

    @Test
    fun tabsAndBottomBarCanBeTappedOnThisPhone() {
        val roots = listOf(history())
        val gfx = cfg.gfExtractor()
        assertEquals("ประวัติ", TabDetector.tapTarget(roots, cfg.historyTabLabels, cfg.tabLabels, gfx)?.desc)
        assertEquals("พร้อมจัดส่ง", TabDetector.tapTarget(roots, cfg.readyTabLabels, cfg.tabLabels, gfx)?.desc)
        assertTrue(TabDetector.isLabel("คำสั่งซื้อ, แท็บที่ 2 จาก 6", "คำสั่งซื้อ"))
        assertTrue(TabDetector.isLabel("Orders, tab 2 of 6", "Orders"))
    }

    @Test
    fun aReadyOrPreparingTabIsNotTakenForHistory() {
        val ready = v(
            cls = "android.widget.FrameLayout", top = 0, bottom = 1604,
            kids = arrayOf(
                tabs(),
                v(
                    cls = "android.widget.ScrollView", top = 291, bottom = 1494, scrollable = true,
                    kids = arrayOf(v("GF-582 คนขับของคุณมาถึงแล้ว 2 รายการ", 300, 520, clickable = true)),
                ),
            ),
        )
        assertNull(TabDetector.openTab(listOf(ready), cfg))
        assertNull(HistoryReader.countIn("ยกเลิก", cfg.cancelledLabels))
        assertNull(HistoryReader.countIn("GF-133 เสร็จสมบูรณ์เมื่อ 7:32 PM", cfg.completedLabels))
        assertEquals(1234, HistoryReader.countIn("Completed 1,234 Cancelled 5", cfg.completedLabels))
        assertEquals(5, HistoryReader.countIn("Completed 1,234 Cancelled 5", cfg.cancelledLabels))
    }

    /**
     * The Ready tab as the shop phone photographed it on 10 Oct 12:30 (GF-951 / GF-517 / GF-768).
     * Like the History rows in the real dumps, each order is one element holding all its text and
     * the gap above the card, so the elements follow each other without a gap and the first one
     * starts exactly where the list starts (y 291). Card borders in the photo: 323, 557, 791.
     */
    private fun ready(vararg cards: Pair<String, IntRange>, listTop: Int = 291): UiNode {
        val list = v(
            cls = "android.widget.ScrollView", top = listTop, bottom = 1494, scrollable = true,
            kids = cards.map { (desc, y) -> v(desc, y.first, y.last, clickable = true) }.toTypedArray(),
        )
        val page = v(
            top = 0, bottom = 1604,
            kids = arrayOf(
                v(top = 0, bottom = 193, kids = arrayOf(v("คำสั่งซื้อ", 97, 161, 40, 300), v("เปิดถึง 4:00 หลังเที่ยง", 90, 168, 400, 700, clickable = true))),
                tabs(),
                v(top = listTop, bottom = 1494, kids = arrayOf(v(top = listTop, bottom = 1494, scrollable = true, kids = arrayOf(list)))),
                v(top = 1318, bottom = 1462, left = 560, right = 704, clickable = true),
                *bottomBar().toTypedArray(),
            ),
        )
        return v(cls = "android.widget.FrameLayout", top = 0, bottom = 1604, kids = arrayOf(v(top = 0, bottom = 1604, clickable = true, scrollable = true, kids = arrayOf(page))))
    }

    @Test
    fun theFirstOrderAtTheTopOfTheReadyListIsPhotographed() {
        val a = ScreenAnalyzer.analyze(
            listOf(
                ready(
                    "GF-951 คนขับของคุณมาถึงแล้ว 1 รายการ · เงินสด" to 291..524,
                    "GF-517 คนขับจะมารับใน 3 นาที 1 รายการ" to 524..758,
                    "GF-768 คนขับจะมารับใน 8 นาที 2 รายการ" to 758..992,
                ),
            ),
            cfg,
        )
        assertEquals(listOf("GF-951", "GF-517", "GF-768"), a.readyGfs())
        // all three are completely on the photo (12:30 photo): the top one too
        assertEquals(listOf("GF-951", "GF-517", "GF-768"), a.readyViews().filter { it.full }.map { it.gf })
        assertTrue(a.readyViews().all { it.gfClear })
    }

    @Test
    fun aSingleOrderAtTheTopIsPhotographed() {
        // 13:18 GF-102 alone would sit the same way: nothing below it, so nothing scrolled away
        val a = ScreenAnalyzer.analyze(listOf(ready("GF-102 คนขับจะมารับใน 3 นาที 2 รายการ" to 291..524)), cfg)
        assertEquals(listOf("GF-102"), a.readyViews().filter { it.full }.map { it.gf })
        // the morning promotion banner pushes the list down; same rule there
        val b = ScreenAnalyzer.analyze(listOf(ready("GF-143 คนขับของคุณมาถึงแล้ว 1 รายการ" to 595..828, listTop = 595)), cfg)
        assertEquals(listOf("GF-143"), b.readyViews().filter { it.full }.map { it.gf })
    }

    @Test
    fun anOrderReallyCutOffAtAnEdgeIsStillNotFull() {
        // a long list scrolled down: the first element shown is only the lower half of an order,
        // the last one only its upper part
        val a = ScreenAnalyzer.analyze(
            listOf(
                ready(
                    "GF-711 คนขับจะมารับใน 1 นาที 1 รายการ" to 291..400,
                    "GF-829 คนขับจะมารับใน 1 นาที 1 รายการ" to 400..634,
                    "GF-882 คนขับจะมารับใน 1 นาที 1 รายการ" to 634..868,
                    "GF-552 คนขับจะมารับใน 9 นาที 2 รายการ" to 868..1102,
                    "GF-170 กำลังค้นหาคนขับ... 1 รายการ" to 1102..1336,
                    "GF-930 กำลังค้นหาคนขับ... 2 รายการ" to 1336..1494,
                ),
            ),
            cfg,
        )
        assertEquals(listOf("GF-829", "GF-882", "GF-552"), a.readyViews().filter { it.full }.map { it.gf })
        assertTrue(a.readyViews().first { it.gf == "GF-711" }.let { !it.full && !it.gfClear })
        assertTrue(a.readyViews().first { it.gf == "GF-930" }.let { !it.full && !it.gfClear })
        // only the gap above the card is cut off (a few pixels): the card itself is all there
        val b = ScreenAnalyzer.analyze(
            listOf(
                ready(
                    "GF-711 คนขับจะมารับใน 1 นาที 1 รายการ" to 291..520,
                    "GF-829 คนขับจะมารับใน 1 นาที 1 รายการ" to 520..754,
                ),
            ),
            cfg,
        )
        assertEquals(listOf("GF-711", "GF-829"), b.readyViews().filter { it.full }.map { it.gf })
    }

    /** Orders screen as dumped on 10 Oct: tab labels carry their counts, the list reports both scroll flags. */
    private fun orders10Oct(top: Int, vararg list: UiNode, preparing: String = "กำลังเตรียม 2", ready: String = "พร้อมจัดส่ง 1"): UiNode {
        val tabStrip = v(
            top = top + 128, bottom = top + 224,
            kids = arrayOf(
                v(
                    cls = "android.widget.HorizontalScrollView", top = top + 128, bottom = top + 224, scrollable = true, horizontal = true,
                    kids = arrayOf(
                        v(preparing, top + 128, top + 224, 0, 240, clickable = true),
                        v(ready, top + 128, top + 224, 240, 480, clickable = true),
                        v("ที่กำลังจะถึง", top + 128, top + 224, 480, 720, clickable = true),
                    ),
                ),
            ),
        )
        val listTop = top + 226
        val scroller = UiNode(
            className = "android.view.View", scrollable = true, canScrollForward = true, canScrollBackward = true,
            left = 0, top = listTop, right = 720, bottom = 1494,
        )
        val inner = v(top = listTop, bottom = 1494, kids = arrayOf(v(top = listTop, bottom = 1494, kids = list)))
        scroller.add(inner)
        val page = v(
            top = top, bottom = 1604,
            kids = arrayOf(
                v(top = top, bottom = top + 128, kids = arrayOf(v("คำสั่งซื้อ", top + 32, top + 96, 40, 300), v("เปิดถึง 4:00 หลังเที่ยง", top + 25, top + 103, 400, 700, clickable = true))),
                tabStrip,
                v(top = listTop, bottom = 1494, kids = arrayOf(scroller)),
                v(top = 1318, bottom = 1462, left = 560, right = 704, clickable = true),
                *bottomBar().toTypedArray(),
            ),
        )
        return v(cls = "android.widget.FrameLayout", top = 0, bottom = 1604, kids = arrayOf(v(top = 0, bottom = 1604, clickable = true, scrollable = true, kids = arrayOf(page))))
    }

    @Test
    fun realDumpOf1251TheOnlyOrderStartsAtTheTopOfTheList() {
        // dump-1791611485754: (GF-407 คนขับจะมารับใน 5 นาที 1 รายการ) @291-525 in the list @291-1494
        val card = v("GF-407 คนขับจะมารับใน 5 นาที 1 รายการ", 291, 525, clickable = true,
            kids = arrayOf(v(top = 400, bottom = 448, left = 630, right = 678, cls = "android.widget.ImageView", clickable = true)))
        val a = ScreenAnalyzer.analyze(listOf(orders10Oct(65, card)), cfg)
        assertNull(a.tab)
        assertEquals(listOf("GF-407"), a.readyViews().filter { it.full && it.gfClear }.map { it.gf })
        assertEquals(1, a.readyCount)
        assertTrue(!a.readyListBlank)
        // the 12:30 photo in the same nesting: three orders, the top one included
        fun card(desc: String, top: Int) = v(desc, top, top + 234, clickable = true,
            kids = arrayOf(v(top = top + 109, bottom = top + 157, left = 630, right = 678, cls = "android.widget.ImageView", clickable = true)))
        val three = ScreenAnalyzer.analyze(
            listOf(
                orders10Oct(
                    65,
                    card("GF-951 คนขับของคุณมาถึงแล้ว 1 รายการ · เงินสด", 291),
                    card("GF-517 คนขับจะมารับใน 3 นาที 1 รายการ", 525),
                    card("GF-768 คนขับจะมารับใน 8 นาที 2 รายการ", 759),
                ),
            ),
            cfg,
        )
        assertEquals(listOf("GF-951", "GF-517", "GF-768"), three.readyViews().filter { it.full && it.gfClear }.map { it.gf })
    }

    @Test
    fun aSelfPickupOrderWaitingForItsCustomerIsAReadyOrder() {
        // photo GF-840_READY_2026-10-15_15-15-30: self-pickup GF-066T ("ลูกค้าใกล้ถึงแล้ว") above
        // GF-840; 1.0.152 counted only GF-840 because GF-066T mentions no driver
        fun card(desc: String, top: Int) = v(desc, top, top + 234, clickable = true,
            kids = arrayOf(v(top = top + 109, bottom = top + 157, left = 630, right = 678, cls = "android.widget.ImageView", clickable = true)))
        val both = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, card("GF-066T ลูกค้าใกล้ถึงแล้ว 1 รายการ", 291), card("GF-840 คนขับของคุณมาถึงแล้ว 1 รายการ", 525))),
            cfg,
        )
        assertEquals(listOf("GF-066T", "GF-840"), both.readyViews().filter { it.full && it.gfClear }.map { it.gf })
        // alone in the list
        val alone = ScreenAnalyzer.analyze(listOf(orders10Oct(65, card("GF-066T ลูกค้าใกล้ถึงแล้ว 1 รายการ", 291))), cfg)
        assertEquals(listOf("GF-066T"), alone.readyViews().filter { it.full && it.gfClear }.map { it.gf })
        // in the same list as an order waiting for its driver, any wording counts: the Ready list
        // holds nothing else
        val other = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, card("GF-512X รอรับที่ร้าน 1 รายการ", 291), card("GF-840 คนขับของคุณมาถึงแล้ว 1 รายการ", 525))),
            cfg,
        )
        assertEquals(listOf("GF-512X", "GF-840"), other.readyGfs())
        assertEquals("GF-512X รอรับที่ร้าน 1 รายการ", other.readyViews().first().items.single().status)
        // ... but never an order already finished
        val mixed = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, card("GF-222 เสร็จสมบูรณ์เมื่อ 3:15 PM", 291), card("GF-840 คนขับของคุณมาถึงแล้ว 1 รายการ", 525))),
            cfg,
        )
        assertEquals(listOf("GF-840"), mixed.readyGfs())
        // an order on its own with an unknown line is not taken for Ready (it could be any tab)
        val unknown = ScreenAnalyzer.analyze(listOf(orders10Oct(65, card("GF-512X รอรับที่ร้าน 1 รายการ", 291))), cfg)
        assertTrue(unknown.readyGfs().isEmpty())
    }

    /** A Preparing card as one element with the order's own green button inside it. */
    private fun prepCard(desc: String, top: Int, button: String = "พร้อมจัดส่ง", height: Int = 250) = v(
        desc, top, top + height, clickable = true,
        kids = arrayOf(v(button, top + 75, top + 140, 460, 690, cls = "android.widget.Button", clickable = true)),
    )

    @Test
    fun thePreparingTabShowsTheTimeLeftAndNothingOnItIsReady() {
        // 22 Oct 11:10: GF-961 has run out of time (red 0:00) and has not been pressed
        val a = ScreenAnalyzer.analyze(
            listOf(
                orders10Oct(
                    65,
                    v("เสร็จสิ้นคำสั่งซื้อนี้เร็ว ๆ นี้", 291, 340),
                    v("อย่าลืมกดปุ่ม 'พร้อมจัดส่ง' หลังจากที่คุณเตรียมคำสั่งซื้อเสร็จแล้ว", 340, 380),
                    prepCard("GF-961 พร้อมจัดส่งใน: 0:00 นาที 1 รายการ", 390),
                    prepCard("GF-992 พร้อมจัดส่งใน: 1:40 นาที 1 รายการ", 640),
                    prepCard("GF-952 พร้อมจัดส่งใน: 5:44 นาที 1 รายการ", 890),
                    prepCard("GF-884 พร้อมจัดส่งใน: 3:42 นาที 1 รายการ", 1140),
                    prepCard("GF-147 พร้อมจัดส่งใน: 8:40 นาที 2 รายการ", 1390, height = 104),
                    preparing = "กำลังเตรียม 5", ready = "พร้อมจัดส่ง 2",
                ),
            ),
            cfg,
        )
        assertEquals(OrderTab.PREPARING, a.tab)
        assertTrue(a.readyGfs().isEmpty())
        assertEquals(
            listOf("GF-961" to 0, "GF-992" to 100, "GF-952" to 344, "GF-884" to 222, "GF-147" to 520),
            a.preparingViews().map { v -> v.gf to v.items.single { it.type == ObsType.PREPARING }.dueSeconds },
        )
        assertEquals(5, a.preparingCount)
        assertEquals(2, a.readyCount)
        // the red one is completely on screen (photo); the last one is cut off at the bottom
        assertTrue(a.preparingViews().first { it.gf == "GF-961" }.full)
        assertTrue(!a.preparingViews().first { it.gf == "GF-147" }.full)
        // the only thing ever tapped there is the tab name, never an order's own button
        val gfx = cfg.gfExtractor()
        assertEquals("พร้อมจัดส่ง 2", TabDetector.tapTarget(listOf(orders10Oct(65, prepCard("GF-961 พร้อมจัดส่งใน: 0:00 นาที 1 รายการ", 390),
            ready = "พร้อมจัดส่ง 2")), cfg.readyTabLabels, cfg.tabLabels, gfx)?.desc)
    }

    @Test
    fun anOrderOfABatchAlreadyMarkedReadyIsNotTakenForReadyOnThePreparingTab() {
        // 22 Oct 16:08: one driver for two orders; GF-595 is done (✓), GF-556 ran out of time
        val group = v(
            top = 400, bottom = 900, clickable = false,
            kids = arrayOf(
                v("คำสั่งซื้อเหล่านี้จะมีคนขับคนเดียวกันมารับ โปรดเตรียมคำสั่งซื้อพร้อมกัน", 400, 480),
                v("GF-595 คนขับจะมารับใน 0 นาที 1 รายการ", 480, 660,
                    kids = arrayOf(v(top = 540, bottom = 600, left = 600, right = 660, cls = "android.widget.ImageView"))),
                prepCard("GF-556 พร้อมจัดส่งใน: 0:00 นาที 2 รายการ", 660, height = 200),
            ),
        )
        val a = ScreenAnalyzer.analyze(listOf(orders10Oct(65, group, preparing = "กำลังเตรียม 1", ready = "พร้อมจัดส่ง 1")), cfg)
        assertEquals(OrderTab.PREPARING, a.tab)
        assertTrue(a.readyGfs().isEmpty())
        assertEquals(listOf("GF-556" to "0:00"), a.preparingViews().map { v -> v.gf to v.items.single { it.type == ObsType.PREPARING }.countdown })
    }

    @Test
    fun englishAndNewerPreparingLayoutsAreReadToo() {
        // 12:54 in English
        val en = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, prepCard("GF-173 Ready in: 0:00 min 1 item", 390, button = "Ready"),
                prepCard("GF-960 Ready in: 9:09 min 3 items", 640, button = "Ready"))),
            cfg,
        )
        assertEquals(listOf("GF-173" to 0, "GF-960" to 549), en.preparingViews().map { v -> v.gf to v.items.single { it.type == ObsType.PREPARING }.dueSeconds })
        assertTrue(en.readyGfs().isEmpty())
        // 11:14, newer layout: no "Ready in", the time stands under the button, and the line above
        // the order talks about the driver ("Driver should arrive in 5-10 mins")
        fun newCard(gf: String, items: String, time: String, top: Int) = v(
            top = top, bottom = top + 360, clickable = false,
            kids = arrayOf(
                v("Driver should arrive in 5-10 mins", top, top + 120),
                v(top = top + 120, bottom = top + 360, kids = arrayOf(
                    v(gf, top + 150, top + 220, 40, 300), v(items, top + 220, top + 270, 40, 300),
                    v("Ready", top + 150, top + 240, 550, 860, cls = "android.widget.Button", clickable = true),
                    v(time, top + 250, top + 300, 640, 760),
                )),
            ),
        )
        val newer = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, newCard("GF-229", "3 items", "00:53", 380), newCard("GF-763", "1 item", "02:31", 740))),
            cfg,
        )
        assertTrue(newer.readyGfs().isEmpty(), "unexpected ${newer.items}")
        assertEquals(listOf("GF-229" to 53, "GF-763" to 151), newer.preparingViews().map { v -> v.gf to v.items.single { it.type == ObsType.PREPARING }.dueSeconds })
    }

    @Test
    fun theReadyTabOf22OctIsUnchanged() {
        // 10:01: five orders, one of them red ("โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง") — still in the Ready tab
        fun card(desc: String, top: Int) = v(desc, top, top + 234, clickable = true,
            kids = arrayOf(v(top = top + 109, bottom = top + 157, left = 630, right = 678, cls = "android.widget.ImageView", clickable = true)))
        val a = ScreenAnalyzer.analyze(
            listOf(
                orders10Oct(
                    65,
                    card("GF-577 คนขับของคุณมาถึงแล้ว 2 รายการ", 291),
                    card("GF-176 โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง 3 รายการ", 525),
                    card("GF-371 คนขับจะมารับใน 10 นาที 2 รายการ", 759),
                    card("GF-081 คนขับจะมารับใน 4 นาที 2 รายการ", 993),
                    card("GF-437 คนขับจะมารับใน 0 นาที 2 รายการ", 1227),
                    preparing = "กำลังเตรียม", ready = "พร้อมจัดส่ง 5",
                ),
            ),
            cfg,
        )
        assertNull(a.tab)
        assertEquals(listOf("GF-577", "GF-176", "GF-371", "GF-081", "GF-437"), a.readyGfs())
        assertTrue(a.preparingViews().isEmpty())
        assertNull(a.preparingCount)
        assertEquals(5, a.readyCount)
    }

    @Test
    fun anEmptyReadyOrPreparingTabIsStillKnown() {
        // 23 Oct: nothing in the Ready tab, a new order being prepared — the watcher must still
        // know it is on the Ready tab (1.0.170 did not, and never went to read the new order)
        val empty = ScreenAnalyzer.analyze(
            listOf(
                orders10Oct(
                    65,
                    v("พร้อมจัดส่ง", 962, 994),
                    v(top = 843, bottom = 1245, cls = "android.widget.ImageView"),
                    v("อัปเดตสถานะคำสั่งซื้อทันทีเมื่อเตรียมเสร็จแล้ว", 1295, 1343),
                    preparing = "กำลังเตรียม 1", ready = "พร้อมจัดส่ง",
                ),
            ),
            cfg,
        )
        assertEquals(OrderTab.READY, empty.tab)
        assertEquals(1, empty.preparingCount)
        assertNull(empty.readyCount)
        assertTrue(!empty.readyListBlank)
        // the same for an empty Preparing tab
        val prep = ScreenAnalyzer.analyze(
            listOf(orders10Oct(65, v("กำลังเตรียม", 962, 994), preparing = "กำลังเตรียม", ready = "พร้อมจัดส่ง 2")),
            cfg,
        )
        assertEquals(OrderTab.PREPARING, prep.tab)
        // no title at all: unknown, as before
        assertNull(ScreenAnalyzer.analyze(listOf(orders10Oct(65, v("กำลังโหลด", 962, 994))), cfg).tab)
    }

    @Test
    fun realDumpOf1121TheReadyTabCountsAnOrderButItsListIsBlank() {
        // dump-1791606084957: "พร้อมจัดส่ง 1" on the tab, the list shows only the empty-list picture
        val a = ScreenAnalyzer.analyze(
            listOf(
                orders10Oct(
                    369,
                    v("พร้อมจัดส่ง", 962, 994),
                    v(top = 843, bottom = 1245, cls = "android.widget.ImageView"),
                    v("อัปเดตสถานะคำสั่งซื้อทันทีเมื่อเตรียมเสร็จแล้ว", 1295, 1343),
                    v("การอัปเดตจะช่วยให้ลูกค้าของคุณได้รับประสบการณ์การจัดส่งที่ดี และแม่นยำที่สุด", 1359, 1455),
                ),
            ),
            cfg,
        )
        // the empty list's own title says it is the Ready tab
        assertEquals(OrderTab.READY, a.tab)
        assertTrue(a.readyGfs().isEmpty())
        assertEquals(1, a.readyCount)
        assertTrue(a.readyListBlank)
    }

    @Test
    fun historyRowsKeepTheStrictEdgeRule() {
        // the History sweep works on this phone: a row touching the top edge still counts as cut off
        val top = v(
            cls = "android.widget.FrameLayout", top = 0, bottom = 1604,
            kids = arrayOf(
                tabs(),
                v(
                    cls = "android.widget.ScrollView", top = 291, bottom = 1494, scrollable = true,
                    kids = arrayOf(
                        v("GF-133 เสร็จสมบูรณ์เมื่อ 7:32 PM โฆษณา", 291, 543, clickable = true),
                        v("GF-700 เสร็จสมบูรณ์เมื่อ 7:09 PM ล่าช้าไป 3 นาที ลูกค้าใหม่ โฆษณา", 543, 843, clickable = true),
                        v("GF-415 เสร็จสมบูรณ์เมื่อ 6:43 PM ลูกค้าใหม่", 843, 1095, clickable = true),
                    ),
                ),
            ),
        )
        val h = ScreenAnalyzer.analyze(listOf(top), cfg)
        assertEquals(listOf("GF-700", "GF-415"), h.historyViews().filter { it.full }.map { it.gf })
    }

    @Test
    fun theFifthOrderUnderGrabsScanButtonStillGetsABackupPhoto() {
        val cards = listOf("GF-711", "GF-829", "GF-882", "GF-552", "GF-045").mapIndexed { i, gf ->
            "$gf คนขับจะมารับใน 5 นาที 1 รายการ" to (291 + 234 * i)..(291 + 234 * (i + 1))
        }
        val a = ScreenAnalyzer.analyze(listOf(ready(*cards.toTypedArray())), cfg)
        assertEquals(listOf("GF-711", "GF-829", "GF-882", "GF-552"), a.readyViews().filter { it.full }.map { it.gf })
        assertTrue(a.readyViews().first { it.gf == "GF-045" }.gfClear)
    }
}
