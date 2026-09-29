package io.github.panuwattegif.readyproof.core

/** Test trees modelled on real GrabMerchant (Thai) screenshots from the shop's evidence folder. */
object Trees {
    const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"

    fun n(
        text: String? = null,
        cls: String = "android.widget.TextView",
        desc: String? = null,
        clickable: Boolean = false,
        collection: Boolean = false,
        scrollable: Boolean = false,
        marked: Boolean = false,
        id: String? = null,
        kids: List<UiNode> = emptyList(),
    ): UiNode {
        val node = UiNode(
            text = text, desc = desc, viewId = id, className = cls, clickable = clickable,
            scrollable = scrollable, collection = collection, marked = marked,
        )
        kids.forEach { node.add(it) }
        return node
    }

    fun box(vararg kids: UiNode, clickable: Boolean = false) =
        n(cls = "android.widget.LinearLayout", clickable = clickable, kids = kids.toList())

    fun list(vararg kids: UiNode) = n(cls = RECYCLER, collection = true, scrollable = true, kids = kids.toList())

    fun button(text: String, marked: Boolean = false) =
        n(text, cls = "android.widget.Button", clickable = true, marked = marked)

    fun tab(label: String, badge: String?) = n(
        cls = "androidx.appcompat.app.ActionBar.Tab", clickable = true,
        kids = listOfNotNull(n(label), badge?.let { n(it) }),
    )

    private fun header(ready: String?, preparing: String?) = box(
        n("คำสั่งซื้อ"),
        n("เปิดถึง 7:00 หลังเที่ยง"),
        box(
            tab("กำลังเตรียม", preparing), tab("พร้อมจัดส่ง", ready),
            tab("ที่กำลังจะถึง", null), tab("ประวัติ", null),
        ),
    )

    private fun bottomNav() = box(n("หน้าแรก"), n("คำสั่งซื้อ"), n("เมนู"), n("แคชเชียร์"), n("เพิ่มเติม"))

    /** GF-613_READY.jpg: the "พร้อมจัดส่ง" tab at 11:01. */
    fun readyTab(): UiNode = n(
        cls = "android.widget.FrameLayout",
        kids = listOf(
            header(ready = "3", preparing = "1"),
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
