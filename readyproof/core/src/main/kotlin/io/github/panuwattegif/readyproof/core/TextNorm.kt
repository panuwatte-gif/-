package io.github.panuwattegif.readyproof.core

import java.text.Normalizer

/**
 * Text clean-up shared by every matcher, so a keyword typed in Settings matches
 * what the target app puts on screen even when the two use different Unicode forms.
 */
object TextNorm {
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
    private val SPACES = Regex("\\s+")

    /** NFC, Thai sara-am composed, zero-width chars removed, whitespace collapsed, trimmed. */
    fun clean(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        var t = Normalizer.normalize(s, Normalizer.Form.NFC)
        // "ํ" + "า" (how some fonts/OCR spell it) -> "ำ"
        t = t.replace("ํา", "ำ")
        t = ZERO_WIDTH.replace(t, "")
        t = SPACES.replace(t, " ").trim()
        return t
    }

    /** [clean] + lowercase: the form used for comparisons. */
    fun key(s: String?): String = clean(s).lowercase()

    /** True when [text] contains any non-blank [keywords] (both compared by [key]). */
    fun containsAny(text: String?, keywords: List<String>): Boolean {
        val k = key(text)
        if (k.isEmpty()) return false
        return keywords.any { kw -> key(kw).let { it.isNotEmpty() && k.contains(it) } }
    }

    /** Index of the first [texts] entry that contains any of [keywords], or -1. */
    fun indexOfAny(texts: List<String>, keywords: List<String>): Int =
        texts.indexOfFirst { containsAny(it, keywords) }
}
