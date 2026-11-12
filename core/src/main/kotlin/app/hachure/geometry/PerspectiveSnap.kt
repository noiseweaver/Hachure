package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Perspective
import app.hachure.model.Ruler
import kotlin.math.abs
import kotlin.math.acos

/**
 * Snapping strokes to perspective: the first few mm of a stroke show where it is heading; if that
 * is close to the line toward a vanishing point the stroke becomes a straight segment that way.
 */
object PerspectiveSnap {
    /** Length of stroke used to read its initial direction. */
    const val LEAD_MM = 5f
    const val DEFAULT_TOLERANCE_DEG = 8f

    /** Direction of the stroke over its first [leadMm]; null if it never gets that far from its start. */
    fun initialDirection(points: List<InkPoint>, leadMm: Float = LEAD_MM): Vec2? {
        if (points.size < 2) return null
        val s = Vec2(points[0].x, points[0].y)
        for (p in points) {
            val v = Vec2(p.x, p.y) - s
            if (v.length() >= leadMm) return v.normalized()
        }
        return null
    }

    /** Angle in degrees between two undirected lines with unit directions [a] and [b], 0..90. */
    fun lineAngle(a: Vec2, b: Vec2): Float =
        Math.toDegrees(acos(abs(a dot b).coerceIn(0f, 1f)).toDouble()).toFloat()

    /**
     * Directions a stroke starting at [start] may snap to: toward each active vanishing point,
     * and true horizontal and vertical in 1 and 2-point perspective (where those lines stay
     * parallel to the picture plane). In 3-point every line converges, so there are none.
     */
    fun candidates(p: Perspective, start: Vec2): List<Vec2> {
        val out = ArrayList<Vec2>(5)
        for (vp in p.active) {
            val d = (PerspectiveGeometry.pos(vp) - start)
            if (d.length() >= 1f) out += d.normalized()
        }
        if (p.points == 1 || p.points == 2) {
            out += Vec2(1f, 0f)
            out += Vec2(0f, 1f)
        }
        return out
    }

    /** The candidate direction that best matches the stroke's initial direction, within tolerance. */
    fun direction(points: List<InkPoint>, p: Perspective, toleranceDeg: Float = DEFAULT_TOLERANCE_DEG): Vec2? {
        if (p.points == 0) return null
        val lead = initialDirection(points) ?: return null
        var best: Vec2? = null
        var bestAngle = toleranceDeg
        for (c in candidates(p, Vec2(points[0].x, points[0].y))) {
            val a = lineAngle(lead, c)
            if (a <= bestAngle) {
                best = c
                bestAngle = a
            }
        }
        return best
    }

    /** The stroke as a straight segment from its start along the matched direction, or null. */
    fun apply(points: List<InkPoint>, p: Perspective, toleranceDeg: Float = DEFAULT_TOLERANCE_DEG): List<InkPoint>? {
        val d = direction(points, p, toleranceDeg) ?: return null
        val s = Vec2(points[0].x, points[0].y)
        return Snap.project(points, Edge(s, s + d))
    }
}

/**
 * Which guide a stroke snaps to. Order: a ruler edge within the snap band wins, then a
 * perspective match, then nothing (freehand).
 */
object SnapResolver {
    sealed interface Result {
        val points: List<InkPoint>
    }

    data class OnRuler(override val points: List<InkPoint>, val hit: Snap.Hit) : Result
    data class OnPerspective(override val points: List<InkPoint>) : Result
    data class Freehand(override val points: List<InkPoint>) : Result

    fun resolve(
        points: List<InkPoint>,
        rulers: List<Ruler>,
        perspective: Perspective?,
        enabled: Boolean,
        bandMm: Float = Snap.DEFAULT_BAND_MM,
    ): Result {
        if (!enabled || points.isEmpty()) return Freehand(points)
        val hit = Snap.nearestEdge(rulers, Vec2(points[0].x, points[0].y), bandMm)
        if (hit != null) return OnRuler(Snap.project(points, hit.edge), hit)
        if (perspective != null) PerspectiveSnap.apply(points, perspective)?.let { return OnPerspective(it) }
        return Freehand(points)
    }
}
