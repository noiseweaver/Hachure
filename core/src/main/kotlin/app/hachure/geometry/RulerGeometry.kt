package app.hachure.geometry

import app.hachure.model.Ruler
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A straight drawing edge from [a] to [b], in page mm. */
data class Edge(val a: Vec2, val b: Vec2) {
    val dir: Vec2 get() = (b - a).normalized()
    val length: Float get() = a.distanceTo(b)

    /** Signed position of [p] along the edge, 0 at [a], in mm. */
    fun along(p: Vec2): Float = (p - a) dot dir

    /** Perpendicular distance from [p] to the edge's infinite line. */
    fun distance(p: Vec2): Float = abs((p - a) cross dir)

    fun pointAt(along: Float): Vec2 = a + dir * along
}

/** Pure geometry of a [Ruler]. */
object RulerGeometry {
    fun dir(r: Ruler): Vec2 {
        val rad = Math.toRadians(r.angleDeg.toDouble())
        return Vec2(cos(rad).toFloat(), sin(rad).toFloat())
    }

    /** Unit normal: [dir] turned a quarter turn clockwise on screen. Hatch direction +1 goes this way. */
    fun normal(r: Ruler): Vec2 = dir(r).perpendicular()

    fun center(r: Ruler) = Vec2(r.cx, r.cy)

    /** The edge on the normal side ([side] = +1) or the other side (-1). */
    fun edge(r: Ruler, side: Int): Edge {
        val d = dir(r) * (r.lengthMm / 2f)
        val o = center(r) + normal(r) * (side * r.widthMm / 2f)
        return Edge(o - d, o + d)
    }

    /** End points of the centre line: index 0 is the start, 1 the end. */
    fun end(r: Ruler, index: Int): Vec2 {
        val d = dir(r) * (r.lengthMm / 2f)
        return if (index == 0) center(r) - d else center(r) + d
    }

    /** Four corners in drawing order. */
    fun corners(r: Ruler): List<Vec2> {
        val e1 = edge(r, 1)
        val e2 = edge(r, -1)
        return listOf(e1.a, e1.b, e2.b, e2.a)
    }

    fun contains(r: Ruler, p: Vec2): Boolean {
        val v = p - center(r)
        return abs(v dot dir(r)) <= r.lengthMm / 2f && abs(v dot normal(r)) <= r.widthMm / 2f
    }

    fun movedTo(r: Ruler, center: Vec2) = r.copy(cx = center.x, cy = center.y)

    /**
     * The ruler turned about its centre so that its end (or, with [byStart], its start) points
     * at [toward]. With [snapStepDeg] > 0 the angle snaps to that step.
     */
    fun rotatedToward(r: Ruler, toward: Vec2, byStart: Boolean = false, snapStepDeg: Float = 0f): Ruler {
        val v = if (byStart) center(r) - toward else toward - center(r)
        if (v.length() == 0f) return r
        var deg = Math.toDegrees(kotlin.math.atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
        if (snapStepDeg > 0f) deg = (deg / snapStepDeg).roundToInt() * snapStepDeg
        return r.copy(angleDeg = Ruler.wrapAngle(deg))
    }
}
