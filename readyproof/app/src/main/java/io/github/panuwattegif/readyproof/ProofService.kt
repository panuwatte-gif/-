package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import io.github.panuwattegif.readyproof.core.Config

/**
 * The accessibility service: receives GrabMerchant's screen events and hands them to [Engine],
 * which does the watching, sweeping, screenshots and the end-of-day run. This phone only
 * watches; orders are accepted and marked ready on the shop's own device.
 * Events from any other app are never delivered (packageNames = the apps chosen in Settings).
 */
class ProofService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ProofService? = null
            private set

        /** Last time an event arrived from the target app; the home screen shows it as a heartbeat. */
        @Volatile
        var lastTargetEventAt = 0L
            private set

        /** Last saved photo, for the home screen. */
        @Volatile
        var lastCaptureText: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    @Volatile
    var engine: Engine? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        applyServiceInfo(ConfigStore.get(this))
        registerShortcutButton()
        engine = Engine(this).also { it.start() }
        instance = this
    }

    fun onConfigChanged(cfg: Config) {
        main.post { applyServiceInfo(cfg) }
    }

    /** Limits delivered events to the target apps chosen in Settings. */
    private fun applyServiceInfo(cfg: Config) {
        try {
            val info = serviceInfo ?: return
            info.packageNames = cfg.targetPackages.toTypedArray()
            serviceInfo = info
        } catch (e: Exception) {
            Diagnostics.error(this, "serviceInfo", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val cfg = ConfigStore.get(this)
        if (!cfg.enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in cfg.targetPackages) return
        lastTargetEventAt = System.currentTimeMillis()
        engine?.onEvent(event.eventType, event.contentChangeTypes)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        buttonCallback?.let { runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) } }
        buttonCallback = null
        engine?.stop()
        engine = null
    }

    // ---- hand captures: accessibility shortcut button and the in-app test --------------------

    private fun registerShortcutButton() {
        try {
            val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    requestManualCapture(0, null)
                }
            }
            accessibilityButtonController.registerAccessibilityButtonCallback(cb, main)
            buttonCallback = cb
        } catch (e: Exception) {
            Diagnostics.error(this, "button", e)
        }
    }

    /** Takes a screenshot after [delayMs] (lets the user switch to Grab when testing). */
    fun requestManualCapture(delayMs: Long, note: String?) {
        main.postDelayed({ engine?.manualCapture(note) }, delayMs)
    }

    /** The buttons "สรุปสิ้นวันตอนนี้" / "กวาด History ตอนนี้". */
    fun runEndOfDayNow() {
        main.post { engine?.runEndOfDayNow() }
    }

    /** Same as [runEndOfDayNow]; the name the History button has always used. */
    fun requestHistorySweepNow() = runEndOfDayNow()

    /** "กลับไปเฝ้า Ready". */
    fun resumeReadyMonitor() {
        main.post { engine?.resumeReadyMonitor() }
    }

    /**
     * "ปิด ReadyProof ชั่วคราว": banking apps refuse to run next to an accessibility service. An app
     * can switch its own service off (never on: that is done in Settings or with the volume keys).
     */
    fun switchOff() {
        Diagnostics.note(this, "switched off by hand")
        disableSelf()
    }

    /** The phone was just bound to a shop. */
    fun onShopBound() {
        main.post { engine?.onShopBound() }
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
