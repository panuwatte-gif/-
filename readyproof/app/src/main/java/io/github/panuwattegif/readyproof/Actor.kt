package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.panuwattegif.readyproof.core.UiNode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The only things ReadyProof ever does inside Grab: scroll the order list, tap a tab of the tab
 * bar / the "Orders" item of the bottom bar, and press Back on a sub-page. It never taps an order
 * card or any of its buttons (the caller only passes tab-bar elements from TabDetector).
 */
class Actor(private val service: AccessibilityService) {

    private fun live(n: UiNode?): AccessibilityNodeInfo? {
        val info = n?.ref as? AccessibilityNodeInfo ?: return null
        return try {
            if (info.refresh()) info else null
        } catch (e: Exception) {
            null
        }
    }

    fun click(n: UiNode): Boolean = live(n)?.let { safely { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } } ?: false

    /** One page down ([forward]) or up. */
    fun scroll(n: UiNode?, forward: Boolean): Boolean {
        val info = live(n) ?: return false
        val primary = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (safely { info.performAction(primary) }) return true
        val alt = if (forward) AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityAction.ACTION_SCROLL_UP
        return safely { info.performAction(alt.id) }
    }

    /** Asks the list to scroll just enough to show [n] completely. */
    fun showOnScreen(n: UiNode): Boolean = live(n)?.let { safely { it.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) } } ?: false

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun launch(pkg: String): Boolean = try {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            service.startActivity(intent)
            true
        }
    } catch (e: Exception) {
        Diagnostics.error(service, "launch", e)
        false
    }

    /**
     * Drags the list by [dy] pixels (negative = content moves up, showing what is below) with a
     * slow finger that rests before lifting, so the list does not keep sliding (no fling).
     */
    suspend fun drag(x: Float, fromY: Float, dy: Float): Boolean {
        val slop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        val dist = abs(dy) + slop
        val toY = fromY + if (dy < 0) -dist else dist
        val moveMs = (dist / 0.5f).toLong().coerceIn(300, 1500)
        return try {
            val move = GestureDescription.StrokeDescription(Path().apply { moveTo(x, fromY); lineTo(x, toY) }, 0, moveMs, true)
            val rest = move.continueStroke(Path().apply { moveTo(x, toY); lineTo(x + 1f, toY) }, 0, 400, false)
            dispatch(move) && dispatch(rest)
        } catch (e: Exception) {
            Diagnostics.error(service, "drag", e)
            false
        }
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = service.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        false
    }
}
