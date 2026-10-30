package app.wafocus

import android.content.Context

fun splitList(s: String): List<String> =
    s.split(',', '\n').map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("wafocus", Context.MODE_PRIVATE)

    /** Names / numbers that count as "me" when someone @mentions me in a group. */
    var aliases: String
        get() = sp.getString("aliases", "") ?: ""
        set(v) { sp.edit().putString("aliases", v).apply() }

    /** Extra words that surface a group message even without an @mention (e.g. "urgent"). */
    var keywords: String
        get() = sp.getString("keywords", "") ?: ""
        set(v) { sp.edit().putString("keywords", v).apply() }

    var mentionAll: Boolean
        get() = sp.getBoolean("mentionAll", true)
        set(v) { sp.edit().putBoolean("mentionAll", v).apply() }

    /** Mark items read automatically when WhatsApp clears its notification (you read it there). */
    var autoRead: Boolean
        get() = sp.getBoolean("autoRead", true)
        set(v) { sp.edit().putBoolean("autoRead", v).apply() }

    /** Keep a short local log of what the parser saw (including dropped group chatter) for tuning. */
    var diagnostics: Boolean
        get() = sp.getBoolean("diag", true)
        set(v) { sp.edit().putBoolean("diag", v).apply() }

    var retentionDays: Int
        get() = sp.getInt("ret", 30)
        set(v) { sp.edit().putInt("ret", v).apply() }

    var askedSetup: Boolean
        get() = sp.getBoolean("askedSetup", false)
        set(v) { sp.edit().putBoolean("askedSetup", v).apply() }
}
