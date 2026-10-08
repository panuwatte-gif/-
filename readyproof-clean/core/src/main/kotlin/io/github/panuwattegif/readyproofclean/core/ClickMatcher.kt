package io.github.panuwattegif.readyproofclean.core

/** What the accessibility event told us about one tap. */
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
    /** Best human label for the Settings "recent taps" list. */
    fun label(): String =
        ClickMatcher.candidates(this).firstOrNull()
            ?: eventTexts.map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.joinToString(" | ").take(80)
}

object ClickMatcher {
    /**
     * Labels that identify the tapped element. A container's texts are only used when there is
     * exactly one, so tapping a whole order card (many texts) never counts as tapping its button.
     */
    fun candidates(info: ClickInfo): List<String> {
        val own = listOfNotNull(info.ownText, info.desc).map { TextNorm.clean(it) }.filter { it.isNotEmpty() }
        if (own.isNotEmpty()) return own.distinct()
        val ev = info.eventTexts.map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.distinct()
        return if (ev.size == 1) ev else emptyList()
    }

    /** Tabs share the button's label ("พร้อมจัดส่ง 3"), so widgets that look like tabs are skipped. */
    fun excluded(info: ClickInfo, cfg: Config): Boolean = cfg.pressExcludeHints.any { hint ->
        classHasToken(info.className, hint) || TextNorm.containsAny(info.roleDesc, listOf(hint))
    }

    fun matches(info: ClickInfo, cfg: Config): Boolean {
        if (excluded(info, cfg)) return false
        val cands = candidates(info).map { it.lowercase() }
        val viewId = TextNorm.key(info.viewId)
        return cfg.pressTriggers.any { matchTrigger(it, cands, viewId) }
    }

    /** "text" exact, "text*" starts with, "*text" ends with, "*text*" contains, "id:x" view id contains x. */
    fun matchTrigger(trigger: String, candidatesLower: List<String>, viewIdLower: String): Boolean {
        val t = TextNorm.clean(trigger)
        if (t.isEmpty()) return false
        if (t.startsWith("id:", ignoreCase = true)) {
            val want = TextNorm.key(t.substring(3))
            return want.isNotEmpty() && viewIdLower.contains(want)
        }
        val k = t.lowercase()
        val starts = k.startsWith("*")
        val ends = k.endsWith("*")
        val core = k.trim('*').trim()
        if (core.isEmpty()) return false
        return candidatesLower.any { c ->
            when {
                starts && ends -> c.contains(core)
                ends -> c.startsWith(core)
                starts -> c.endsWith(core)
                else -> c == core
            }
        }
    }

    /**
     * True when a class-name segment is [token] or starts with it at a camel-case boundary:
     * "ActionBar.Tab", "TabView", "TabWidget" match "tab"; "TableButton" does not.
     */
    fun classHasToken(className: String?, token: String): Boolean {
        val tok = token.trim()
        if (className.isNullOrEmpty() || tok.isEmpty()) return false
        return className.split('.', '$').any { seg ->
            if (seg.equals(tok, ignoreCase = true)) return@any true
            if (seg.length > tok.length && seg.startsWith(tok, ignoreCase = true)) seg[tok.length].isUpperCase() else false
        }
    }
}
