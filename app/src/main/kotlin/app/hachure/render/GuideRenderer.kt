package app.hachure.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.hachure.geometry.Guides
import app.hachure.geometry.Handle
import app.hachure.geometry.PageTransform
import app.hachure.geometry.RulerGeometry
import app.hachure.geometry.Vec2
import app.hachure.model.Ruler

/**
 * Draws rulers over the page. Pure black and white line work, no grey fill: it stays crisp in
 * the fast e-ink modes and the ink under a ruler stays readable.
 */
class GuideRenderer {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val path = Path()
    private val oval = RectF()
    private var dash: DashPathEffect? = null

    /** View-pixel bounds of everything [draw] or [drawGhost] may paint for [r]. */
    fun bounds(r: Ruler, t: PageTransform, out: Rect) {
        var l = Float.POSITIVE_INFINITY
        var tp = Float.POSITIVE_INFINITY
        var rt = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (c in RulerGeometry.corners(r)) {
            val v = t.pageToView(c)
            if (v.x < l) l = v.x
            if (v.y < tp) tp = v.y
            if (v.x > rt) rt = v.x
            if (v.y > b) b = v.y
        }
        // Room for the hatch arrow outside the edge and for the angle readout of the ghost.
        val pad = t.lengthToView(PAD_MM).toInt() + 4
        out.set(l.toInt() - pad, tp.toInt() - pad, rt.toInt() + pad, b.toInt() + pad)
    }

    fun draw(c: Canvas, r: Ruler, t: PageTransform, selected: Boolean, locked: Boolean) {
        val mm = t.scale
        val e1 = RulerGeometry.edge(r, 1)
        val e2 = RulerGeometry.edge(r, -1)
        val n = RulerGeometry.normal(r)

        // Short ends thin, drawing edges strong.
        line.pathEffect = null
        line.strokeWidth = THIN_PX
        seg(c, t, e1.a, e2.a)
        seg(c, t, e1.b, e2.b)
        line.strokeWidth = if (selected) SELECTED_PX else EDGE_PX
        seg(c, t, e1.a, e1.b)
        seg(c, t, e2.a, e2.b)

        // Ticks every 5 mm from the middle, longer every 10 mm, pointing into the bar.
        line.strokeWidth = THIN_PX
        val half = r.lengthMm / 2f
        var s = 0f
        var i = 0
        while (s <= half - 1f) {
            val len = if (i % 2 == 0) 2.2f else 1.2f
            for (sign in intArrayOf(1, -1)) {
                if (s == 0f && sign < 0) continue
                val p1 = e1.pointAt(half + sign * s)
                seg(c, t, p1, p1 - n * len)
                val p2 = e2.pointAt(half + sign * s)
                seg(c, t, p2, p2 + n * len)
            }
            s += 5f
            i++
        }

        if (r.hatchDir != 0) drawHatchArrow(c, r, t)

        if (locked) {
            drawLock(c, t.pageToView(RulerGeometry.center(r)), mm)
        } else {
            for (h in Handle.entries) drawHandle(c, t.pageToView(Guides.handlePosition(r, h)), h, mm, r)
        }
    }

    /** Dashed outline that follows the finger; [label] (the angle) is shown at the centre. */
    fun drawGhost(c: Canvas, r: Ruler, t: PageTransform, label: String?) {
        val d = dash ?: DashPathEffect(floatArrayOf(t.lengthToView(2f), t.lengthToView(1.2f)), 0f).also { dash = it }
        path.rewind()
        RulerGeometry.corners(r).forEachIndexed { i, p ->
            val v = t.pageToView(p)
            if (i == 0) path.moveTo(v.x, v.y) else path.lineTo(v.x, v.y)
        }
        path.close()
        line.strokeWidth = GHOST_PX
        line.pathEffect = d
        c.drawPath(path, line)
        line.pathEffect = null
        if (label != null) {
            val v = t.pageToView(RulerGeometry.center(r))
            text.textSize = t.lengthToView(5f)
            val w = text.measureText(label) / 2f + 6f
            val h = text.textSize * 0.7f
            c.drawRect(v.x - w, v.y - h, v.x + w, v.y + h, fill)
            c.drawText(label, v.x, v.y - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    private fun seg(c: Canvas, t: PageTransform, a: Vec2, b: Vec2) {
        val va = t.pageToView(a)
        val vb = t.pageToView(b)
        c.drawLine(va.x, va.y, vb.x, vb.y, line)
    }

    /** Arrow across the edge the ruler will step toward, a quarter of the way along the bar. */
    private fun drawHatchArrow(c: Canvas, r: Ruler, t: PageTransform) {
        val n = RulerGeometry.normal(r) * r.hatchDir.toFloat()
        val d = RulerGeometry.dir(r)
        val base = RulerGeometry.center(r) + d * (r.lengthMm * 0.22f) + n * (r.widthMm / 2f - 3f)
        val tip = base + n * 6f
        line.strokeWidth = EDGE_PX
        seg(c, t, base, tip)
        seg(c, t, tip, tip - n * 1.8f + d * 1.4f)
        seg(c, t, tip, tip - n * 1.8f - d * 1.4f)
    }

    private fun drawHandle(c: Canvas, v: Vec2, h: Handle, mm: Float, r: Ruler) {
        val rad = Guides.HANDLE_MM / 2f * mm
        c.drawCircle(v.x, v.y, rad, fill)
        line.strokeWidth = EDGE_PX
        c.drawCircle(v.x, v.y, rad - EDGE_PX / 2f, line)
        val k = rad * 0.5f
        if (h == Handle.MOVE) {
            // Four-way arrow.
            c.drawLine(v.x - k, v.y, v.x + k, v.y, line)
            c.drawLine(v.x, v.y - k, v.x, v.y + k, line)
            val a = k * 0.35f
            for ((dx, dy) in arrayOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val tx = v.x + dx * k
                val ty = v.y + dy * k
                c.drawLine(tx, ty, tx - dx * a + dy * a, ty - dy * a + dx * a, line)
                c.drawLine(tx, ty, tx - dx * a - dy * a, ty - dy * a - dx * a, line)
            }
        } else {
            // Arc with an arrowhead: turn.
            oval.set(v.x - k, v.y - k, v.x + k, v.y + k)
            val start = r.angleDeg + if (h == Handle.ROTATE_END) -60f else 120f
            c.drawArc(oval, start, 120f, false, line)
            val endRad = Math.toRadians((start + 120f).toDouble())
            val ex = v.x + k * Math.cos(endRad).toFloat()
            val ey = v.y + k * Math.sin(endRad).toFloat()
            c.drawCircle(ex, ey, EDGE_PX * 1.2f, solid)
        }
    }

    private fun drawLock(c: Canvas, v: Vec2, mm: Float) {
        val w = 3.2f * mm
        val h = 2.4f * mm
        c.drawRect(v.x - w / 2f - 4f, v.y - h * 1.3f - 4f, v.x + w / 2f + 4f, v.y + h + 4f, fill)
        c.drawRect(v.x - w / 2f, v.y, v.x + w / 2f, v.y + h, solid)
        line.strokeWidth = EDGE_PX
        oval.set(v.x - w * 0.3f, v.y - h * 0.9f, v.x + w * 0.3f, v.y + h * 0.5f)
        c.drawArc(oval, 180f, 180f, false, line)
    }

    private companion object {
        const val THIN_PX = 2f
        const val EDGE_PX = 3f
        const val SELECTED_PX = 6f
        const val GHOST_PX = 3f
        const val PAD_MM = 5f
    }
}
