package io.github.panuwattegif.readyproof.core

/** Finds order numbers ("GF-613") in on-screen text and returns them in canonical form. */
class GfExtractor(pattern: String, private val prefix: String) {
    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Distinct order numbers in [text], in order of appearance. */
    fun extract(text: String?): List<String> {
        val t = TextNorm.clean(text)
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in regex.findAll(t)) {
            val number = m.groupValues.getOrNull(1).orEmpty()
            out += if (number.isNotEmpty()) prefix + number else m.value.replace(" ", "").uppercase()
        }
        return out.toList()
    }
}
