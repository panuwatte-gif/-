package io.github.panuwattegif.readyproof.core

/** Test trees modelled on real GrabMerchant screenshots (Thai and English) from the shop. */
object Trees {
    const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"

    fun n(
        text: String? = null,
        cls: String = "android.widget.TextView",
        desc: String? = null,
        clickable: Boolean = false,
        collection: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        marked: Boolean = false,
        id: String? = null,
        shown: Boolean = true,
        kids: List<UiNode> = emptyList(),
    ): UiNode {
        val node = UiNode(
            text = text, desc = desc, viewId = id, className = cls, clickable = clickable,
            scrollable = scrollable, collection = collection, selected = selected, marked = marked,
            shown = shown,
        )
        kids.forEach { node.add(it) }
        return node
    }

    fun box(vararg kids: UiNode, clickable: Boolean = false) =
        n(cls = "android.widget.LinearLayout", clickable = clickable, kids = kids.toList())

    fun list(vararg kids: UiNode) = n(cls = RECYCLER, collection = true, scrollable = true, kids = kids.toList())

    fun button(text: String, marked: Boolean = false) =
        n(text, cls = "android.widget.Button", clickable = true, marked = marked)

    /** Material tab: "selected" is set on the tab and passed down to its label, as Android does. */
    fun tab(label: String, badge: String?, selected: Boolean = false) = n(
        cls = "androidx.appcompat.app.ActionBar.Tab", clickable = true, selected = selected,
        kids = listOfNotNull(n(label, selected = selected), badge?.let { n(it, selected = selected) }),
    )

    /** Thai header; [selectedTab] = index of the open tab, null = app does not report selection. */
    private fun header(ready: String?, preparing: String?, selectedTab: Int? = null) = box(
        n("คำสั่งซื้อ"),
        n("เปิดถึง 7:00 หลังเที่ยง"),
        box(
            tab("กำลังเตรียม", preparing, selectedTab == 0), tab("พร้อมจัดส่ง", ready, selectedTab == 1),
            tab("ที่กำลังจะถึง", null, selectedTab == 2), tab("ประวัติ", null, selectedTab == 3),
        ),
    )

    private fun headerEn(selected: String) = box(
        n("Orders"),
        n("Open until 7:00 PM"),
        box(
            tab("Preparing", "1", selected == "Preparing"), tab("Ready", "2", selected == "Ready"),
            tab("Upcoming", null, selected == "Upcoming"), tab("History", null, selected == "History"),
        ),
    )

    private fun bottomNav() = box(n("หน้าแรก"), n("คำสั่งซื้อ"), n("เมนู"), n("แคชเชียร์"), n("เพิ่มเติม"))

    private fun bottomNavEn() = box(n("Home"), n("Orders", selected = true), n("Menu"), n("Cashier"), n("More"))

