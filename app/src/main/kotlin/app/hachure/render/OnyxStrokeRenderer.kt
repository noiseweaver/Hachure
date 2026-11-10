package app.hachure.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import app.hachure.geometry.PageTransform
import app.hachure.model.Stroke
import app.hachure.model.Tool
import com.onyx.android.sdk.data.note.ShapeCreateArgs
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPen
import com.onyx.android.sdk.pen.NeoCharcoalPenV2
import com.onyx.android.sdk.pen.NeoFountainPen
import com.onyx.android.sdk.pen.NeoMarkerPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.PenRenderArgs

/**
 * Commits strokes with the Onyx SDK's own pen renderers, so the committed stroke matches the
 * live A2 ink.
 *
 * [V] Every class and signature used here exists in onyxsdk-pen 1.4.12 (javap). Argument meaning
 * is read from the bytecode: fountain (width, width, maxPressure), brush (width, maxPressure),
 * marker (width). [I] These are the renderers the A2 live layer uses. [G] The result matches the
 * live ink pixel for pixel; check on device. Pencil and dash have no SDK renderer and are drawn
 * by [fallback]. Any SDK failure falls back for that brush for the rest of the session.
 */
class OnyxStrokeRenderer(
    private val context: Context,
    private val maxPressure: Float,
    private val fallback: PageRenderer,
) : StrokeRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val identity = Matrix()
    private val failed = HashSet<Tool>()

    /** Last SDK failure, for the debug overlay. */
    var lastError: String? = null
        private set

    override fun drawStroke(canvas: Canvas, stroke: Stroke, t: PageTransform) {
        if (stroke.tool in failed || stroke.points.size < 2 || !hasSdkRenderer(stroke.tool)) {
            fallback.drawStroke(canvas, stroke, t)
            return
        }
        try {
            drawWithSdk(canvas, stroke, t)
        } catch (e: Throwable) {
            failed += stroke.tool
            lastError = "${stroke.tool.id}: ${e.javaClass.simpleName}: ${e.message}"
            fallback.drawStroke(canvas, stroke, t)
        }
    }

    private fun drawWithSdk(canvas: Canvas, stroke: Stroke, t: PageTransform) {
        val w = t.lengthToView(stroke.widthMm).coerceAtLeast(1f)
        val pts = ArrayList<TouchPoint>(stroke.points.size)
        for (p in stroke.points) {
            pts += TouchPoint(p.x * t.scale + t.offset.x, p.y * t.scale + t.offset.y, p.pressure * maxPressure, w, p.t)
        }
        paint.color = Color.BLACK
        paint.strokeWidth = w
        val args = ShapeCreateArgs().setMaxPressure(maxPressure)
        when (stroke.tool) {
            Tool.FOUNTAIN -> NeoFountainPen.drawStroke(canvas, paint, pts, w, w, maxPressure, false)
            Tool.BRUSH -> NeoBrushPen.drawStroke(canvas, paint, pts, w, maxPressure, false)
            Tool.MARKER -> NeoMarkerPen.drawStroke(canvas, paint, pts, w, false)
            Tool.CHARCOAL ->
                NeoCharcoalPen.drawNormalStroke(context, canvas, paint, pts, Color.BLACK, w, args, identity, false)
            Tool.CHARCOAL2 -> NeoCharcoalPenV2.drawNormalStroke(
                PenRenderArgs()
                    .setCanvas(canvas)
                    .setPaint(paint)
                    .setPoints(pts)
                    .setColor(Color.BLACK)
                    .setStrokeWidth(w)
                    .setPenType(NeoPenConfig.NEOPEN_PEN_TYPE_CHARCOAL_V2)
                    .setCreateArgs(args)
                    .setContentRect(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()))
                    .setRenderMatrix(identity)
                    .setScreenMatrix(identity),
            )
            else -> fallback.drawStroke(canvas, stroke, t)
        }
    }

    fun failedTools(): Set<Tool> = failed

    private fun hasSdkRenderer(tool: Tool) = when (tool) {
        Tool.FOUNTAIN, Tool.BRUSH, Tool.MARKER, Tool.CHARCOAL, Tool.CHARCOAL2 -> true
        Tool.PENCIL, Tool.DASH -> false
    }
}
