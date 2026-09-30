package io.github.panuwattegif.readyproof

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast

/** Checks and shortcuts for the one-time phone setup shown on the home screen. */
object ServiceStatus {

    fun isEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo?.serviceInfo
            si?.packageName == ctx.packageName && si.name == ProofService::class.java.name
        }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isIgnoringBattery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    fun openAccessibilitySettings(a: Activity) = start(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppInfo(a: Activity) =
        start(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))

    fun requestIgnoreBattery(a: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName))
        if (!start(a, direct, quiet = true)) start(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openApp(a: Activity, pkg: String) {
        val intent = a.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) Toast.makeText(a, "ไม่พบแอปนี้ในเครื่อง", Toast.LENGTH_SHORT).show() else start(a, intent)
    }

    private fun start(a: Activity, intent: Intent, quiet: Boolean = false): Boolean = try {
        a.startActivity(intent)
        true
    } catch (e: Exception) {
        if (!quiet) Toast.makeText(a, "เปิดหน้านี้ไม่ได้ในเครื่องนี้ กรุณาเปิดจากหน้าตั้งค่าเอง", Toast.LENGTH_LONG).show()
        false
    }
}
