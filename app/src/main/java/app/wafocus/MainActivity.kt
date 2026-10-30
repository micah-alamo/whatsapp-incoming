package app.wafocus

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One visible line: personal chats are collapsed per contact, mentions stay one per message. */
class Row(val head: Msg, var count: Int, val ids: MutableList<Long>)

class MainActivity : Activity() {

    private val green = Color.parseColor("#128C7E")
    private val greenDark = Color.parseColor("#0B6B5F")
    private val bg = Color.parseColor("#F1F3F2")

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var list: ListView
    private lateinit var banner: TextView
    private lateinit var countView: TextView
    private lateinit var chips: List<TextView>
    private lateinit var readChip: TextView

    private var filter = 0          // 0 all, 1 personal, 2 mentions
    private var showRead = false
    private var rows: List<Row> = emptyList()
    private val adapter by lazy { RowAdapter() }
    private val refreshCb: () -> Unit = { refresh() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        prefs = Prefs(this)
        window.statusBarColor = greenDark
        setContentView(buildUi())
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        Db.listeners.add(refreshCb)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        Db.listeners.remove(refreshCb)
    }

    override fun onResume() {
        super.onResume()
        banner.visibility = if (listenerEnabled()) View.GONE else View.VISIBLE
        db.purge(prefs.retentionDays)
        db.touch()
        if (!prefs.askedSetup) {
            prefs.askedSetup = true
            showSettings()
        }
    }

