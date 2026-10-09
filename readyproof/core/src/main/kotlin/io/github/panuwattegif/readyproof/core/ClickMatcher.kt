package io.github.panuwattegif.readyproof.core

/** What the accessibility event told us about one tap (shown in the troubleshooting export). */
data class ClickInfo(
    /** Text of the tapped element itself. */
    val ownText: String? = null,
    val desc: String? = null,
    /** Texts Android attached to the event (a container reports its children's texts). */
    val eventTexts: List<String> = emptyList(),
    val className: String? = null,
    val viewId: String? = null,
    val roleDesc: String? = null,
) {
    /** Best human label for the tap. */
    fun label(): String =
        candidates().firstOrNull()
            ?: eventTexts.map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.joinToString(" | ").take(80)

    /**
     * Labels that identify the tapped element. A container's texts are only used when there is
     * exactly one, so tapping a whole order card (many texts) never reads as tapping its button.
     */
    fun candidates(): List<String> {
        val own = listOfNotNull(ownText, desc).map { TextNorm.clean(it) }.filter { it.isNotEmpty() }
        if (own.isNotEmpty()) return own.distinct()
        val ev = eventTexts.map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinct()
        return if (ev.size == 1) ev else emptyList()
    }
}
