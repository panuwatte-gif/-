package io.github.panuwattegif.readyproof.core

import java.time.LocalTime

/**
 * Every behaviour the app has is driven by this object. Defaults cover GrabMerchant in both
 * English and Thai (tabs "Preparing / Ready / Upcoming / History" =
 * "กำลังเตรียม / พร้อมจัดส่ง / ที่กำลังจะถึง / ประวัติ", order numbers "GF-123", "GF-398F").
 * Adding a setting = add a field here + in [ConfigCodec] + a row in the Settings screen.
 */
data class Config(
    /** Master switch: when false the service stays enabled but does nothing. */
    val enabled: Boolean = true,
    /** Apps whose screens are watched. Nothing outside these packages is ever read. */
    val targetPackages: List<String> = listOf("com.grab.merchant"),
    /** Order number pattern; group 1 (if present) is the number part (letter suffix allowed: GF-398F). */
    val gfPattern: String = """(?<![A-Za-z0-9])GF\s*-\s*(\d{2,5}[A-Za-z]?)(?![A-Za-z0-9])""",
    /** Prefix put in front of group 1 to build the canonical order number. */
    val gfPrefix: String = "GF-",

    /** Labels of the order tabs; used to tell which tab is open and to switch tabs. */
    val tabLabels: List<String> = listOf(
        "Preparing", "Ready", "Upcoming", "History",
        "กำลังเตรียม", "พร้อมจัดส่ง", "ที่กำลังจะถึง", "ประวัติ",
    ),
    /** The tab whose orders are all "done, waiting for the rider": everything there is evidence. */
    val readyTabLabels: List<String> = listOf("Ready", "พร้อมจัดส่ง"),
    /** Orders still being cooked; at closing time they must be gone too before History is read. */
    val preparingTabLabels: List<String> = listOf("Preparing", "กำลังเตรียม"),
    val historyTabLabels: List<String> = listOf("History", "ประวัติ"),
    /** Bottom navigation of the Grab app; "Orders" leads back to the order tabs. */
    val navLabels: List<String> = listOf(
        "Home", "Orders", "Menu", "Cashier", "More",
        "หน้าแรก", "คำสั่งซื้อ", "เมนู", "แคชเชียร์", "เพิ่มเติม",
    ),
    val ordersNavLabels: List<String> = listOf("Orders", "คำสั่งซื้อ"),

    /** Screenshot every order in the Ready tab, once per order. */
    val captureReady: Boolean = true,
    /**
     * Only used when the phone does not report which tab is open: a card with one of these
     * (and none of [readyNone]) counts as being in the Ready tab.
     */
    val readyAny: List<String> = listOf(
        "Finding a driver", "Driver", "กำลังค้นหาคนขับ", "คนขับ", "โปรดเตรียมคำสั่งซื้อนี้",
    ),
    val readyNone: List<String> = listOf("Ready in", "พร้อมจัดส่งใน", "Completed", "เสร็จสมบูรณ์", "Cancelled", "ยกเลิก"),
    /**
     * Status shown when the order first appeared in the Ready tab that means the shop was late:
     * the rider was already there, or Grab asked the shop to get the order ready.
     */
    val lateStatus: List<String> = listOf(
        "มาถึงแล้ว", "arrived", "โปรดเตรียมคำสั่งซื้อนี้", "prepare this order", "get this order ready",
    ),

    /** Screenshot the History list where it shows a delayed order. */
    val captureDelay: Boolean = true,
    val doneAny: List<String> = listOf("Completed", "เสร็จสมบูรณ์"),
    /** Cancelled History rows count toward the day total even though they are never delayed. */
    val cancelAny: List<String> = listOf("Cancelled", "Canceled", "ยกเลิก"),
    val delayAny: List<String> = listOf("Delayed by", "ล่าช้าไป"),
    /** Labels of the totals at the top of History ("Completed 77", "Cancelled 0"). */
    val completedLabels: List<String> = listOf("Completed", "เสร็จสมบูรณ์"),
    val cancelledLabels: List<String> = listOf("Cancelled", "ยกเลิก"),

    /** A READY shot counts for a delayed order if taken within this many hours before it finished. */
    val evidenceWindowHours: Int = 4,

    // ---- the phone dedicated to watching the Ready tab ----
    /** Scroll the Ready list by itself so orders below the screen are photographed too. */
    val autoScroll: Boolean = true,
    /** Re-check the whole Ready list from the top at least this often (minutes). */
    val fullSweepMinutes: Int = 5,
    /** Keep the screen on while the Grab app is open. */
    val keepScreenOn: Boolean = true,
    /**
     * Bring the phone back to Grab's Ready tab when it ends up elsewhere (Grab switched tabs by
     * itself, or someone used the phone and walked away [guardIdleMinutes] ago).
     */
    val guardReadyTab: Boolean = true,
    val guardIdleMinutes: Int = 2,
    /** End of day: after [closeTime] + [endBufferMinutes], once the Ready tab is empty, read History and report. */
    val autoEndOfDay: Boolean = true,
    val closeTime: String = "19:00",
    val endBufferMinutes: Int = 5,
    /** While orders are still waiting in the Ready tab, check again every this many minutes. */
    val recheckMinutes: Int = 5,
    /** Read History anyway after waiting this long for the Ready tab to empty (an order stuck there). */
    val endMaxWaitMinutes: Int = 120,
    /**
     * The shop opens: from here until the end of day the phone is brought back to the Ready tab and
     * a switched-off watcher raises an alarm. Outside these hours the phone is the owner's.
     */
    val openTime: String = "09:00",
    /**
     * After tonight's automatic report the watcher switches itself off, so banking apps (which
     * refuse to run next to an accessibility service) work at night. It is switched on again by hand.
     */
    val autoOffAfterClose: Boolean = true,
    /** New Ready orders arriving together share one photo: wait at most this many seconds for the next one. */
    val readyBatchSeconds: Int = 12,

    val showToast: Boolean = false,
    val jpegQuality: Int = 80,
    /** How far back the photo search looks (evidence is never deleted automatically). */
    val retentionDays: Int = 30,
    /** Keep text dumps of watched screens for troubleshooting. */
    val diagnostics: Boolean = false,
) {
    fun gfExtractor(): GfExtractor = GfExtractor(gfPattern, gfPrefix)

    /** [closeTime] + [endBufferMinutes], or null when the time is not valid. */
    fun endOfDayAt(): LocalTime? = parseTime(closeTime)?.plusMinutes(endBufferMinutes.toLong())

    fun openAt(): LocalTime? = parseTime(openTime)

    /** Between [openTime] and the end of day (both must be valid; otherwise always true). */
    fun inShopHours(t: LocalTime): Boolean {
        val open = openAt() ?: return true
        val end = endOfDayAt() ?: return true
        return if (open.isBefore(end)) !t.isBefore(open) && t.isBefore(end) else !t.isBefore(open) || t.isBefore(end)
    }

    /** Human readable problems; empty = valid. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (!isUsablePattern(gfPattern)) errors += "รูปแบบเลขออเดอร์ไม่ถูกต้อง (ว่าง, ผิดรูปแบบ หรือจับข้อความว่างได้)"
        if (targetPackages.isEmpty()) errors += "ต้องมีแอปเป้าหมายอย่างน้อย 1 แอป"
        if (readyTabLabels.isEmpty()) errors += "ต้องมีชื่อแท็บ Ready อย่างน้อย 1 คำ"
        if (tabLabels.size < 3) errors += "ต้องมีชื่อแท็บทั้งหมดอย่างน้อย 3 คำ (ใช้หาแถบแท็บ)"
        if (preparingTabLabels.isEmpty() && autoEndOfDay) errors += "ต้องมีชื่อแท็บกำลังเตรียมอย่างน้อย 1 คำ (หรือปิดสรุปสิ้นวันอัตโนมัติ)"
        if (historyTabLabels.isEmpty() && autoEndOfDay) errors += "ต้องมีชื่อแท็บ History อย่างน้อย 1 คำ (หรือปิดสรุปสิ้นวันอัตโนมัติ)"
        if (parseTime(closeTime) == null) errors += "เวลาปิดร้านต้องเป็นแบบ 19:00"
        if (parseTime(openTime) == null) errors += "เวลาเปิดร้านต้องเป็นแบบ 09:00"
        if (readyBatchSeconds !in 0..30) errors += "เวลารวบภาพ Ready ต้องอยู่ระหว่าง 0–30 วินาที"
        if (endBufferMinutes !in 0..120) errors += "เวลาเผื่อหลังปิดร้านต้องอยู่ระหว่าง 0–120 นาที"
        if (recheckMinutes !in 1..60) errors += "เวลารอตรวจซ้ำต้องอยู่ระหว่าง 1–60 นาที"
        if (endMaxWaitMinutes !in 5..600) errors += "เวลารอออเดอร์ค้างนานสุดต้องอยู่ระหว่าง 5–600 นาที"
        if (fullSweepMinutes !in 1..60) errors += "รอบกวาดแท็บ Ready ต้องอยู่ระหว่าง 1–60 นาที"
        if (guardIdleMinutes !in 1..60) errors += "เวลารอก่อนพากลับแท็บ Ready ต้องอยู่ระหว่าง 1–60 นาที"
        if (jpegQuality !in 30..100) errors += "คุณภาพภาพต้องอยู่ระหว่าง 30–100"
        if (retentionDays !in 1..365) errors += "จำนวนวันค้นย้อนหลังต้องอยู่ระหว่าง 1–365"
        if (evidenceWindowHours !in 1..24) errors += "ช่วงเวลาจับคู่หลักฐานต้องอยู่ระหว่าง 1–24 ชั่วโมง"
        return errors
    }

    companion object {
        val DEFAULT = Config()

        /** A pattern that compiles and cannot match empty text (which would tag everything). */
        fun isUsablePattern(p: String): Boolean = try {
            p.isNotBlank() && !Regex(p, RegexOption.IGNORE_CASE).containsMatchIn("")
        } catch (e: Exception) {
            false
        }

        /** "19:00" / "7:05" -> time; anything else -> null. */
        fun parseTime(s: String): LocalTime? {
            val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }

        /** Splits a multi-line settings field into clean, unique, non-empty entries. */
        fun lines(text: String): List<String> =
            text.split('\n').map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinct()
    }
}

