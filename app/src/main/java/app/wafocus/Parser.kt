package app.wafocus

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Turns a WhatsApp notification into zero or more incoming messages.
 *
 * WhatsApp posts MessagingStyle notifications: each chat is one notification whose
 * EXTRA_MESSAGES holds the last few messages. Groups carry EXTRA_CONVERSATION_TITLE
 * (the group name) and a per-message sender; personal chats don't.
 */
object Parser {
    val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")

    class Parsed(
        val chat: String,
        val sender: String,
        val text: String,
        val ts: Long,
        val isGroup: Boolean,
    )

    private val SUFFIX = Regex("\\s*\\((\\d+) (messages?|pesan)\\)\\s*$", RegexOption.IGNORE_CASE)

    private val NOISE = listOf(
        Regex("^\\d+ (new )?messages?( from \\d+ chats?)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d+ pesan( baru)?( dari \\d+ (obrolan|chat))?$", RegexOption.IGNORE_CASE),
        Regex("checking for new messages|memeriksa pesan", RegexOption.IGNORE_CASE),
        Regex("whatsapp web is currently active|whatsapp web sedang aktif", RegexOption.IGNORE_CASE),
        Regex("backup in progress|pencadangan", RegexOption.IGNORE_CASE),
    )

    // When the device owner replies via notification, WhatsApp shows them as "You".
    private val SELF = setOf("you", "me", "anda", "saya")

    private fun clean(s: CharSequence?): String = (s?.toString() ?: "").replace(SUFFIX, "").trim()

    private fun isNoise(t: String): Boolean {
        val x = t.trim()
        return NOISE.any { it.containsMatchIn(x) }
    }

    fun parse(sbn: StatusBarNotification): List<Parsed> {
        val n = sbn.notification ?: return emptyList()
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val ex = n.extras ?: return emptyList()

        val title = clean(ex.getCharSequence(Notification.EXTRA_TITLE))
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val conv = clean(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))

        var isGroup = ex.getBoolean("android.isGroupConversation", false) || conv.isNotEmpty()
        var chat = if (conv.isNotEmpty()) conv else title
        var fallbackSender = ""

        // Older layout: "Sender @ Group"
        if (!isGroup && title.contains(" @ ")) {
            isGroup = true
            fallbackSender = title.substringBefore(" @ ").trim()
            chat = title.substringAfter(" @ ").trim()
        }
        if (chat.isEmpty()) return emptyList()

        val out = ArrayList<Parsed>()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)

        if (arr != null && arr.isNotEmpty()) {
            for (o in arr) {
                val b = o as? Bundle ?: continue
                val t = b.getCharSequence("text")?.toString().orEmpty()
                val ts = b.getLong("time", 0L)
                var s = b.getCharSequence("sender")?.toString()
                if (s.isNullOrBlank() && Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    val person = b.getParcelable<android.app.Person>("sender_person")
                    s = person?.name?.toString()
                }
                var sender = s?.trim().orEmpty()
                if (sender.lowercase() in SELF) continue
                if (isGroup && sender.isEmpty()) continue
                if (!isGroup && sender.isEmpty()) sender = chat
                if (t.isBlank() || isNoise(t)) continue
                out.add(Parsed(chat, sender, t.trim(), if (ts > 0) ts else sbn.postTime, isGroup))
            }
        } else if (text.isNotBlank() && !isNoise(text)) {
            var sender = fallbackSender
            var body = text
            if (isGroup && sender.isEmpty() && text.contains(": ")) {
                sender = text.substringBefore(": ").trim()
                body = text.substringAfter(": ")
            }
            if (sender.isEmpty()) sender = chat
            out.add(Parsed(chat, sender, body.trim(), sbn.postTime, isGroup))
        }
        return out
    }

    /** Raw view of a notification, for the diagnostics log. */
    fun describe(sbn: StatusBarNotification): String {
        val n = sbn.notification ?: return "(null notification)"
        val ex = n.extras
        return "summary=${n.flags and Notification.FLAG_GROUP_SUMMARY != 0} " +
            "title=${ex?.getCharSequence(Notification.EXTRA_TITLE)} " +
            "conv=${ex?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)} " +
            "grpFlag=${ex?.getBoolean("android.isGroupConversation", false)} " +
            "text=${ex?.getCharSequence(Notification.EXTRA_TEXT)}"
    }
}

/** Decides what is worth showing. Returns null for ordinary group chatter. */
object Filter {
    private val EVERYONE = Regex("@(everyone|all|semua)\\b", RegexOption.IGNORE_CASE)

    fun classify(p: Parser.Parsed, prefs: Prefs): Int? {
        if (!p.isGroup) return Kind.DM
        if (isMention(p.text, prefs)) return Kind.MENTION
        val kws = splitList(prefs.keywords)
        if (kws.any { p.text.contains(it, ignoreCase = true) }) return Kind.KEYWORD
        return null
    }

    private fun isMention(text: String, prefs: Prefs): Boolean {
        val compact = text.replace(Regex("[\\s\\-()]"), "")
        for (a in splitList(prefs.aliases)) {
            var digits = a.filter { it.isDigit() }
            val looksLikeNumber = digits.length >= 7 && a.all { it.isDigit() || it in "+ -()" }
            if (looksLikeNumber) {
                // Local Indonesian format 08xx -> 628xx (WhatsApp renders mentions with the full number).
                if (digits.startsWith("0")) digits = "62" + digits.drop(1)
                if (compact.contains("@$digits") || compact.contains("@+$digits")) return true
            } else if (text.contains("@$a", ignoreCase = true)) {
                return true
            }
        }
        return prefs.mentionAll && EVERYONE.containsMatchIn(text)
    }
}
