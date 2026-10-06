package io.github.panuwattegif.readyproof.core

/**
 * Every behaviour the app has is driven by this object. Defaults cover GrabMerchant in both
 * English and Thai (tabs "Preparing / Ready / Upcoming / History" =
 * "กำลังเตรียม / พร้อมจัดส่ง / ที่กำลังจะถึง / ประวัติ", order numbers "GF-123").
 * Adding a setting = add a field here + in [ConfigCodec] + a row in the Settings screen.
 */
data class Config(
    /** Master switch: when false the service stays enabled but does nothing. */
    val enabled: Boolean = true,
    /** Apps whose screens are watched. Nothing outside these packages is ever read. */
    val targetPackages: List<String> = listOf("com.grab.merchant"),
    /** Order number pattern; group 1 (if present) is the number part. */
    val gfPattern: String = """(?<![A-Za-z0-9])GF\s*-\s*(\d{2,5})(?!\d)""",
    /** Prefix put in front of group 1 to build the canonical order number. */
    val gfPrefix: String = "GF-",

    /** Labels of the order tabs; used to tell which tab is open. */
    val tabLabels: List<String> = listOf(
        "Preparing", "Ready", "Upcoming", "History",
        "กำลังเตรียม", "พร้อมจัดส่ง", "ที่กำลังจะถึง", "ประวัติ",
    ),
    /** The tab whose orders are all "pressed ready": everything shown there is evidence. */
    val readyTabLabels: List<String> = listOf("Ready", "พร้อมจัดส่ง"),

    /** Screenshot orders shown in the Ready tab (once per order). */
    val captureReady: Boolean = true,
    /**
     * Only used when the app does not report which tab is open: a card with one of these
     * (and none of [readyNone]) counts as being in the Ready tab.
     */
    val readyAny: List<String> = listOf("Finding a driver", "Driver", "กำลังค้นหาคนขับ", "คนขับ"),
    val readyNone: List<String> = listOf("Ready in", "พร้อมจัดส่งใน", "Completed", "เสร็จสมบูรณ์", "Cancelled", "ยกเลิก"),
    /** The same order is photographed as READY again only after this many minutes. */
    val readyRepeatMinutes: Int = 90,

    /**
     * Button labels that mean "food is ready". Exact match by default;
     * "text*" = starts with, "*text*" = contains, "id:xyz" = view id contains xyz.
     */
    val pressTriggers: List<String> = listOf("Ready", "พร้อมจัดส่ง"),
    /** Taps on widgets whose class/role contains these tokens are ignored (the tab of the same name). */
    val pressExcludeHints: List<String> = listOf("tab", "แท็บ"),
    /** Label of the prep countdown on a card, e.g. "Ready in: 9:32 min". */
    val countdownKeywords: List<String> = listOf("Ready in", "พร้อมจัดส่งใน"),
    /** After the button is tapped, remind to open the Ready tab if no READY shot follows. */
    val remindReadyTab: Boolean = true,
    /** Also screenshot the moment the button is tapped (not needed as evidence). */
    val capturePress: Boolean = false,

    /** Screenshot the history list when it shows a delayed order. */
    val captureDelay: Boolean = true,
    val doneAny: List<String> = listOf("Completed", "เสร็จสมบูรณ์"),
    /** Cancelled History rows still count toward the day total even though they are not delayed. */
    val cancelAny: List<String> = listOf("Cancelled", "Canceled", "ยกเลิก"),
    val delayAny: List<String> = listOf("Delayed by", "ล่าช้าไป"),

    /** A READY shot counts for a delayed order if taken within this many hours before it finished. */
    val evidenceWindowHours: Int = 4,

    val showToast: Boolean = true,
    val jpegQuality: Int = 80,
    /** Screenshots older than this are deleted automatically. */
    val retentionDays: Int = 30,
    /** Keep text dumps of watched screens for troubleshooting. */
    val diagnostics: Boolean = false,
) {
    fun gfExtractor(): GfExtractor = GfExtractor(gfPattern, gfPrefix)

    /** Human readable problems; empty = valid. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (!isUsablePattern(gfPattern)) errors += "รูปแบบเลขออเดอร์ไม่ถูกต้อง (ว่าง, ผิดรูปแบบ หรือจับข้อความว่างได้)"
        if (targetPackages.isEmpty()) errors += "ต้องมีแอปเป้าหมายอย่างน้อย 1 แอป"
        if (readyTabLabels.isEmpty() && captureReady) errors += "ต้องมีชื่อแท็บ Ready อย่างน้อย 1 คำ (หรือปิดการแคปแท็บ Ready)"
        if (pressTriggers.isEmpty() && (capturePress || remindReadyTab)) errors += "ต้องมีคำบนปุ่มอย่างน้อย 1 คำ (หรือปิดการเตือน/การแคปตอนกดปุ่ม)"
        if (jpegQuality !in 30..100) errors += "คุณภาพภาพต้องอยู่ระหว่าง 30–100"
        if (retentionDays !in 1..365) errors += "จำนวนวันที่เก็บภาพต้องอยู่ระหว่าง 1–365"
        if (readyRepeatMinutes !in 1..1440) errors += "เวลาแคป READY ซ้ำต้องอยู่ระหว่าง 1–1440 นาที"
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

        /** Splits a multi-line settings field into clean, unique, non-empty entries. */
        fun lines(text: String): List<String> =
            text.split('\n').map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinct()
    }
}

