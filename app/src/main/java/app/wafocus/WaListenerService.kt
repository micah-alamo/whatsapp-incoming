package app.wafocus

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            Db.get(this).purge(Prefs(this).retentionDays)
            // Pick up anything already sitting in the shade when the service (re)starts.
            activeNotifications?.forEach { handle(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed", t)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, WaListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        if (sbn == null || sbn.packageName !in Parser.PKGS) return
        val prefs = Prefs(this)
        if (!prefs.autoRead) return
        // Tapped, or WhatsApp itself cleared it (you read the chat in WhatsApp / on another device).
        if (reason != REASON_CLICK_ && reason != REASON_APP_CANCEL_) return
        try {
            val db = Db.get(this)
            Parser.parse(sbn).map { it.chat }.distinct().forEach { db.markChatRead(it) }
            Db.changed()
        } catch (t: Throwable) {
            Log.w(TAG, "remove failed", t)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in Parser.PKGS) return
        try {
            val prefs = Prefs(this)
            val db = Db.get(this)
            val parsed = Parser.parse(sbn)
            var added = false
            for (p in parsed) {
                val kind = Filter.classify(p, prefs)
                if (prefs.diagnostics) {
                    val label = when (kind) {
                        Kind.DM -> "DM"
                        Kind.MENTION -> "MENTION"
                        Kind.KEYWORD -> "KEYWORD"
                        else -> "dropped (group chatter)"
                    }
                    db.addDiag("[$label] ${p.chat} | ${p.sender}: ${p.text.take(80)}")
                }
                if (kind == null) continue
                if (db.insert(Msg(0, sbn.packageName, p.chat, p.sender, p.text, p.ts, kind, false))) added = true
            }
            if (parsed.isEmpty() && prefs.diagnostics) db.addDiag("[no messages parsed] " + Parser.describe(sbn))
            if (added) Db.changed()
        } catch (t: Throwable) {
            Log.w(TAG, "handle failed", t)
        }
    }

    companion object {
        private const val TAG = "WaFocus"
        private const val REASON_CLICK_ = NotificationListenerService.REASON_CLICK
        private const val REASON_APP_CANCEL_ = NotificationListenerService.REASON_APP_CANCEL
    }
}
