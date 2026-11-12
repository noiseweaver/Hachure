package app.hachure.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import app.hachure.geometry.Aim
import app.hachure.geometry.Guides
import app.hachure.geometry.Handle
import app.hachure.geometry.PageTransform
import app.hachure.geometry.PerspectiveGeometry
import app.hachure.geometry.PerspectiveItem
import app.hachure.geometry.RulerGeometry
import app.hachure.geometry.Vec2
import app.hachure.model.Perspective
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
    private var faint: DashPathEffect? = null

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

    fun draw(c: Canvas, r: Ruler, t: PageTransform, selected: Boolean, locked: Boolean, aimed: Boolean = false) {
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
            val handles = if (aimed) listOf(Handle.MOVE) else Handle.entries
            for (h in handles) drawHandle(c, t.pageToView(Guides.handlePosition(r, h)), h, mm, r)
        }
        if (aimed) {
            // Mark the edge that converges on the vanishing point: a solid wedge at each end.
            val e = RulerGeometry.edge(r, Aim.AIMED_SIDE)
            val d = RulerGeometry.dir(r)
            for ((p, s) in listOf(e.a to 1f, e.b to -1f)) wedge(c, t, p, p + d * (4f * s), p + d * (2f * s) - n * 2.5f)
        }
    }

    private fun wedge(c: Canvas, t: PageTransform, a: Vec2, b: Vec2, tip: Vec2) {
        path.rewind()
        val va = t.pageToView(a)
        val vb = t.pageToView(b)
        val vt = t.pageToView(tip)
        path.moveTo(va.x, va.y)
        path.lineTo(vb.x, vb.y)
        path.lineTo(vt.x, vt.y)
        path.close()
        c.drawPath(path, solid)
    }

    // Perspective

    /** View bounds of one perspective handle (vanishing point or horizon). */
    fun itemBounds(p: Perspective, item: PerspectiveItem, t: PageTransform, w: Float, h: Float, out: Rect) {
        val pos = when (item) {
            is PerspectiveItem.Vp -> PerspectiveGeometry.handlePosition(p.vps[item.index], w, h)
            PerspectiveItem.Horizon -> PerspectiveGeometry.horizonHandle(p, w, h)
        }
        if (pos == null) {
            out.setEmpty()
            return
        }
        val v = t.pageToView(pos)
        val pad = t.lengthToView(PerspectiveGeometry.HANDLE_MM / 2f + 4f).toInt() + 4
        out.set(v.x.toInt() - pad, v.y.toInt() - pad, v.x.toInt() + pad, v.y.toInt() + pad)
    }

    /** View bounds of the visible horizon line; empty when it does not cross the page. */
    fun horizonBounds(p: Perspective, t: PageTransform, w: Float, h: Float, out: Rect) {
        val seg = PerspectiveGeometry.horizon(p)?.let { (o, d) -> PerspectiveGeometry.clipLine(o, d, w, h) }
        if (seg == null) {
            out.setEmpty()
            return
        }
        val a = t.pageToView(seg.first)
        val b = t.pageToView(seg.second)
        out.set(minOf(a.x, b.x).toInt() - 6, minOf(a.y, b.y).toInt() - 6, maxOf(a.x, b.x).toInt() + 6, maxOf(a.y, b.y).toInt() + 6)
    }

    fun drawPerspective(c: Canvas, p: Perspective, t: PageTransform, w: Float, h: Float, lockAll: Boolean, selected: PerspectiveItem?) {
        if (p.points == 0) return
        if (p.linesVisible) {
            val fd = faint ?: DashPathEffect(floatArrayOf(3f, 7f), 0f).also { faint = it }
            line.strokeWidth = 1f
            line.pathEffect = fd
            for (vp in p.active) {
                for ((a, b) in PerspectiveGeometry.rays(vp, p.rays, w, h)) seg(c, t, a, b)
            }
            line.pathEffect = null
        }
        PerspectiveGeometry.horizon(p)?.let { (o, d) ->
            PerspectiveGeometry.clipLine(o, d, w, h)?.let { (a, b) ->
                line.strokeWidth = if (selected == PerspectiveItem.Horizon) EDGE_PX + 2f else THIN_PX
                seg(c, t, a, b)
            }
        }
        val rad = t.lengthToView(PerspectiveGeometry.HANDLE_MM / 2f)
        if (!PerspectiveGeometry.locked(p, PerspectiveItem.Horizon, lockAll)) {
            PerspectiveGeometry.horizonHandle(p, w, h)?.let { pos ->
                val v = t.pageToView(pos)
                disc(c, v, rad, selected == PerspectiveItem.Horizon)
                // Up and down arrow.
                val k = rad * 0.55f
                val a = k * 0.45f
                line.strokeWidth = EDGE_PX
                c.drawLine(v.x, v.y - k, v.x, v.y + k, line)
                for (s in floatArrayOf(-1f, 1f)) {
                    c.drawLine(v.x, v.y + s * k, v.x - a, v.y + s * (k - a), line)
                    c.drawLine(v.x, v.y + s * k, v.x + a, v.y + s * (k - a), line)
                }
            }
        }
        for (i in 0 until p.points) {
            val vp = p.vps[i]
            val item = PerspectiveItem.Vp(i)
            val v = t.pageToView(PerspectiveGeometry.handlePosition(vp, w, h))
            val locked = PerspectiveGeometry.locked(p, item, lockAll)
            val dir = PerspectiveGeometry.markerDirection(vp, w, h)
            if (!locked) disc(c, v, rad, selected == item)
            text.textSize = rad * 0.8f
            line.strokeWidth = EDGE_PX
            if (dir == null) {
                // On the page: cross hair on the exact point, number beside it.
                val k = if (locked) rad * 0.45f else rad
                c.drawLine(v.x - k, v.y, v.x + k, v.y, line)
                c.drawLine(v.x, v.y - k, v.x, v.y + k, line)
                val off = if (locked) rad * 0.75f else rad * 0.45f
                c.drawCircle(v.x + off, v.y - off, text.textSize * 0.62f, fill)
                c.drawText((i + 1).toString(), v.x + off, v.y - off - (text.ascent() + text.descent()) / 2f, text)
            } else {
                // Off the page: an arrow pointing the way, number behind it.
                val tip = Vec2(v.x + dir.x * rad * 0.8f, v.y + dir.y * rad * 0.8f)
                val back = Vec2(v.x + dir.x * rad * 0.1f, v.y + dir.y * rad * 0.1f)
                val side = Vec2(-dir.y, dir.x) * (rad * 0.4f)
                path.rewind()
                path.moveTo(tip.x, tip.y)
                path.lineTo(back.x + side.x, back.y + side.y)
                path.lineTo(back.x - side.x, back.y - side.y)
                path.close()
                c.drawPath(path, solid)
                val tx = v.x - dir.x * rad * 0.45f
                val ty = v.y - dir.y * rad * 0.45f
                c.drawText((i + 1).toString(), tx, ty - (text.ascent() + text.descent()) / 2f, text)
            }
        }
    }

    private fun disc(c: Canvas, v: Vec2, rad: Float, selected: Boolean) {
        c.drawCircle(v.x, v.y, rad, fill)
        line.strokeWidth = if (selected) SELECTED_PX else EDGE_PX
        c.drawCircle(v.x, v.y, rad - line.strokeWidth / 2f, line)
    }

    /** Dashed outline of where the dragged perspective item will land. */
    fun drawPerspectiveGhost(c: Canvas, p: Perspective, item: PerspectiveItem, t: PageTransform, w: Float, h: Float) {
        val d = dash ?: DashPathEffect(floatArrayOf(t.lengthToView(2f), t.lengthToView(1.2f)), 0f).also { dash = it }
        line.strokeWidth = GHOST_PX
        line.pathEffect = d
        val rad = t.lengthToView(PerspectiveGeometry.HANDLE_MM / 2f)
        val moved = when (item) {
            is PerspectiveItem.Vp -> listOf(item.index)
            PerspectiveItem.Horizon -> (0 until minOf(2, p.points)).toList()
        }
        for (i in moved) {
            val v = t.pageToView(PerspectiveGeometry.handlePosition(p.vps[i], w, h))
            c.drawCircle(v.x, v.y, rad, line)
        }
        if (item == PerspectiveItem.Horizon || (item is PerspectiveItem.Vp && item.index < 2)) {
            PerspectiveGeometry.horizon(p)?.let { (o, dir) ->
                PerspectiveGeometry.clipLine(o, dir, w, h)?.let { (a, b) -> seg(c, t, a, b) }
            }
        }
        line.pathEffect = null
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
