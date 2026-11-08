package app.hachure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import app.hachure.store.CoverPattern
import kotlin.math.max

/**
 * One notebook cover on the shelf: a 3:4 hatch-patterned cover with the title on a white label,
 * and a caption line under it. With [isNewTile] it draws the dashed "New notebook" tile instead.
 * Pure black and white so it renders crisply in any e-ink mode.
 */
class CoverView(context: Context, private val dpmm: Float) : View(context) {

    var title = ""
    var caption = ""
    var pattern = CoverPattern.DIAGONAL
    var isNewTile = false

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private val white = Paint().apply { color = Color.WHITE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; isFakeBoldText = true }
    private val captionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val box = RectF()

    init {
        isClickable = true
        isLongClickable = true
    }

    private fun mm(v: Float) = v * dpmm

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val coverH = w * 4 / 3
        setMeasuredDimension(w, coverH + mm(CAPTION_MM).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val coverH = w * 4f / 3f
        val stroke = mm(0.5f)
        rule.strokeWidth = stroke
        box.set(stroke / 2, stroke / 2, w - stroke / 2, coverH - stroke / 2)

        if (isNewTile) {
            rule.pathEffect = DashPathEffect(floatArrayOf(mm(2f), mm(1.2f)), 0f)
            canvas.drawRect(box, rule)
            rule.pathEffect = null
            val cx = w / 2
            val cy = coverH / 2 - mm(3f)
            rule.strokeWidth = mm(0.6f)
            canvas.drawLine(cx - mm(4f), cy, cx + mm(4f), cy, rule)
            canvas.drawLine(cx, cy - mm(4f), cx, cy + mm(4f), rule)
            titlePaint.textSize = mm(3.2f)
            titlePaint.isFakeBoldText = false
            titlePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(title, cx, cy + mm(10f), titlePaint)
            return
        }

        canvas.save()
        canvas.clipRect(box)
        drawPattern(canvas, w, coverH)
        canvas.restore()
        canvas.drawRect(box, rule)

        // Title label: white box near the bottom of the cover.
        titlePaint.textSize = mm(3.2f)
        titlePaint.isFakeBoldText = true
        titlePaint.textAlign = Paint.Align.LEFT
        val pad = mm(2f)
        val labelH = titlePaint.textSize * 2.2f
        box.set(mm(3f), coverH - mm(3f) - labelH, w - mm(3f), coverH - mm(3f))
        canvas.drawRect(box, white)
        rule.strokeWidth = mm(0.4f)
        canvas.drawRect(box, rule)
        val text = TextUtils.ellipsize(title, titlePaint, box.width() - 2 * pad, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, box.left + pad, box.centerY() - (titlePaint.ascent() + titlePaint.descent()) / 2, titlePaint)

        captionPaint.textSize = mm(2.6f)
        canvas.drawText(caption, 0f, coverH + mm(CAPTION_MM) * 0.65f, captionPaint)
    }

    private fun drawPattern(c: Canvas, w: Float, h: Float) {
        val gap = mm(3.2f)
        ink.strokeWidth = mm(0.6f)
        val span = max(w, h) * 2
        fun lines(dx: Float, dy: Float) {
            // Parallel lines with direction (dx, dy), spaced by gap along the normal.
            val nx = -dy
            val ny = dx
            var k = -span
            while (k <= span) {
                val ox = w / 2 + nx * k
                val oy = h / 2 + ny * k
                c.drawLine(ox - dx * span, oy - dy * span, ox + dx * span, oy + dy * span, ink)
                k += gap
            }
        }
        val d = 0.70710677f
        when (pattern) {
            CoverPattern.DIAGONAL -> lines(d, -d)
            CoverPattern.DIAGONAL_BACK -> lines(d, d)
            CoverPattern.CROSS -> { lines(d, -d); lines(d, d) }
            CoverPattern.HORIZONTAL -> lines(1f, 0f)
            CoverPattern.DOTS -> {
                var y = gap / 2
                while (y < h) {
                    var x = gap / 2
                    while (x < w) {
                        c.drawCircle(x, y, mm(0.45f), ink)
                        x += gap
                    }
                    y += gap
                }
            }
        }
    }

    private companion object {
        const val CAPTION_MM = 6f
    }
}
