package io.github.panuwattegif.readyproofclean.core

/**
 * Plain copy of one on-screen element. The Android layer converts the live accessibility tree
 * into these so all screen logic stays testable without a phone.
 */
class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    /** The element is a list/grid (RecyclerView, LazyColumn ...). */
    val collection: Boolean = false,
    val selected: Boolean = false,
    /** Set on the element that was tapped, so its order card can be found. */
    val marked: Boolean = false,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    private val _children = ArrayList<UiNode>()
    val children: List<UiNode> get() = _children
    var parent: UiNode? = null
        private set

    fun add(child: UiNode): UiNode {
        child.parent = this
        _children += child
        return this
    }

    fun addAll(vararg kids: UiNode): UiNode {
        kids.forEach { add(it) }
        return this
    }

    /** Text then content description (when it adds something), cleaned, non-empty. */
    fun ownStrings(): List<String> {
        val t = TextNorm.clean(text)
        val d = TextNorm.clean(desc)
        return when {
            t.isEmpty() && d.isEmpty() -> emptyList()
            t.isEmpty() -> listOf(d)
            d.isEmpty() || d == t || t.contains(d) -> listOf(t)
            else -> listOf(t, d)
        }
    }

    /** Pre-order walk (self first), iterative so deep trees cannot overflow the stack. */
    fun walk(): Sequence<UiNode> = sequence {
        val stack = ArrayDeque<UiNode>()
        stack.addLast(this@UiNode)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    /** Own strings of this subtree in reading order, consecutive duplicates removed. */
    fun collectTexts(limit: Int = 60): List<String> {
        val out = ArrayList<String>()
        for (n in walk()) {
            for (s in n.ownStrings()) {
                if (out.lastOrNull() != s) out += s
                if (out.size >= limit) return out
            }
        }
        return out
    }
}

/** Indented text dump of a tree, used by the troubleshooting export. */
object TreeDump {
    fun dump(root: UiNode, maxLines: Int = 1500): String {
        val sb = StringBuilder()
        var lines = 0
        fun visit(n: UiNode, depth: Int) {
            if (lines >= maxLines) return
            lines++
            sb.append("  ".repeat(depth.coerceAtMost(40)))
            sb.append('[').append(n.className?.substringAfterLast('.') ?: "?")
            n.viewId?.let { sb.append(" #").append(it.substringAfter(":id/")) }
            sb.append(']')
            val flags = buildString {
                if (n.clickable) append('C')
                if (n.scrollable) append('S')
                if (n.collection) append('L')
                if (n.selected) append('*')
                if (n.marked) append('M')
            }
            if (flags.isNotEmpty()) sb.append('{').append(flags).append('}')
            n.text?.let { sb.append(" \"").append(TextNorm.clean(it)).append('"') }
            n.desc?.let { sb.append(" (").append(TextNorm.clean(it)).append(')') }
            sb.append(" @").append(n.top).append('-').append(n.bottom).append('\n')
            for (c in n.children) visit(c, depth + 1)
        }
        visit(root, 0)
        if (lines >= maxLines) sb.append("... (truncated)\n")
        return sb.toString()
    }
}