    private fun listenerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)

        // Header
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(green)
        top.setPadding(dp(16), dp(12), dp(6), dp(12))

        val title = TextView(this)
        title.text = "WA Focus"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTypeface(null, Typeface.BOLD)
        top.addView(title)

        countView = TextView(this)
        countView.setTextColor(Color.WHITE)
        countView.setPadding(dp(10), 0, 0, 0)
        top.addView(countView)

        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(headerButton("✓ All read") {
            db.markAllRead()
            refresh()
        })
        top.addView(headerButton("⚙") { showSettings() })
        root.addView(top)

        // Permission banner
        banner = TextView(this)
        banner.text = "Notification access is OFF — tap to enable.\n" +
            "Android 13+: first open App info ⋮ → \"Allow restricted settings\"."
        banner.setTextColor(Color.WHITE)
        banner.setBackgroundColor(Color.parseColor("#C62828"))
        banner.setPadding(dp(16), dp(10), dp(16), dp(10))
        banner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        root.addView(banner)

        // Filter chips
        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(10), dp(8), dp(10), dp(8))
        chips = listOf("All", "Personal", "Mentions").mapIndexed { i, label ->
            chip(label) {
                filter = i
                refresh()
            }
        }
        chips.forEach { chipRow.addView(it) }
        readChip = chip("Unread only") {
            showRead = !showRead
            refresh()
        }
        chipRow.addView(readChip)
        root.addView(chipRow)

        // List + empty state
        val frame = FrameLayout(this)
        list = ListView(this)
        list.divider = ColorDrawable(Color.parseColor("#1A000000"))
        list.dividerHeight = 1
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            db.markRead(r.ids)
            open(r.head)
            refresh()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            db.markRead(rows[pos].ids)
            Toast.makeText(this, "Marked read", Toast.LENGTH_SHORT).show()
            refresh()
            true
        }
        frame.addView(list)

        val empty = TextView(this)
        empty.text = "Nothing unread.\nPersonal messages and @mentions will show up here."
        empty.gravity = Gravity.CENTER
        empty.setTextColor(Color.GRAY)
        empty.setPadding(dp(32), dp(32), dp(32), dp(32))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty

        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun headerButton(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setPadding(dp(14), dp(6), dp(14), dp(6))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.rightMargin = dp(8)
        t.layoutParams = lp
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleChip(t: TextView, selected: Boolean) {
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(if (selected) green else Color.parseColor("#DDE3E1"))
        t.background = g
        t.setTextColor(if (selected) Color.WHITE else Color.parseColor("#33403D"))
    }

    // ---------------------------------------------------------------- data

    private fun refresh() {
        rows = buildRows(db.query(filter, showRead))
        adapter.notifyDataSetChanged()
        val unread = db.unreadCount()
        countView.text = if (unread > 0) "· $unread unread" else ""
        chips.forEachIndexed { i, c -> styleChip(c, i == filter) }
        readChip.text = if (showRead) "Incl. read" else "Unread only"
        styleChip(readChip, !showRead)
    }

    private fun buildRows(msgs: List<Msg>): List<Row> {
        val byChat = HashMap<String, Row>()
        val out = ArrayList<Row>()
        for (m in msgs) { // newest first
            if (m.kind == Kind.DM) {
                val k = m.pkg + "|" + m.chat + "|" + m.read
                val existing = byChat[k]
                if (existing == null) {
                    val r = Row(m, 1, mutableListOf(m.id))
                    byChat[k] = r
                    out.add(r)
                } else {
                    existing.count++
                    existing.ids.add(m.id)
                }
            } else {
                out.add(Row(m, 1, mutableListOf(m.id)))
            }
        }
        return out
    }

    private fun open(m: Msg) {
        try {
            val digits = m.chat.filter { it.isDigit() }
            val phoneLike = digits.length >= 8 && m.chat.all { it.isDigit() || it in "+ -()" }
            if (m.kind == Kind.DM && phoneLike) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
                i.setPackage(m.pkg)
                startActivity(i)
            } else {
                startActivity(packageManager.getLaunchIntentForPackage(m.pkg))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmtTime(ts: Long): String {
        val f = if (DateUtils.isToday(ts)) "HH:mm" else "d MMM HH:mm"
        return SimpleDateFormat(f, Locale.getDefault()).format(Date(ts))
    }

    // ---------------------------------------------------------------- list rows

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = rows[position].head.id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@MainActivity
            val r = rows[position]
            val m = r.head
            val unread = !m.read

            val v = LinearLayout(ctx)
            v.orientation = LinearLayout.VERTICAL
            v.setPadding(dp(16), dp(10), dp(16), dp(10))
            v.setBackgroundColor(if (unread) Color.WHITE else bg)

            val line1 = LinearLayout(ctx)
            line1.orientation = LinearLayout.HORIZONTAL

            val name = TextView(ctx)
            name.text = m.chat + if (r.count > 1) "  (${r.count})" else ""
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            name.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            name.setTextColor(if (unread) Color.BLACK else Color.GRAY)
            name.setSingleLine()
            name.ellipsize = TextUtils.TruncateAt.END
            line1.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val time = TextView(ctx)
            time.text = fmtTime(m.ts)
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            time.setTextColor(if (unread) green else Color.GRAY)
            line1.addView(time)
            v.addView(line1)

            val body = TextView(ctx)
            body.text = if (m.kind == Kind.DM) m.body else "${m.sender}: ${m.body}"
            body.maxLines = 3
            body.ellipsize = TextUtils.TruncateAt.END
            body.setTextColor(Color.parseColor("#44504D"))
            v.addView(body)

            if (m.kind != Kind.DM) {
                val tag = TextView(ctx)
                tag.text = if (m.kind == Kind.MENTION) "@ mentioned you in a group" else "keyword match in a group"
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                tag.setTextColor(green)
                tag.setPadding(0, dp(2), 0, 0)
                v.addView(tag)
            }
            return v
        }
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        fun label(t: String): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.setTextColor(Color.DKGRAY)
            tv.setPadding(0, dp(12), 0, dp(2))
            return tv
        }

        val aliases = EditText(this)
        aliases.setText(prefs.aliases)
        aliases.hint = "e.g. Iqbal, Munahar, 081234567890"
        val keywords = EditText(this)
        keywords.setText(prefs.keywords)
        keywords.hint = "e.g. urgent, deadline (optional)"
        val retention = EditText(this)
        retention.inputType = InputType.TYPE_CLASS_NUMBER
        retention.setText(prefs.retentionDays.toString())

        val cbAll = CheckBox(this)
        cbAll.text = "Treat @everyone / @all as a mention"
        cbAll.isChecked = prefs.mentionAll
        val cbAuto = CheckBox(this)
        cbAuto.text = "Auto-mark read when I read it in WhatsApp"
        cbAuto.isChecked = prefs.autoRead
        val cbDiag = CheckBox(this)
        cbDiag.text = "Keep a local diagnostics log"
        cbDiag.isChecked = prefs.diagnostics

        box.addView(label("My names / numbers (what people @mention). Comma-separated:"))
        box.addView(aliases)
        box.addView(label("Extra keywords that also surface a group message:"))
        box.addView(keywords)
        box.addView(label("Keep messages for (days):"))
        box.addView(retention)
        box.addView(cbAll)
        box.addView(cbAuto)
        box.addView(cbDiag)

        fun action(text: String, run: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.isAllCaps = false
            b.setOnClickListener { run() }
            box.addView(b)
        }
        action("Notification access settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        action("Allow running in background (battery)") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        action("View diagnostics log") { showDiagnostics() }

        val sv = ScrollView(this)
        sv.addView(box)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                prefs.aliases = aliases.text.toString()
                prefs.keywords = keywords.text.toString()
                prefs.retentionDays = (retention.text.toString().toIntOrNull() ?: 30).coerceIn(1, 365)
                prefs.mentionAll = cbAll.isChecked
                prefs.autoRead = cbAuto.isChecked
                prefs.diagnostics = cbDiag.isChecked
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val fmt = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
        val entries = db.diag()
        val text = if (entries.isEmpty()) {
            "Nothing recorded yet. Receive a WhatsApp message with diagnostics enabled."
        } else {
            entries.joinToString("\n\n") { fmt.format(Date(it.first)) + "  " + it.second }
        }
        val tv = TextView(this)
        tv.text = text
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(20), dp(12), dp(20), dp(12))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("What the parser saw")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("wa-focus diagnostics", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Clear") { _, _ -> db.clearDiag() }
            .show()
    }
}