object ConfigCodec {
    /** Bump when defaults change in a way saved settings must pick up (see [migrate]). */
    const val VERSION = 5

    fun encode(c: Config): String = Json.write(
        linkedMapOf(
            "v" to VERSION,
            "enabled" to c.enabled,
            "targetPackages" to c.targetPackages,
            "gfPattern" to c.gfPattern,
            "gfPrefix" to c.gfPrefix,
            "tabLabels" to c.tabLabels,
            "readyTabLabels" to c.readyTabLabels,
            "preparingTabLabels" to c.preparingTabLabels,
            "historyTabLabels" to c.historyTabLabels,
            "navLabels" to c.navLabels,
            "ordersNavLabels" to c.ordersNavLabels,
            "captureReady" to c.captureReady,
            "readyAny" to c.readyAny,
            "readyNone" to c.readyNone,
            "lateStatus" to c.lateStatus,
            "captureDelay" to c.captureDelay,
            "doneAny" to c.doneAny,
            "cancelAny" to c.cancelAny,
            "delayAny" to c.delayAny,
            "completedLabels" to c.completedLabels,
            "cancelledLabels" to c.cancelledLabels,
            "evidenceWindowHours" to c.evidenceWindowHours,
            "autoScroll" to c.autoScroll,
            "fullSweepMinutes" to c.fullSweepMinutes,
            "keepScreenOn" to c.keepScreenOn,
            "guardReadyTab" to c.guardReadyTab,
            "guardIdleMinutes" to c.guardIdleMinutes,
            "autoEndOfDay" to c.autoEndOfDay,
            "closeTime" to c.closeTime,
            "endBufferMinutes" to c.endBufferMinutes,
            "recheckMinutes" to c.recheckMinutes,
            "endMaxWaitMinutes" to c.endMaxWaitMinutes,
            "openTime" to c.openTime,
            "autoOffAfterClose" to c.autoOffAfterClose,
            "readyBatchSeconds" to c.readyBatchSeconds,
            "showToast" to c.showToast,
            "jpegQuality" to c.jpegQuality,
            "retentionDays" to c.retentionDays,
            "diagnostics" to c.diagnostics,
        )
    )

