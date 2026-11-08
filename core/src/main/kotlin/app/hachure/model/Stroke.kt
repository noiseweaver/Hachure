package app.hachure.model

/** A committed stroke. Immutable; edits produce new strokes. */
data class Stroke(
    val id: Long,
    val tool: Tool,
    /** Nominal width in mm (the tool decides how pressure modulates it). */
    val widthMm: Float,
    val points: List<InkPoint>,
) {
    init {
        require(widthMm > 0f) { "widthMm must be positive, was $widthMm" }
        require(points.isNotEmpty()) { "a stroke needs at least one point" }
    }

    /** Axis-aligned bounds in mm, padded by half the width. */
    fun bounds(): Rect {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = widthMm / 2f
        return Rect(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }
}

/** Axis-aligned rectangle in mm. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
