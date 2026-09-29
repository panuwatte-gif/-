package io.github.panuwattegif.readyproof.core

import java.time.LocalTime

/** Small parsers for the strings GrabMerchant shows (Thai, with AM/PM or 24h clocks). */
object Parsers {
    // Digits around the clock are excluded so prices like "223.00" are not read as 23:00.
    private val CLOCK = Regex(
        """(?<!\d)(\d{1,2})\s*[:.]\s*(\d{2})(?!\d)\s*(a\.?m\.?(?![a-z])|p\.?m\.?(?![a-z])|น\.?|หลังเที่ยง|ก่อนเที่ยง)?""",
        RegexOption.IGNORE_CASE,
    )
    // No \b after Thai units: Java treats Thai letters as non-word characters.
    private val DURATION = Regex(
        """^\s*(?:(\d+)\s*(?:ชั่วโมง|ชม\.?|(?:hours?|hrs?|h)(?![a-z])))?\s*(?:(\d+)\s*(?:นาที|(?:minutes?|mins?|m)(?![a-z])))?""",
        RegexOption.IGNORE_CASE,
    )
    private val COUNTDOWN = Regex("""^\s*:?\s*(-?\d{1,3}:\d{2})""")

    /** Text after the first keyword found in [text] (compared case-insensitively), or null. */
    fun after(text: String, keywords: List<String>): String? {
        val t = TextNorm.clean(text)
        val lower = t.lowercase()
        for (kw in keywords) {
            val k = TextNorm.key(kw)
            if (k.isEmpty()) continue
            val i = lower.indexOf(k)
            if (i >= 0) return t.substring(i + k.length)
        }
        return null
    }

    /** First clock time in [text]: "12:56 PM" -> 12:56, "7:28 PM" -> 19:28, "19:28 น." -> 19:28. */
    fun clock(text: String?): LocalTime? {
        val m = CLOCK.find(TextNorm.clean(text)) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val suffix = m.groupValues[3].lowercase().replace(".", "")
        if (min > 59) return null
        when {
            suffix == "pm" || suffix == "หลังเที่ยง" -> {
                if (h !in 1..12) return null
                if (h != 12) h += 12
            }
            suffix == "am" || suffix == "ก่อนเที่ยง" -> {
                if (h !in 1..12) return null
                if (h == 12) h = 0
            }
            else -> if (h > 23) return null
        }
        return LocalTime.of(h, min)
    }

    /** "12:56" style (24h) for storage; built by hand so device locale digits never leak in. */
    fun hhmm(t: LocalTime): String = pad2(t.hour) + ":" + pad2(t.minute)

    fun pad2(n: Int): String = n.toString().padStart(2, '0')

    /** Finish time from a history line such as "เสร็จสมบูรณ์เมื่อ 12:56 PM". */
    fun doneAt(text: String, doneKeywords: List<String>): String? =
        after(text, doneKeywords)?.let { clock(it) }?.let { hhmm(it) }

    /** Minutes from "ล่าช้าไป 4 นาที" / "ล่าช้าไป 1 ชม. 5 นาที"; null when no number is shown. */
    fun delayMinutes(text: String, delayKeywords: List<String>): Int? {
        val rest = after(text, delayKeywords) ?: return null
        val m = DURATION.find(rest) ?: return null
        val h = m.groupValues[1].toIntOrNull()
        val min = m.groupValues[2].toIntOrNull()
        if (h == null && min == null) return null
        return (h ?: 0) * 60 + (min ?: 0)
    }

    /** "5:53" from "พร้อมจัดส่งใน: 5:53 นาที". */
    fun countdown(text: String, keywords: List<String>): String? {
        val rest = after(text, keywords) ?: return null
        return COUNTDOWN.find(rest)?.groupValues?.get(1)
    }
}

/** Turns one order card into observations according to the [Config] keyword lists. */
class StatusRules(private val cfg: Config) {

    fun evaluate(card: Card): List<Item> {
        val texts = card.texts
        val out = ArrayList<Item>(2)

        val readyIdx = TextNorm.indexOfAny(texts, cfg.readyAny)
        val readyBlocked = TextNorm.indexOfAny(texts, cfg.readyNone) >= 0
        if (readyIdx >= 0 && !readyBlocked) {
            out += Item(card.gf, ObsType.READY, status = texts[readyIdx], card = texts)
        }

        val doneIdx = TextNorm.indexOfAny(texts, cfg.doneAny)
        if (doneIdx >= 0) {
            val doneAt = Parsers.doneAt(texts[doneIdx], cfg.doneAny)
                ?: texts.firstNotNullOfOrNull { Parsers.doneAt(it, cfg.doneAny) }
            val delayIdx = TextNorm.indexOfAny(texts, cfg.delayAny)
            val delayMin = if (delayIdx >= 0) Parsers.delayMinutes(texts[delayIdx], cfg.delayAny) else null
            // Grab's own delay flag only counts on finished orders (history rows).
            if (delayIdx >= 0) {
                out += Item(card.gf, ObsType.DELAY, status = texts[delayIdx], delayMin = delayMin, doneAt = doneAt, card = texts)
            }
            out += Item(card.gf, ObsType.DONE, status = texts[doneIdx], delayMin = delayMin, doneAt = doneAt, card = texts)
        }
        return out
    }

    fun countdownOf(texts: List<String>): String? =
        texts.firstNotNullOfOrNull { Parsers.countdown(it, cfg.countdownKeywords) }
}
