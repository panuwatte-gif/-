package io.github.panuwattegif.readyproof

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.panuwattegif.readyproof.core.UiNode

/**
 * Copies the live accessibility tree into plain [UiNode]s (bounded size). Each copy keeps a
 * reference to its live element so the list can be scrolled and a tab tapped.
 * Elements that are not on screen are copied only a few levels deep (enough for an off-screen
 * tab label) and are flagged hidden, so they never count as orders on screen.
 */
object NodeSnapshot {
    private const val MAX_TEXT = 300
    private const val MAX_DEPTH = 80
    private const val MAX_HIDDEN_DEPTH = 3

    private val FORWARD = setOf(AccessibilityAction.ACTION_SCROLL_FORWARD.id, AccessibilityAction.ACTION_SCROLL_DOWN.id)
    private val BACKWARD = setOf(AccessibilityAction.ACTION_SCROLL_BACKWARD.id, AccessibilityAction.ACTION_SCROLL_UP.id)
    private val SIDEWAYS = setOf(
        AccessibilityAction.ACTION_SCROLL_LEFT.id, AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        AccessibilityAction.ACTION_PAGE_LEFT.id, AccessibilityAction.ACTION_PAGE_RIGHT.id,
    )
    private val UPDOWN = setOf(
        AccessibilityAction.ACTION_SCROLL_UP.id, AccessibilityAction.ACTION_SCROLL_DOWN.id,
        AccessibilityAction.ACTION_PAGE_UP.id, AccessibilityAction.ACTION_PAGE_DOWN.id,
    )
    private val SIDEWAYS_CLASS = Regex("ViewPager|HorizontalScrollView|TabLayout|HorizontalPager", RegexOption.IGNORE_CASE)

    fun capture(root: AccessibilityNodeInfo, maxNodes: Int): UiNode {
        val rect = Rect()
        var count = 0

        fun convert(n: AccessibilityNodeInfo, shown: Boolean): UiNode {
            count++
            n.getBoundsInScreen(rect)
            val actions = n.actionList.map { it.id }
            val ci = n.collectionInfo
            val cls = n.className?.toString()
            val horizontal = (ci != null && ci.rowCount == 1 && ci.columnCount > 1) ||
                (actions.any { it in SIDEWAYS } && actions.none { it in UPDOWN }) ||
                (cls != null && SIDEWAYS_CLASS.containsMatchIn(cls))
            return UiNode(
                text = n.text?.toString()?.take(MAX_TEXT),
                desc = n.contentDescription?.toString()?.take(MAX_TEXT),
                viewId = n.viewIdResourceName,
                className = n.className?.toString(),
                clickable = n.isClickable,
                scrollable = n.isScrollable,
                collection = n.collectionInfo != null,
                selected = n.isSelected,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                canScrollForward = actions.any { it in FORWARD },
                canScrollBackward = actions.any { it in BACKWARD },
                shown = shown,
                horizontal = horizontal,
            ).also { it.ref = n }
        }

        class Step(val info: AccessibilityNodeInfo, val node: UiNode, val depth: Int, val hiddenDepth: Int)

        val rootNode = convert(root, true)
        val stack = ArrayDeque<Step>()
        stack.addLast(Step(root, rootNode, 0, 0))
        while (stack.isNotEmpty() && count < maxNodes) {
            val step = stack.removeLast()
            if (step.depth >= MAX_DEPTH) continue
            for (i in 0 until step.info.childCount) {
                if (count >= maxNodes) break
                val child = try {
                    step.info.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                val visible = child.isVisibleToUser
                val hiddenDepth = if (visible && step.hiddenDepth == 0) 0 else step.hiddenDepth + 1
                if (hiddenDepth > MAX_HIDDEN_DEPTH) continue
                val converted = convert(child, hiddenDepth == 0)
                step.node.add(converted)
                stack.addLast(Step(child, converted, step.depth + 1, hiddenDepth))
            }
        }
        return rootNode
    }
}
