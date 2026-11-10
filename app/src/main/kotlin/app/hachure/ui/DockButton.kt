package app.hachure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

/**
 * One rail or ring button in the dock's design language: a stroked icon, an optional small label
 * under it, and an inverted (white on black) look when selected. Sized by the dock in mm.
 *
 * [glyph] replaces the icon with a short text (the style menu shows the brush name), and
 * [widthBar] draws a horizontal bar of that thickness in px (the width button).
 */
class DockButton(context: Context) : View(context) {

    var icon: Drawable? = null
        set(value) { field = value?.mutate(); invalidate() }

    var label: String = ""
        set(value) { field = value; contentDescription = value; invalidate() }

    var showLabel = false
        set(value) { field = value; invalidate() }

    var glyph: String? = null
        set(value) { field = value; invalidate() }

    var widthBar = 0f
        set(value) { field = value; invalidate() }

    var round = false

    /** Glyph text height as a fraction of the icon box. */
    var glyphScale = 0.42f

    /** Icon box edge in px. */
    var iconPx = 48

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        isClickable = true
        isFocusable = false
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val fg = when {
            !isEnabled -> DISABLED
            isSelected -> Color.WHITE
            else -> Color.BLACK
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (isSelected) {
            bg.color = Color.BLACK
            if (round) canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, bg) else canvas.drawRect(0f, 0f, w, h, bg)
        } else if (round) {
            bg.color = Color.WHITE
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, bg)
            border.color = Color.BLACK
            border.strokeWidth = RULE_PX
            canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f - RULE_PX / 2f, border)
        }

        val labelH = if (showLabel && label.isNotEmpty()) h * 0.28f else 0f
        val cx = w / 2f
        val cy = (h - labelH) / 2f
        val box = minOf(iconPx.toFloat(), (h - labelH) * 0.62f)
        when {
            widthBar > 0f -> {
                bar.color = fg
                bar.strokeWidth = widthBar
                canvas.drawLine(cx - box / 2f, cy, cx + box / 2f, cy, bar)
            }
            glyph != null -> {
                glyphPaint.color = fg
                glyphPaint.textSize = box * glyphScale
                canvas.drawText(glyph!!, cx, cy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
            }
            else -> icon?.let {
                it.setTint(fg)
                it.setBounds((cx - box / 2f).toInt(), (cy - box / 2f).toInt(), (cx + box / 2f).toInt(), (cy + box / 2f).toInt())
                it.draw(canvas)
            }
        }
        if (labelH > 0f) {
            text.color = fg
            text.textSize = labelH * 0.8f
            canvas.drawText(label, cx, h - labelH * 0.25f, text)
        }
    }

    private companion object {
        val DISABLED = 0xFF9A9A9A.toInt()
        const val RULE_PX = 4f
    }
}
