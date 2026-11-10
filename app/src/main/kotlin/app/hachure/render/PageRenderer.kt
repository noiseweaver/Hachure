package app.hachure.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import app.hachure.geometry.PageTransform
import app.hachure.model.DotGrid
import app.hachure.model.Stroke
import app.hachure.model.Tool

/**
 * App-side stroke renderer. Used on non-Onyx devices, for brushes the Onyx SDK has no commit
 * renderer for (pencil, dash), and as the fallback if an SDK renderer throws.
 *
 * Pressure brushes approximate their A2 look with per-segment widths.
 */
class PageRenderer : StrokeRenderer {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_GRAY
        style = Paint.Style.FILL
    }

    override fun drawStroke(canvas: Canvas, stroke: Stroke, t: PageTransform) {
        val width = t.lengthToView(stroke.widthMm).coerceAtLeast(1f)
        val pts = stroke.points
        paint.color = if (stroke.tool == Tool.MARKER) MARKER_GRAY else Color.BLACK
        fill.color = paint.color
        if (pts.size == 1) {
            val p = pts[0]
            canvas.drawCircle(p.x * t.scale + t.offset.x, p.y * t.scale + t.offset.y, width / 2f, fill)
            return
        }
        when (stroke.tool) {
            Tool.FOUNTAIN, Tool.BRUSH, Tool.CHARCOAL, Tool.CHARCOAL2 -> drawPressure(canvas, stroke, t, width)
            else -> {
                paint.strokeWidth = width
                paint.pathEffect = if (stroke.tool == Tool.DASH) {
                    DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f)
                } else {
                    null
                }
                path.rewind()
                path.moveTo(pts[0].x * t.scale + t.offset.x, pts[0].y * t.scale + t.offset.y)
                for (i in 1 until pts.size) {
                    path.lineTo(pts[i].x * t.scale + t.offset.x, pts[i].y * t.scale + t.offset.y)
                }
                canvas.drawPath(path, paint)
                paint.pathEffect = null
            }
        }
    }

    /** Width follows pressure: 30% at no pressure, 100% at full. */
    private fun drawPressure(canvas: Canvas, stroke: Stroke, t: PageTransform, width: Float) {
        val pts = stroke.points
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            paint.strokeWidth = (width * (0.3f + 0.7f * (a.pressure + b.pressure) / 2f)).coerceAtLeast(1f)
            canvas.drawLine(
                a.x * t.scale + t.offset.x, a.y * t.scale + t.offset.y,
                b.x * t.scale + t.offset.x, b.y * t.scale + t.offset.y,
                paint,
            )
        }
    }

    /** Draws the dot grid for the page area in view rect [l],[top],[r],[b] (px). */
    fun drawGrid(canvas: Canvas, grid: DotGrid, t: PageTransform, l: Float, top: Float, r: Float, b: Float) {
        val radius = (t.lengthToView(grid.dotDiameterMm) / 2f).coerceAtLeast(1f)
        val pl = t.lengthToPage(l - t.offset.x)
        val pt = t.lengthToPage(top - t.offset.y)
        val pr = t.lengthToPage(r - t.offset.x)
        val pb = t.lengthToPage(b - t.offset.y)
        grid.forEachDot(pl, pt, pr, pb) { x, y ->
            canvas.drawCircle(x * t.scale + t.offset.x, y * t.scale + t.offset.y, radius, gridPaint)
        }
    }

    private companion object {
        /** Light enough to read as paper texture on e-ink, dark enough to survive A2 dithering. */
        const val GRID_GRAY = 0xFF9A9A9A.toInt()
        const val MARKER_GRAY = 0xFF555555.toInt()
    }
}
