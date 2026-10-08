package io.github.panuwattegif.readyproof

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.panuwattegif.readyproof.ui.MainActivity
import io.github.panuwattegif.readyproof.ui.ReportActivity

/**
 * Messages from the unattended phone. Status updates are silent (no pop-up over Grab, which could
 * cover an order number in the next shot); only the finished end-of-day report alerts.
 */
object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_REPORT = "report"
    private const val CH_PROBLEM = "problem"
    const val ID_STATUS = 1
    const val ID_REPORT = 2
    const val ID_PROBLEM = 3
    const val ID_WATCH = 4

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(CH_STATUS) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "สถานะ ReadyProof", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CH_REPORT) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_REPORT, "สรุปสิ้นวัน", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (nm.getNotificationChannel(CH_PROBLEM) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_PROBLEM, "ปัญหาที่ต้องแก้", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    /** Silent progress messages; [ID_PROBLEM] / [ID_WATCH] go to the audible "problem" channel. */
    fun status(ctx: Context, title: String, text: String, id: Int = ID_STATUS) =
        post(ctx, id, if (id == ID_PROBLEM || id == ID_WATCH) CH_PROBLEM else CH_STATUS, title, text, MainActivity::class.java)

    fun report(ctx: Context, title: String, text: String) =
        post(ctx, ID_REPORT, CH_REPORT, title, text, ReportActivity::class.java)

    fun cancel(ctx: Context, id: Int) {
        try {
            manager(ctx)?.cancel(id)
        } catch (ignored: Exception) {
        }
    }

    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String, target: Class<*>) {
        if (!canPost(ctx)) return
        try {
            val nm = manager(ctx) ?: return
            val intent = Intent(ctx, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_proof)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(id, n)
        } catch (e: Exception) {
            Diagnostics.error(ctx, "notify", e)
        }
    }
}
