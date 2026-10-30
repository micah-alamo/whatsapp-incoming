package app.wafocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Keeps one silent "N unread" notification in sync; Android derives the app-icon badge from it. */
object Notifier {
    private const val CHANNEL = "unread"
    private const val ID = 1

    fun update(ctx: Context, dm: Int, mention: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val total = dm + mention
            if (total == 0) {
                nm.cancel(ID)
                return
            }
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Unread summary", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Silent count of unread personal messages and mentions"
                ch.setShowBadge(true)
                nm.createNotificationChannel(ch)
            }
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val parts = ArrayList<String>()
            if (dm > 0) parts.add("$dm personal")
            if (mention > 0) parts.add("$mention mention" + if (mention > 1) "s" else "")
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("$total unread")
                .setContentText(parts.joinToString(" · "))
                .setContentIntent(pi)
                .setNumber(total)
                .setBadgeIconType(Notification.BADGE_ICON_SMALL)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
            nm.notify(ID, n)
        } catch (_: Exception) {
            // notification permission missing or similar: badge is a convenience, never crash for it
        }
    }
}