    /** Unknown keys are ignored and missing keys fall back to defaults, so old saves keep working. */
    fun decode(text: String): Config {
        val m = Json.parseObject(text)
        val d = Config.DEFAULT
        val c = Config(
            enabled = m.bool("enabled") ?: d.enabled,
            targetPackages = m.strList("targetPackages") ?: d.targetPackages,
            gfPattern = m.str("gfPattern") ?: d.gfPattern,
            gfPrefix = m.str("gfPrefix") ?: d.gfPrefix,
            tabLabels = m.strList("tabLabels") ?: d.tabLabels,
            readyTabLabels = m.strList("readyTabLabels") ?: d.readyTabLabels,
            preparingTabLabels = m.strList("preparingTabLabels") ?: d.preparingTabLabels,
            historyTabLabels = m.strList("historyTabLabels") ?: d.historyTabLabels,
            navLabels = m.strList("navLabels") ?: d.navLabels,
            ordersNavLabels = m.strList("ordersNavLabels") ?: d.ordersNavLabels,
            captureReady = m.bool("captureReady") ?: d.captureReady,
            readyAny = m.strList("readyAny") ?: d.readyAny,
            readyNone = m.strList("readyNone") ?: d.readyNone,
            lateStatus = m.strList("lateStatus") ?: d.lateStatus,
            captureDelay = m.bool("captureDelay") ?: d.captureDelay,
            doneAny = m.strList("doneAny") ?: d.doneAny,
            cancelAny = m.strList("cancelAny") ?: d.cancelAny,
            delayAny = m.strList("delayAny") ?: d.delayAny,
            completedLabels = m.strList("completedLabels") ?: d.completedLabels,
            cancelledLabels = m.strList("cancelledLabels") ?: d.cancelledLabels,
            evidenceWindowHours = m.int("evidenceWindowHours") ?: d.evidenceWindowHours,
            autoScroll = m.bool("autoScroll") ?: d.autoScroll,
            fullSweepMinutes = m.int("fullSweepMinutes") ?: d.fullSweepMinutes,
            keepScreenOn = m.bool("keepScreenOn") ?: d.keepScreenOn,
            guardReadyTab = m.bool("guardReadyTab") ?: d.guardReadyTab,
            guardIdleMinutes = m.int("guardIdleMinutes") ?: d.guardIdleMinutes,
            autoEndOfDay = m.bool("autoEndOfDay") ?: d.autoEndOfDay,
            closeTime = m.str("closeTime") ?: d.closeTime,
            endBufferMinutes = m.int("endBufferMinutes") ?: d.endBufferMinutes,
            recheckMinutes = m.int("recheckMinutes") ?: d.recheckMinutes,
            endMaxWaitMinutes = m.int("endMaxWaitMinutes") ?: d.endMaxWaitMinutes,
            openTime = m.str("openTime") ?: d.openTime,
            autoOffAfterClose = m.bool("autoOffAfterClose") ?: d.autoOffAfterClose,
            readyBatchSeconds = m.int("readyBatchSeconds") ?: d.readyBatchSeconds,
            showToast = m.bool("showToast") ?: d.showToast,
            jpegQuality = m.int("jpegQuality") ?: d.jpegQuality,
            retentionDays = m.int("retentionDays") ?: d.retentionDays,
            diagnostics = m.bool("diagnostics") ?: d.diagnostics,
        )
        return migrate(c, m.int("v") ?: 1)
    }

