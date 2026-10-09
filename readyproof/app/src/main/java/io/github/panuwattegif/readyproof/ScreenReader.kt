package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.panuwattegif.readyproof.core.Box
import io.github.panuwattegif.readyproof.core.Config
import io.github.panuwattegif.readyproof.core.ScanContext
import io.github.panuwattegif.readyproof.core.ScreenAnalysis
import io.github.panuwattegif.readyproof.core.ScreenAnalyzer
import io.github.panuwattegif.readyproof.core.UiNode

/** What is on screen right now, as far as the Grab app is concerned. */
class Screen(
    val roots: List<UiNode>,
    val a: ScreenAnalysis,
    /** Grab shows a dialog / pop-up on top of its main screen: never tap anything then. */
    val dialog: Boolean,
    val at: Long = System.currentTimeMillis(),
)

/** Which apps are on screen (only window types and app names are looked at, never other apps' content). */
class WindowsState(
    val grabRoots: List<AccessibilityNodeInfo>,
    /** Areas of windows drawn on top of Grab (other apps, system pop-ups, Grab's own dialogs). */
    val occluders: List<Box>,
    val dialog: Boolean,
    val ownAppVisible: Boolean,
) {
    val grabVisible: Boolean get() = grabRoots.isNotEmpty()
}

class ScreenReader(private val service: AccessibilityService) {
    companion object {
        private const val MAX_NODES = 2500
    }

    fun windows(cfg: Config): WindowsState {
        val rect = Rect()
        class W(val info: AccessibilityWindowInfo, val box: Box, val pkg: String?)
        val all = try {
            service.windows.map { w ->
                w.getBoundsInScreen(rect)
                val pkg = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    try {
                        w.root?.packageName?.toString()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                W(w, Box(rect.left, rect.top, rect.right, rect.bottom), pkg)
            }
        } catch (e: Exception) {
            Diagnostics.error(service, "windows", e)
            emptyList()
        }
        val grab = all.filter { it.pkg != null && it.pkg in cfg.targetPackages }
        val main = grab.maxByOrNull { it.box.width.toLong() * it.box.height }
        val occluders = ArrayList<Box>()
        var dialog = false
        if (main != null) {
            val mainArea = main.box.width.toLong() * main.box.height
            for (w in all) {
                if (w === main || w.info.layer <= main.info.layer) continue
                // Our own 1-pixel keep-awake window and similar specks cover nothing.
                if (w.box.width <= 2 || w.box.height <= 2) continue
                occluders += w.box
                if (w in grab && w.box.width.toLong() * w.box.height < mainArea * 85 / 100) dialog = true
            }
        }
        val roots = grab.sortedByDescending { it.box.width.toLong() * it.box.height }.mapNotNull {
            try {
                it.info.root
            } catch (e: Exception) {
                null
            }
        }
        val own = all.any { it.pkg == service.packageName }
        if (roots.isEmpty()) {
            // Some phones do not list windows; the active window is enough to keep working.
            val active = service.rootInActiveWindow
            val pkg = active?.packageName?.toString()
            if (active != null && pkg != null && pkg in cfg.targetPackages) {
                return WindowsState(listOf(active), emptyList(), false, false)
            }
        }
        return WindowsState(roots, occluders, dialog, own)
    }

    /** Reads and analyses the Grab screen; null when Grab is not on screen. */
    fun read(cfg: Config): Screen? {
        val w = windows(cfg)
        if (!w.grabVisible) return null
        val roots = w.grabRoots.map { NodeSnapshot.capture(it, MAX_NODES) }
        val a = ScreenAnalyzer.analyze(roots, cfg, ScanContext(w.occluders) { inkBox(it) })
        return Screen(roots, a, w.dialog)
    }

    /** Screen area covered by the letters of a text element (asked only when something overlaps it). */
    private fun inkBox(n: UiNode): Box? {
        val info = n.ref as? AccessibilityNodeInfo ?: return null
        return try {
            val text = info.text ?: return null
            val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            if (text.isEmpty() || key !in info.availableExtraData) return null
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, minOf(text.length, 200))
            }
            if (!info.refreshWithExtraData(key, args)) return null
            @Suppress("DEPRECATION")
            val rects: Array<Parcelable>? = info.extras.getParcelableArray(key)
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            rects?.forEach { p ->
                val rf = p as? RectF ?: return@forEach
                if (rf.width() <= 0f && rf.height() <= 0f) return@forEach
                l = minOf(l, rf.left)
                t = minOf(t, rf.top)
                r = maxOf(r, rf.right)
                b = maxOf(b, rf.bottom)
            }
            if (l > r || t > b) null else Box(l.toInt(), t.toInt(), kotlin.math.ceil(r).toInt(), kotlin.math.ceil(b).toInt())
        } catch (e: Exception) {
            null
        }
    }
}
