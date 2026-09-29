package io.github.panuwattegif.readyproof.core

/**
 * Every behaviour the app has is driven by this object. Defaults match the Thai GrabMerchant
 * screens (tabs "กำลังเตรียม / พร้อมจัดส่ง / ประวัติ", order numbers "GF-123").
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

    /** Screenshot when a button matching [pressTriggers] is tapped. */
    val capturePress: Boolean = true,
    /**
     * Button labels that mean "food is ready". Exact match by default;
     * "text*" = starts with, "*text*" = contains, "id:xyz" = view id contains xyz.
     */
    val pressTriggers: List<String> = listOf("พร้อมจัดส่ง"),
    /** Taps on widgets whose class/role contains these tokens are ignored (the tab "พร้อมจัดส่ง"). */
    val pressExcludeHints: List<String> = listOf("tab", "แท็บ"),
    /** Label of the prep countdown on a card, e.g. "พร้อมจัดส่งใน: 5:53 นาที". */
    val countdownKeywords: List<String> = listOf("พร้อมจัดส่งใน"),

    /** Screenshot when an order card shows a "ready, waiting for / with driver" status. */
    val captureReady: Boolean = true,
    val readyAny: List<String> = listOf(
        "กำลังค้นหาคนขับ", "คนขับจะมารับ", "คนขับกำลังมา", "คนขับมาถึง", "คนขับถึงร้าน",
    ),
    /** A card containing any of these is NOT counted as READY (still preparing / already done). */
    val readyNone: List<String> = listOf("พร้อมจัดส่งใน", "เสร็จสมบูรณ์"),
    /** The same order is photographed as READY again only after this many minutes. */
    val readyRepeatMinutes: Int = 90,

    /** Screenshot when the history list shows a delayed order. */
    val captureDelay: Boolean = true,
    val doneAny: List<String> = listOf("เสร็จสมบูรณ์"),
    val delayAny: List<String> = listOf("ล่าช้าไป"),

    /** Evidence counts for a delayed order if taken within this many hours before it finished. */
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
        if (pressTriggers.isEmpty() && capturePress) errors += "ต้องมีคำของปุ่มอย่างน้อย 1 คำ (หรือปิดการแคปตอนกดปุ่ม)"
        if (readyAny.isEmpty() && captureReady) errors += "ต้องมีคำสถานะ READY อย่างน้อย 1 คำ (หรือปิดการแคป READY)"
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
    fun encode(c: Config): String = Json.write(
        linkedMapOf(
            "v" to 1,
            "enabled" to c.enabled,
            "targetPackages" to c.targetPackages,
            "gfPattern" to c.gfPattern,
            "gfPrefix" to c.gfPrefix,
            "capturePress" to c.capturePress,
            "pressTriggers" to c.pressTriggers,
            "pressExcludeHints" to c.pressExcludeHints,
            "countdownKeywords" to c.countdownKeywords,
            "captureReady" to c.captureReady,
            "readyAny" to c.readyAny,
            "readyNone" to c.readyNone,
            "readyRepeatMinutes" to c.readyRepeatMinutes,
            "captureDelay" to c.captureDelay,
            "doneAny" to c.doneAny,
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
        return Config(
            enabled = m.bool("enabled") ?: d.enabled,
            targetPackages = m.strList("targetPackages") ?: d.targetPackages,
            gfPattern = m.str("gfPattern") ?: d.gfPattern,
            gfPrefix = m.str("gfPrefix") ?: d.gfPrefix,
            capturePress = m.bool("capturePress") ?: d.capturePress,
            pressTriggers = m.strList("pressTriggers") ?: d.pressTriggers,
            pressExcludeHints = m.strList("pressExcludeHints") ?: d.pressExcludeHints,
            countdownKeywords = m.strList("countdownKeywords") ?: d.countdownKeywords,
            captureReady = m.bool("captureReady") ?: d.captureReady,
            readyAny = m.strList("readyAny") ?: d.readyAny,
            readyNone = m.strList("readyNone") ?: d.readyNone,
            readyRepeatMinutes = m.int("readyRepeatMinutes") ?: d.readyRepeatMinutes,
            captureDelay = m.bool("captureDelay") ?: d.captureDelay,
            doneAny = m.strList("doneAny") ?: d.doneAny,
            delayAny = m.strList("delayAny") ?: d.delayAny,
            evidenceWindowHours = m.int("evidenceWindowHours") ?: d.evidenceWindowHours,
            showToast = m.bool("showToast") ?: d.showToast,
            jpegQuality = m.int("jpegQuality") ?: d.jpegQuality,
            retentionDays = m.int("retentionDays") ?: d.retentionDays,
            diagnostics = m.bool("diagnostics") ?: d.diagnostics,
        )
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
        pressTriggers = pressTriggers.cleanList(),
        pressExcludeHints = pressExcludeHints.cleanList(),
        countdownKeywords = countdownKeywords.cleanList(),
        readyAny = readyAny.cleanList(),
        readyNone = readyNone.cleanList(),
        doneAny = doneAny.cleanList(),
        delayAny = delayAny.cleanList(),
        jpegQuality = jpegQuality.coerceIn(30, 100),
        retentionDays = retentionDays.coerceIn(1, 365),
        readyRepeatMinutes = readyRepeatMinutes.coerceIn(1, 1440),
        evidenceWindowHours = evidenceWindowHours.coerceIn(1, 24),
    )
}