    /**
     * Saved settings from older versions keep what the user typed and gain the newer words:
     * v1 knew only the Thai screens, v3 had no letter-suffixed order numbers (GF-398F), and v4 is
     * the unattended Ready-tab phone (no pop-up messages, which could cover an order number in the
     * next shot); v5 adds opening hours, switching off at night and shared photos for bursts.
     * Settings of the old "Ready button" feature are simply ignored.
     */
    fun migrate(c: Config, from: Int): Config {
        if (from >= VERSION) return c
        val d = Config.DEFAULT
        fun merge(saved: List<String>, defaults: List<String>) = (saved + defaults).distinct()
        return c.copy(
            gfPattern = if (c.gfPattern in OLD_GF_PATTERNS) d.gfPattern else c.gfPattern,
            readyAny = merge(c.readyAny, d.readyAny),
            readyNone = merge(c.readyNone, d.readyNone),
            doneAny = merge(c.doneAny, d.doneAny),
            cancelAny = merge(c.cancelAny, d.cancelAny),
            delayAny = merge(c.delayAny, d.delayAny),
            showToast = if (from < 4) false else c.showToast,
        )
    }

    private val OLD_GF_PATTERNS = setOf("""(?<![A-Za-z0-9])GF\s*-\s*(\d{2,5})(?!\d)""")

