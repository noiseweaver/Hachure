package app.hachure.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.hachure.geometry.PageTransform
import app.hachure.model.Stroke

/**
 * Draws committed strokes. Milestone 1 renders every tool as a constant-width black polyline;
 * per-tool renderers (pencil grain, fountain nib) arrive in Milestone 3.
 */
class PageRenderer {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke, t: PageTransform) {
        val width = t.lengthToView(stroke.widthMm).coerceAtLeast(1f)
        val pts = stroke.points
        if (pts.size == 1) {
            val p = pts[0]
            canvas.drawCircle(p.x * t.scale + t.offset.x, p.y * t.scale + t.offset.y, width / 2f, dotPaint)
            return
        }
        paint.strokeWidth = width
        path.rewind()
        path.moveTo(pts[0].x * t.scale + t.offset.x, pts[0].y * t.scale + t.offset.y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x * t.scale + t.offset.x, pts[i].y * t.scale + t.offset.y)
        }
        canvas.drawPath(path, paint)
    }
}
