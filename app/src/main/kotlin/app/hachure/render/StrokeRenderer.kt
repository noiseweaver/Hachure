package app.hachure.render

import android.graphics.Canvas
import app.hachure.geometry.PageTransform
import app.hachure.model.Stroke

/** Draws one committed stroke onto the page bitmap. */
interface StrokeRenderer {
    fun drawStroke(canvas: Canvas, stroke: Stroke, t: PageTransform)
}
