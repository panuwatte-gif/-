package io.github.panuwattegif.readyproof.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * All screens are built from these few helpers (no XML layouts), so the look stays consistent
 * and a screen is just a function that turns current state into views.
 */
object Ui {
    const val BG = 0xFFF4F5F7.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFF1F2328.toInt()
    const val MUTED = 0xFF6B7280.toInt()
    const val GREEN = 0xFF0F9D58.toInt()
    const val RED = 0xFFD93025.toInt()
    const val AMBER = 0xFFB26A00.toInt()
    const val LINE = 0xFFE5E7EB.toInt()
    const val FIELD = 0xFFF9FAFB.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(ctx, radiusDp)
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    private fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33000000), content, null)

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Title bar; the caller adds the body (with weight 1) to the returned root and sets it as content. */
    fun frame(a: Activity, title: String, subtitle: String? = null, back: Boolean = true): LinearLayout {
        val root = column(a).apply { setBackgroundColor(BG) }
        val bar = row(a).apply {
            setBackgroundColor(CARD)
            setPadding(dp(a, if (back) 4 else 16), dp(a, 10), dp(a, 16), dp(a, 10))
        }
        if (back) {
            bar.addView(TextView(a).apply {
                text = "‹"
                textSize = 32f
                setTextColor(TEXT)
                contentDescription = "กลับ"
                setPadding(dp(a, 12), 0, dp(a, 14), dp(a, 4))
                setOnClickListener { a.finish() }
            })
        }
        val titles = column(a)
        titles.addView(TextView(a).apply {
            text = title
            textSize = 20f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (subtitle != null) titles.addView(TextView(a).apply {
            text = subtitle
            textSize = 13f
            setTextColor(MUTED)
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(bar)
        root.addView(View(a).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        return root
    }

    /** Title bar + scrolling column; returns the column to fill. */
    fun page(a: Activity, title: String, subtitle: String? = null, back: Boolean = true): LinearLayout {
        val root = frame(a, title, subtitle, back)
        val scroll = ScrollView(a).apply { isFillViewport = true }
        val col = column(a).apply { setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 32)) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        a.setContentView(root)
        return col
    }

    fun card(parent: LinearLayout): LinearLayout {
        val ctx = parent.context
        val c = column(ctx).apply {
            background = rounded(ctx, CARD, 12f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 14))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })
        return c
    }

    fun text(
        parent: LinearLayout,
        s: CharSequence,
        size: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false,
        topDp: Int = 0,
    ): TextView {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.15f)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        parent.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, topDp) })
        return tv
    }

    fun button(
        parent: LinearLayout,
        label: String,
        filled: Boolean = true,
        color: Int = GREEN,
        onClick: () -> Unit,
    ): Button {
        val ctx = parent.context
        val b = Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) Color.WHITE else color)
            background = ripple(rounded(ctx, if (filled) color else CARD, 10f, if (filled) null else color))
            stateListAnimator = null
            minHeight = dp(ctx, 46)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
        return b
    }

    /** Small button for rows; the caller adds it. */
    fun smallButton(ctx: Context, label: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(GREEN)
        background = ripple(rounded(ctx, CARD, 8f, GREEN))
        stateListAnimator = null
        minHeight = dp(ctx, 36)
        minimumHeight = dp(ctx, 36)
        setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
        setOnClickListener { onClick() }
    }

    fun switch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val ctx = parent.context
        val s = Switch(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(TEXT)
            isChecked = checked
            minHeight = dp(ctx, 44)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        parent.addView(s, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return s
    }

    fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        help: String? = null,
        multiLine: Boolean = false,
        number: Boolean = false,
    ): EditText {
        val ctx = parent.context
        text(parent, label, 14f, TEXT, bold = true, topDp = 12)
        if (help != null) text(parent, help, 12f, MUTED)
        val e = EditText(ctx).apply {
            setText(value)
            textSize = 15f
            setTextColor(TEXT)
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiLine) {
                isSingleLine = false
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
            background = rounded(ctx, FIELD, 8f, LINE)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }
        parent.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 4) })
        return e
    }

    fun divider(parent: LinearLayout) {
        val ctx = parent.context
        parent.addView(View(ctx).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(ctx, 10); bottomMargin = dp(ctx, 6) })
    }

    fun toast(ctx: Context, msg: String, long: Boolean = false) =
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    fun alert(a: Activity, title: String, message: String) {
        AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("ตกลง", null).show()
    }

    fun confirm(a: Activity, title: String, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }
}
