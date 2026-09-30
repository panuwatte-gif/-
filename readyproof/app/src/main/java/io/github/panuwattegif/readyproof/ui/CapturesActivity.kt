package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.panuwattegif.readyproof.ConfigStore
import io.github.panuwattegif.readyproof.RecordStore
import io.github.panuwattegif.readyproof.core.Record
import io.github.panuwattegif.readyproof.core.ReportText
import io.github.panuwattegif.readyproof.core.TextNorm
import java.time.LocalDate
import java.time.ZoneId

/** Every screenshot of a day (or of all kept days when searching), newest first. */
class CapturesActivity : Activity() {
    private var date: LocalDate = LocalDate.now()
    private var query = ""
    private var allDays = false
    private var shown: List<Record> = emptyList()
    private lateinit var thumbs: Thumbs
    private lateinit var adapter: RecordAdapter
    private lateinit var dateLabel: TextView
    private lateinit var countLabel: TextView
    private val refresh: () -> Unit = { reload() }
    private val zone: ZoneId = ZoneId.systemDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs = Thumbs(this)
        val root = Ui.frame(this, "ภาพที่แคปไว้", "แตะ = ดูภาพ · กดค้าง = ส่งภาพนั้น")
        val top = Ui.column(this).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 6)) }

        val nav = Ui.row(this)
        nav.addView(Ui.smallButton(this, "◀ วันก่อน") {
            date = date.minusDays(1)
            reload()
        })
        dateLabel = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
        }
        nav.addView(dateLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(Ui.smallButton(this, "วันถัดไป ▶") {
            if (date.isBefore(LocalDate.now())) {
                date = date.plusDays(1)
                reload()
            }
        })
        top.addView(nav)

        val search = EditText(this).apply {
            hint = "ค้นหาเลข GF เช่น 613"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            textSize = 16f
            background = Ui.rounded(context, Ui.CARD, 8f, Ui.LINE)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    reload()
                }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@CapturesActivity, 8) })
        top.addView(CheckBox(this).apply {
            text = "ค้นหาทุกวันที่เก็บไว้"
            setOnCheckedChangeListener { _, on ->
                allDays = on
                reload()
            }
        })

        val actions = Ui.row(this)
        countLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.MUTED)
        }
        actions.addView(countLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(Ui.smallButton(this, "📤 ส่งทุกภาพที่แสดง") {
            Share.images(this, shown.mapNotNull { it.uri?.let(Uri::parse) })
        })
        top.addView(actions)
        root.addView(top)

        adapter = RecordAdapter()
        val list = ListView(this).apply {
            divider = ColorDrawable(Ui.LINE)
            dividerHeight = 1
            setBackgroundColor(Ui.CARD)
            adapter = this@CapturesActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.uri?.let { Share.view(this@CapturesActivity, Uri.parse(it)) } }
            setOnItemLongClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.uri?.let { Share.images(this@CapturesActivity, listOf(Uri.parse(it))) }
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        RecordStore.addListener(refresh)
        reload()
    }

    override fun onPause() {
        RecordStore.removeListener(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        thumbs.close()
        super.onDestroy()
    }

    private fun reload() {
        val q = TextNorm.key(query)
        val searchAll = allDays && q.isNotEmpty()
        val records = if (searchAll) {
            val today = LocalDate.now()
            RecordStore.loadRange(this, today.minusDays(ConfigStore.get(this).retentionDays.toLong()), today)
        } else {
            RecordStore.load(this, date)
        }
        shown = records.asSequence()
            .filter { it.uri != null }
            .filter { q.isEmpty() || TextNorm.key(it.searchText()).contains(q) }
            .sortedByDescending { it.t }
            .toList()
        dateLabel.text = if (searchAll) "ทุกวัน" else ReportText.date(date)
        countLabel.text = "${shown.size} ภาพ"
        adapter.notifyDataSetChanged()
    }

    private class Holder(val image: ImageView, val title: TextView, val gfs: TextView, val detail: TextView)

    private inner class RecordAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: Holder
            if (convertView == null) {
                val ctx = parent.context
                val row = Ui.row(ctx).apply {
                    setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
                    layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                }
                val image = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = Ui.rounded(ctx, Ui.FIELD, 6f, Ui.LINE)
                }
                row.addView(image, LinearLayout.LayoutParams(Ui.dp(ctx, 60), Ui.dp(ctx, 128)))
                val texts = Ui.column(ctx).apply { setPadding(Ui.dp(ctx, 12), 0, 0, 0) }
                val title = TextView(ctx).apply {
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val gfs = TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(Ui.GREEN)
                }
                val detail = TextView(ctx).apply {
                    textSize = 13f
                    setTextColor(Ui.MUTED)
                }
                texts.addView(title)
                texts.addView(gfs)
                texts.addView(detail)
                row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                holder = Holder(image, title, gfs, detail)
                row.tag = holder
                view = row
            } else {
                view = convertView
                holder = convertView.tag as Holder
            }
            val r = shown[position]
            val day = if (allDays && query.isNotBlank()) ReportText.date(RecordStore.dateOf(r.t)) + " " else ""
            holder.title.text = day + ReportText.time(r.t, zone) + " · " + r.kind.label
            holder.gfs.text = r.gfs.ifEmpty { r.visible }.joinToString(", ").ifEmpty { "ไม่พบเลข GF" }
            val first = r.items.firstOrNull()
            holder.detail.text = listOfNotNull(
                first?.status,
                first?.countdown?.let { "เหลือ $it" },
                first?.delayMin?.let { "ล่าช้า $it นาที" },
                r.note,
            ).joinToString(" · ")
            thumbs.into(holder.image, r.uri)
            return view
        }
    }
}
