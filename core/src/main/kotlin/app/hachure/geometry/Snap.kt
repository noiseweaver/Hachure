package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Ruler
import kotlin.math.hypot

/** Snapping strokes to ruler edges (option A: freehand live, projected on commit). */
object Snap {
    const val DEFAULT_BAND_MM = 4f

    data class Hit(val ruler: Ruler, val side: Int, val edge: Edge, val distanceMm: Float)

    /**
     * The ruler edge nearest to a stroke start at [p], if one lies within [bandMm]. Locked rulers
     * snap too. The band also reaches [bandMm] past each end of the edge.
     */
    fun nearestEdge(rulers: List<Ruler>, p: Vec2, bandMm: Float = DEFAULT_BAND_MM): Hit? {
        var best: Hit? = null
        for (r in rulers) {
            for (side in intArrayOf(1, -1)) {
                val e = RulerGeometry.edge(r, side)
                val d = e.distance(p)
                if (d > bandMm) continue
                val s = e.along(p)
                if (s < -bandMm || s > e.length + bandMm) continue
                if (best == null || d < best.distanceMm) best = Hit(r, side, e, d)
            }
        }
        return best
    }

    /**
     * Projects a stroke onto [edge]: a straight segment from the projection of the first point
     * to the projection of the last. Each point keeps its pressure and time and sits at the same
     * fraction of the length as it did along the freehand path, so the pressure profile survives.
     */
    fun project(points: List<InkPoint>, edge: Edge): List<InkPoint> {
        if (points.isEmpty()) return points
        val s0 = edge.along(Vec2(points.first().x, points.first().y))
        val s1 = edge.along(Vec2(points.last().x, points.last().y))
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] +
                hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        val total = cumulative.last()
        return points.mapIndexed { i, p ->
            val f = if (total == 0f) 0f else cumulative[i] / total
            val q = edge.pointAt(s0 + (s1 - s0) * f)
            p.copy(x = q.x, y = q.y)
        }
    }
}
