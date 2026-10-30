package app.wafocus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object Kind {
    const val DM = 0        // personal chat
    const val MENTION = 1   // @mention in a group
    const val KEYWORD = 2   // keyword hit in a group
}

data class Msg(
    val id: Long,
    val pkg: String,
    val chat: String,      // contact name (DM) or group name (mention/keyword)
    val sender: String,
    val body: String,
    val ts: Long,
    val kind: Int,
    val read: Boolean,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "wafocus.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msg(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "dedup TEXT UNIQUE NOT NULL," +
                "pkg TEXT, chat TEXT, sender TEXT, body TEXT," +
                "ts INTEGER, kind INTEGER, is_read INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX msg_ts ON msg(ts)")
        db.execSQL("CREATE TABLE diag(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, info TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun insert(m: Msg): Boolean {
        val cv = ContentValues().apply {
            put("dedup", "${m.pkg}|${m.chat}|${m.sender}|${m.ts}|${m.body}")
            put("pkg", m.pkg)
            put("chat", m.chat)
            put("sender", m.sender)
            put("body", m.body)
            put("ts", m.ts)
            put("kind", m.kind)
            put("is_read", 0)
        }
        return writableDatabase.insertWithOnConflict("msg", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** filter: 0 = everything, 1 = personal only, 2 = mentions/keywords only. */
    @Synchronized
    fun query(filter: Int, includeRead: Boolean): List<Msg> {
        val sb = StringBuilder("SELECT id,pkg,chat,sender,body,ts,kind,is_read FROM msg WHERE 1=1")
        when (filter) {
            1 -> sb.append(" AND kind=0")
            2 -> sb.append(" AND kind IN (1,2)")
        }
        if (!includeRead) sb.append(" AND is_read=0")
        sb.append(" ORDER BY ts DESC LIMIT 800")
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(sb.toString(), null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Msg(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                        c.getString(4) ?: "", c.getLong(5), c.getInt(6), c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun unreadCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msg WHERE is_read=0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    @Synchronized
    fun markRead(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("UPDATE msg SET is_read=1 WHERE id IN (${ids.joinToString(",")})")
    }

    @Synchronized
    fun markChatRead(chat: String) {
        writableDatabase.execSQL("UPDATE msg SET is_read=1 WHERE chat=? AND is_read=0", arrayOf(chat))
    }

    @Synchronized
    fun markAllRead() {
        writableDatabase.execSQL("UPDATE msg SET is_read=1 WHERE is_read=0")
    }

    @Synchronized
    fun purge(days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.execSQL("DELETE FROM msg WHERE ts < ?", arrayOf<Any>(cutoff))
    }

    @Synchronized
    fun addDiag(info: String) {
        val w = writableDatabase
        w.execSQL("INSERT INTO diag(ts, info) VALUES(?, ?)", arrayOf<Any>(System.currentTimeMillis(), info))
        w.execSQL("DELETE FROM diag WHERE id NOT IN (SELECT id FROM diag ORDER BY id DESC LIMIT 60)")
    }

    @Synchronized
    fun diag(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT ts, info FROM diag ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getLong(0), c.getString(1) ?: ""))
        }
        return out
    }

    @Synchronized
    fun clearDiag() {
        writableDatabase.execSQL("DELETE FROM diag")
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        val listeners = CopyOnWriteArrayList<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        fun changed() {
            main.post { listeners.forEach { it() } }
        }
    }
}