    /** Never throws: a corrupt save falls back to defaults instead of breaking the app. */
    fun decodeOrDefault(text: String?): Config {
        if (text.isNullOrBlank()) return Config.DEFAULT
        return try {
            decode(text).sanitized()
        } catch (e: Exception) {
            Config.DEFAULT
        }
    }
}

/** Replaces only the broken parts with defaults, keeping everything else the user set. */
fun Config.sanitized(): Config {
    val d = Config.DEFAULT
    fun List<String>.cleanList() = map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinct()
    return copy(
        targetPackages = targetPackages.cleanList().ifEmpty { d.targetPackages },
        gfPattern = if (Config.isUsablePattern(gfPattern)) gfPattern else d.gfPattern,
        tabLabels = tabLabels.cleanList(),
        readyTabLabels = readyTabLabels.cleanList().ifEmpty { d.readyTabLabels },
        preparingTabLabels = preparingTabLabels.cleanList(),
        historyTabLabels = historyTabLabels.cleanList(),
        navLabels = navLabels.cleanList(),
        ordersNavLabels = ordersNavLabels.cleanList(),
        readyAny = readyAny.cleanList(),
        readyNone = readyNone.cleanList(),
        lateStatus = lateStatus.cleanList(),
        doneAny = doneAny.cleanList(),
        cancelAny = cancelAny.cleanList(),
        delayAny = delayAny.cleanList(),
        completedLabels = completedLabels.cleanList(),
        cancelledLabels = cancelledLabels.cleanList(),
        closeTime = if (Config.parseTime(closeTime) != null) closeTime.trim() else d.closeTime,
        endBufferMinutes = endBufferMinutes.coerceIn(0, 120),
        recheckMinutes = recheckMinutes.coerceIn(1, 60),
        endMaxWaitMinutes = endMaxWaitMinutes.coerceIn(5, 600),
        openTime = if (Config.parseTime(openTime) != null) openTime.trim() else d.openTime,
        readyBatchSeconds = readyBatchSeconds.coerceIn(0, 30),
        fullSweepMinutes = fullSweepMinutes.coerceIn(1, 60),
        guardIdleMinutes = guardIdleMinutes.coerceIn(1, 60),
        jpegQuality = jpegQuality.coerceIn(30, 100),
        retentionDays = retentionDays.coerceIn(1, 365),
        evidenceWindowHours = evidenceWindowHours.coerceIn(1, 24),
    )
}
