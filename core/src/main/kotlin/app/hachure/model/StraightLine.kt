package app.hachure.model

import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Points for a straight stroke from [ax],[ay] to [bx],[by] (mm). Pressure-based renderers need
 * more than two samples to draw evenly, so the line is resampled every [stepMm].
 */
fun straightLinePoints(
    ax: Float, ay: Float, bx: Float, by: Float,
    pressure: Float, durationMs: Long, stepMm: Float = 0.5f,
): List<InkPoint> {
    require(stepMm > 0f)
    val p = pressure.coerceIn(0f, 1f)
    val n = maxOf(1, ceil(hypot(bx - ax, by - ay) / stepMm).toInt())
    return List(n + 1) { i ->
        val f = i.toFloat() / n
        InkPoint(ax + (bx - ax) * f, ay + (by - ay) * f, p, (durationMs * f).toLong().coerceAtLeast(0))
    }
}
