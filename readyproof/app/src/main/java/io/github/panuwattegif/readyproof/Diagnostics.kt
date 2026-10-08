package io.github.panuwattegif.readyproof

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.panuwattegif.readyproof.core.ConfigCodec
import io.github.panuwattegif.readyproof.core.Naming
import io.github.panuwattegif.readyproof.core.TreeDump
import io.github.panuwattegif.readyproof.core.UiNode
import java.io.File
import java.time.LocalDateTime

/**
 * Troubleshooting without a developer at the shop: an error log plus (when enabled in Settings)
 * text dumps of the watched screens, exported as one file the owner can send.
 */
object Diagnostics {
    private const val MAX_DUMPS = 25
    private const val MAX_ERROR_BYTES = 200_000L
    private const val MIN_DUMP_GAP_MS = 3_000L
    private val DIGITS = Regex("\\d")

    private var lastSignature = 0
    private var lastDumpAt = 0L

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "diag").apply { mkdirs() }

    @Synchronized
    fun error(ctx: Context, where: String, e: Throwable?) {
        try {
            val f = File(dir(ctx), "errors.log")
            f.appendText("${LocalDateTime.now()} [$where] ${e?.javaClass?.simpleName}: ${e?.message}\n")
            if (f.length() > MAX_ERROR_BYTES) f.writeText(f.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
        } catch (ignored: Exception) {
        }
    }

    /**
     * Saves a dump when the screen *layout* changed (digits ignored, so ticking timers do not
     * count) or when [force] is set (taps and hand captures).
     */
    @Synchronized
    fun dump(ctx: Context, label: String, roots: List<UiNode>, force: Boolean) {
        try {
            val now = System.currentTimeMillis()
            // Failure snapshots are automatic, but still bounded when a broken page repeats.
            if (now - lastDumpAt < MIN_DUMP_GAP_MS) return
            val text = roots.joinToString("\n") { TreeDump.dump(it) }
            val signature = DIGITS.replace(text, "#").hashCode()
            if (!force && signature == lastSignature) return
            lastSignature = signature
            lastDumpAt = now
            File(dir(ctx), "dump-$now.txt").writeText("# $label ${LocalDateTime.now()}\n$text")
            dir(ctx).listFiles { f -> f.name.startsWith("dump-") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_DUMPS)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            error(ctx, "dump", e)
        }
    }

    /** Writes everything useful for remote troubleshooting to Download/ReadyProof and returns it. */
    fun export(ctx: Context): Uri {
        val sb = StringBuilder()
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        sb.append("ReadyProof ").append(pkg.versionName).append(" (").append(pkg.longVersionCode).append(")\n")
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" SDK ").append(Build.VERSION.SDK_INT)
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("Time ").append(LocalDateTime.now()).append('\n')
        sb.append("Service enabled=").append(ServiceStatus.isEnabled(ctx))
            .append(" running=").append(ProofService.instance != null)
            .append(" lastGrabEvent=").append(ProofService.lastTargetEventAt).append('\n')
        sb.append("\n## Config\n").append(ConfigCodec.encode(ConfigStore.get(ctx))).append('\n')
        val prefs = ConfigStore.prefs(ctx)
        sb.append("\n## Monitor and closing\n")
        for (key in listOf("monitor_status", "closing_history_status", "auto_history_last_result", "drive_status"))
            sb.append(key).append("=").append(prefs.getString(key, null)).append('\n')
        sb.append("shop=").append(ShopStore.get(ctx)?.id)
            .append(" driveEnabled=").append(prefs.getBoolean("drive_enabled", false)).append('\n')
        sb.append("autoNavigationEnabled=").append(prefs.getBoolean("auto_navigation_enabled", true)).append('\n')
        sb.append("\n## Local daily batch requests\n")
        File(ctx.filesDir, "reports").listFiles()?.filter { it.name.contains("batch-request-") }
            ?.sortedBy { it.name }?.forEach { sb.append(it.name).append(" | bytes=").append(it.length()).append('\n') }
        sb.append("\n## Upload journal (no tokens or photo contents)\n")
        DriveSync.entries(ctx).forEach { e ->
            sb.append(e.key).append(" | shop=").append(e.shopId).append(" | ").append(e.name)
                .append(" | state=").append(e.state).append(" | error=").append(e.error)
                .append(" | batchMembers=").append(e.batchMembers?.size).append('\n')
        }
        sb.append("\n## Recent taps\n")
        ClickLog.list(ctx).forEach { e ->
            sb.append(e.t).append(" | ").append(e.label).append(" | ").append(e.className).append(" | ")
                .append(e.viewId).append(" | matched=").append(e.matched).append('\n')
        }
        val errors = File(dir(ctx), "errors.log")
        sb.append("\n## Errors\n").append(if (errors.exists()) errors.readLines().takeLast(100).joinToString("\n") else "-").append('\n')
        dir(ctx).listFiles { f -> f.name.startsWith("dump-") }?.sortedByDescending { it.name }?.forEach {
            sb.append("\n## ").append(it.name).append('\n').append(it.readText())
        }
        val name = "readyproof-diagnostics-" + Naming.stamp(LocalDateTime.now()) + ".txt"
        return MediaSaver.saveDownload(ctx, name, "text/plain", sb.toString().toByteArray(Charsets.UTF_8))
    }
}
