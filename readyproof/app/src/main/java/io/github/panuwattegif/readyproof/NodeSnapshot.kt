package io.github.panuwattegif.readyproof

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.panuwattegif.readyproof.core.UiNode

/** Copies the live accessibility tree into plain [UiNode]s (visible elements only, bounded size). */
object NodeSnapshot {
    private const val MAX_TEXT = 300
    private const val MAX_DEPTH = 80

    fun capture(root: AccessibilityNodeInfo, maxNodes: Int, mark: AccessibilityNodeInfo? = null): UiNode {
        val rect = Rect()
        var count = 0

        fun convert(n: AccessibilityNodeInfo): UiNode {
            count++
            n.getBoundsInScreen(rect)
            return UiNode(
                text = n.text?.toString()?.take(MAX_TEXT),
                desc = n.contentDescription?.toString()?.take(MAX_TEXT),
                viewId = n.viewIdResourceName,
                className = n.className?.toString(),
                clickable = n.isClickable,
                scrollable = n.isScrollable,
                collection = n.collectionInfo != null,
                selected = n.isSelected,
                marked = mark != null && n == mark,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            )
        }

        val rootNode = convert(root)
        val stack = ArrayDeque<Triple<AccessibilityNodeInfo, UiNode, Int>>()
        stack.addLast(Triple(root, rootNode, 0))
        while (stack.isNotEmpty() && count < maxNodes) {
            val (info, node, depth) = stack.removeLast()
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until info.childCount) {
                if (count >= maxNodes) break
                val child = try {
                    info.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (!child.isVisibleToUser) continue
                val converted = convert(child)
                node.add(converted)
                stack.addLast(Triple(child, converted, depth + 1))
            }
        }
        return rootNode
    }
}
