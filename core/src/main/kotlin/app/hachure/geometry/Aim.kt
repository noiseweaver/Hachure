package app.hachure.geometry

import app.hachure.model.Perspective
import app.hachure.model.Ruler
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Perspective hatching: a ruler that points at a vanishing point. Its marked edge (the one on
 * the normal side, [AIMED_SIDE]) always passes through the point, wherever the ruler is moved
 * and after every hatch step, so stepped hatching fans out from the vanishing point.
 */
object Aim {
    const val AIMED_SIDE = 1

    /** True when [r] is aimed at a vanishing point that is in use. */
    fun active(r: Ruler, p: Perspective?): Boolean = p != null && r.aimVp in 1..p.points

    /** [r] turned about its centre so its aimed edge passes through its vanishing point. */
    fun apply(r: Ruler, p: Perspective?): Ruler {
        if (p == null || !active(r, p)) return r
        return toward(r, PerspectiveGeometry.pos(p.vps[r.aimVp - 1]))
    }

    fun toward(r: Ruler, target: Vec2): Ruler {
        val v = target - RulerGeometry.center(r)
        val d = v.length()
        val half = r.widthMm / 2f
        // Inside the bar there is no tangent; leave the ruler as it is.
        if (d <= half) return r
        val base = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
        val off = Math.toDegrees(asin((half / d).toDouble())).toFloat()
        // Two orientations put the normal-side edge through the target; keep the nearer one so
        // the ruler's ends (and its hatch direction) do not swap under the user.
        val a = Ruler.wrapAngle(base - off)
        val b = Ruler.wrapAngle(base + 180f + off)
        fun gap(x: Float): Float {
            val g = abs(x - Ruler.wrapAngle(r.angleDeg)) % 360f
            return if (g > 180f) 360f - g else g
        }
        return r.copy(angleDeg = if (gap(a) <= gap(b)) a else b)
    }

    /** Cycles the aim: none, VP1, ... up to the points in use, back to none. */
    fun next(r: Ruler, p: Perspective): Ruler {
        val n = if (r.aimVp >= p.points) 0 else r.aimVp + 1
        return apply(r.copy(aimVp = n), p)
    }
}