object ConfigCodec {
    /** Bump when defaults change in a way saved settings must pick up (see [migrate]). */
    const val VERSION = 3

    fun encode(c: Config): String = Json.write(
        linkedMapOf(
            "v" to VERSION,
            "enabled" to c.enabled,
            "targetPackages" to c.targetPackages,
            "gfPattern" to c.gfPattern,
            "gfPrefix" to c.gfPrefix,
            "tabLabels" to c.tabLabels,
            "readyTabLabels" to c.readyTabLabels,
            "captureReady" to c.captureReady,
            "readyAny" to c.readyAny,
            "readyNone" to c.readyNone,
            "readyRepeatMinutes" to c.readyRepeatMinutes,
            "pressTriggers" to c.pressTriggers,
            "pressExcludeHints" to c.pressExcludeHints,
            "countdownKeywords" to c.countdownKeywords,
            "remindReadyTab" to c.remindReadyTab,
            "capturePress" to c.capturePress,
            "captureDelay" to c.captureDelay,
            "doneAny" to c.doneAny,
            "cancelAny" to c.cancelAny,
            "delayAny" to c.delayAny,
            "evidenceWindowHours" to c.evidenceWindowHours,
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
            captureReady = m.bool("captureReady") ?: d.captureReady,
            readyAny = m.strList("readyAny") ?: d.readyAny,
            readyNone = m.strList("readyNone") ?: d.readyNone,
            readyRepeatMinutes = m.int("readyRepeatMinutes") ?: d.readyRepeatMinutes,
            pressTriggers = m.strList("pressTriggers") ?: d.pressTriggers,
            pressExcludeHints = m.strList("pressExcludeHints") ?: d.pressExcludeHints,
            countdownKeywords = m.strList("countdownKeywords") ?: d.countdownKeywords,
            remindReadyTab = m.bool("remindReadyTab") ?: d.remindReadyTab,
            capturePress = m.bool("capturePress") ?: d.capturePress,
            captureDelay = m.bool("captureDelay") ?: d.captureDelay,
            doneAny = m.strList("doneAny") ?: d.doneAny,
            cancelAny = m.strList("cancelAny") ?: d.cancelAny,
            delayAny = m.strList("delayAny") ?: d.delayAny,
            evidenceWindowHours = m.int("evidenceWindowHours") ?: d.evidenceWindowHours,
            showToast = m.bool("showToast") ?: d.showToast,
            jpegQuality = m.int("jpegQuality") ?: d.jpegQuality,
            retentionDays = m.int("retentionDays") ?: d.retentionDays,
            diagnostics = m.bool("diagnostics") ?: d.diagnostics,
        )
        return migrate(c, m.int("v") ?: 1)
    }

    /**
     * v1 only knew the Thai screens and screenshotted every button tap. Saved v1 settings get the
     * English words added (nothing the user typed is removed) and the tap screenshot turned off.
     */
    fun migrate(c: Config, from: Int): Config {
        val d = Config.DEFAULT
        fun merge(saved: List<String>, defaults: List<String>) = (saved + defaults).distinct()
        var out = c
        if (from < 2) {
            out = out.copy(
                pressTriggers = merge(out.pressTriggers, d.pressTriggers),
                countdownKeywords = merge(out.countdownKeywords, d.countdownKeywords),
                readyAny = merge(out.readyAny, d.readyAny),
                readyNone = merge(out.readyNone, d.readyNone),
                doneAny = merge(out.doneAny, d.doneAny),
                delayAny = merge(out.delayAny, d.delayAny),
                capturePress = false,
            )
        }
        if (from < 3) {
            out = out.copy(cancelAny = merge(out.cancelAny, d.cancelAny))
        }
        return out
    }

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
        readyTabLabels = readyTabLabels.cleanList(),
        readyAny = readyAny.cleanList(),
        readyNone = readyNone.cleanList(),
        pressTriggers = pressTriggers.cleanList(),
        pressExcludeHints = pressExcludeHints.cleanList(),
        countdownKeywords = countdownKeywords.cleanList(),
        doneAny = doneAny.cleanList(),
        cancelAny = cancelAny.cleanList(),
        delayAny = delayAny.cleanList(),
        jpegQuality = jpegQuality.coerceIn(30, 100),
        retentionDays = retentionDays.coerceIn(1, 365),
        readyRepeatMinutes = readyRepeatMinutes.coerceIn(1, 1440),
        evidenceWindowHours = evidenceWindowHours.coerceIn(1, 24),
    )
}