    /** GF-613_READY.jpg (Thai): the "พร้อมจัดส่ง" tab at 11:01. */
    fun readyTab(selectionReported: Boolean = false): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = "3", preparing = "1", selectedTab = if (selectionReported) 1 else null),
            list(
                box(n("GF-396"), n("กำลังค้นหาคนขับ..."), n("2 รายการ"), clickable = true),
                box(n("GF-521"), n("คนขับจะมารับใน 0 นาที"), n("2 รายการ"), clickable = true),
                box(n("GF-613"), n("กําลังค้นหาคนขับ..."), n("8 รายการ"), clickable = true), // decomposed sara am
            ),
            bottomNav(),
        ),
    )

    /** GF-450_GF-697_01_BEFORE-ready.jpg: the "กำลังเตรียม" tab; [pressOn] marks that card's button. */
    fun preparingTab(pressOn: String? = null): UiNode {
        fun card(gf: String, countdown: String, items: String, extra: String? = null) = box(
            n(gf),
            n("พร้อมจัดส่งใน: $countdown นาที"),
            *listOfNotNull(extra?.let { n(it) }).toTypedArray(),
            n(items),
            button("พร้อมจัดส่ง", marked = gf == pressOn),
            clickable = true,
        )
        return n(
            cls = "android.widget.FrameLayout",
            kids = listOf(
                header(ready = "3", preparing = "4"),
                box(
                    n("เสร็จสิ้นคำสั่งซื้อนี้เร็ว ๆ นี้"),
                    n("อย่าลืมกดปุ่ม 'พร้อมจัดส่ง' หลังจากที่คุณเตรียมคำสั่งซื้อเสร็จแล้ว"),
                ),
                list(
                    card("GF-961", "0:00", "1 รายการ"),
                    card("GF-147", "5:53", "2 รายการ", extra = "คนขับจะมารับใน 3 นาที"),
                    card("GF-697", "8:32", "1 รายการ"),
                    card("GF-450", "8:15", "1 รายการ"),
                ),
                bottomNav(),
            ),
        )
    }

    /** Screenshot_20260923_221512.jpg: the "ประวัติ" tab. */
    fun historyTab(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = null, preparing = null),
            list(
                box(n("ลูกค้าใหม่"), n("โฆษณา"), n("GF-610"), n("เสร็จสมบูรณ์เมื่อ 12:56 PM"), n("223.00"), n("ล่าช้าไป 4 นาที")),
                box(n("โฆษณา"), n("GF-722"), n("184.00"), n("เสร็จสมบูรณ์เมื่อ 12:18 PM")),
                box(n("GF-380"), n("เสร็จสมบูรณ์เมื่อ 12:24 PM"), n("449.00"), n("ล่าช้าไป 9 นาที")),
                box(n("GF-161"), n("เสร็จสมบูรณ์เมื่อ 12:42 PM"), n("ล่าช้าไป 15 นาที")),
                box(n("GF-024"), n("เสร็จสมบูรณ์เมื่อ 12:19 PM"), n("195.00")),
            ),
            bottomNav(),
        ),
    )

    /** The shop's English screenshot (14:27): "Ready" tab with two orders finding a driver. */
    fun readyTabEn(withBanner: Boolean = true, fab: UiNode? = null, extra: List<UiNode> = emptyList()): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOfNotNull(
            // In-app banner naming another order: must not count as being in the Ready tab.
            if (withBanner) box(n("แก้ไขปัญหาคำสั่งซื้อ GF-941 เรียบร้อยแล้ว"), n("แตะที่นี่เพื่อดูรายละเอียด"), clickable = true) else null,
            headerEn(selected = "Ready"),
            list(
                box(n("GF-231"), n("Finding a driver..."), n("1 item"), clickable = true),
                box(n("GF-439"), n("Finding a driver..."), n("2 items"), clickable = true),
                *extra.toTypedArray(),
            ),
            fab,
            bottomNavEn(),
        ),
    )

    /** Thai Ready tab with five orders (shop screenshot 10:01): the list is longer than the screen. */
    fun longReadyTabTh(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = "5", preparing = null, selectedTab = 1),
            list(
                box(n("GF-577"), n("คนขับของคุณมาถึงแล้ว"), n("2 รายการ"), clickable = true),
                box(n("GF-176"), n("โปรดเตรียมคำสั่งซื้อนี้ให้พร้อมจัดส่ง"), n("3 รายการ"), clickable = true),
                box(n("GF-371"), n("คนขับจะมารับใน 10 นาที"), n("2 รายการ"), clickable = true),
                box(n("GF-081"), n("คนขับจะมารับใน 4 นาที"), n("2 รายการ"), clickable = true),
                box(n("GF-437"), n("คนขับจะมารับใน 0 นาที"), n("2 รายการ"), clickable = true),
            ),
            bottomNav(),
        ),
    )

    /** History tab (shop screenshot 21:52): date, Grab's totals, then the order rows. */
    fun historyWithHeaderEn(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            headerEn(selected = "History"),
            n(
                cls = "androidx.core.widget.NestedScrollView", scrollable = true,
                kids = listOf(
                    box(n("Today, 08 Oct 2026")),
                    box(
                        n("Net sales"), n("฿16,035.00"),
                        box(box(n("Completed"), n("77")), box(n("Cancelled"), n("0"))),
                        n("See store operations insights"),
                    ),
                    n("Orders"),
                    n(
                        cls = RECYCLER, collection = true,
                        kids = listOf(
                            box(n("GF-133"), n("Completed at 7:32 PM"), n("99.00"), n("Ads"), clickable = true),
                            box(n("GF-700"), n("Completed at 7:09 PM"), n("Delayed by 3 mins"), n("337.00"), n("New customer"), n("Ads"), clickable = true),
                        ),
                    ),
                ),
            ),
            bottomNavEn(),
        ),
    )

    /** The shop's English screenshot (14:28): "Preparing" tab with the "Ready" button. */
    fun preparingTabEn(pressOn: String? = null): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            box(n("แก้ไขปัญหาคำสั่งซื้อ GF-941 เรียบร้อยแล้ว"), n("แตะที่นี่เพื่อดูรายละเอียด"), clickable = true),
            headerEn(selected = "Preparing"),
            list(
                box(n("GF-861"), n("Ready in: 9:32 min"), n("2 items"), button("Ready", marked = pressOn == "GF-861"), clickable = true),
                box(n("GF-862"), n("Ready in: 3:10 min"), n("Driver arriving in 2 mins"), n("1 item"), button("Ready"), clickable = true),
            ),
            bottomNavEn(),
        ),
    )

    /** GF-613_DELAY.jpg (English): the History tab. */
    fun historyTabEn(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            headerEn(selected = "History"),
            list(
                box(n("GF-555"), n("Cancelled at 1:34 AM")),
                box(n("New customer"), n("Ads"), n("GF-613"), n("Completed at 11:47 AM"), n("1,167.00"), n("Delayed by 4 mins")),
                box(n("New customer"), n("Ads"), n("GF-028"), n("Completed at 12:06 PM")),
                box(n("Ads"), n("GF-944"), n("Completed at 11:44 AM"), n("173.00")),
                box(n("GF-120"), n("Completed at 1:05 PM"), n("Delayed by 1 min")),
            ),
            bottomNavEn(),
        ),
    )

    /** Same data as the ready tab but the app reports every text directly under the list. */
    fun flattenedList(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = "3", preparing = "1"),
            list(
                n("GF-396"), n("กำลังค้นหาคนขับ..."), n("2 รายการ"),
                n("GF-521"), n("คนขับจะมารับใน 0 นาที"),
                n("GF-613"), n("เสร็จสมบูรณ์เมื่อ 7:28 PM"),
            ),
        ),
    )

    /** A UI toolkit that merges each card into one element with one long text (Compose/Flutter). */
    fun mergedCards(): UiNode = n(
        cls = "android.view.View",
        kids = listOf(
            n(
                cls = "android.view.View", collection = true, scrollable = true,
                kids = listOf(
                    n("GF-396, กำลังค้นหาคนขับ..., 2 รายการ", cls = "android.view.View", clickable = true),
                    n("GF-610, เสร็จสมบูรณ์เมื่อ 12:56 PM, 223.00, ล่าช้าไป 4 นาที", cls = "android.view.View", clickable = true),
                ),
            ),
        ),
    )

    /** An order detail page: one order, no list container. */
    fun detailPage(markButton: Boolean = false): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = "3", preparing = "1"),
            box(n("GF-613"), n("กำลังค้นหาคนขับ..."), n("ข้าวกะเพรา x2")),
            button("พร้อมจัดส่ง", marked = markButton),
        ),
    )
}
