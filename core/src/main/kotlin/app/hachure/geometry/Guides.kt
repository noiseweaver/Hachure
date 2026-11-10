package app.hachure.geometry

import app.hachure.model.Perspective
import app.hachure.model.Ruler
import kotlin.math.abs

/** The three finger handles of a ruler. */
enum class Handle { MOVE, ROTATE_START, ROTATE_END }

/**
 * Rules for handling guides, kept free of Android so they can be unit tested.
 *
 * Lock rules: a locked ruler (its own lock, or "lock all") has no handles and cannot be dragged.
 * It still snaps strokes, and hatching still steps it, because that is the pen's doing and the
 * lock only guards against stray fingers.
 */
object Guides {
    /** Handle diameter: the fingertip minimum from the spec. */
    const val HANDLE_MM = 9f

    /** Distance of the rotate handles from the ends of the bar. */
    const val END_INSET_MM = 8f

    const val ANGLE_SNAP_DEG = 15f
    const val MAX_RULERS = 8

    data class Grab(val ruler: Ruler, val handle: Handle)

    fun locked(r: Ruler, lockAll: Boolean) = lockAll || r.locked

    fun handlePosition(r: Ruler, h: Handle): Vec2 {
        val c = RulerGeometry.center(r)
        val d = RulerGeometry.dir(r) * (r.lengthMm / 2f - END_INSET_MM)
        return when (h) {
            Handle.MOVE -> c
            Handle.ROTATE_START -> c - d
            Handle.ROTATE_END -> c + d
        }
    }

    /**
     * The handle under a finger at [p]: the nearest handle centre within [reachMm], skipping
     * locked rulers. Rulers later in the list are drawn on top and win ties.
     */
    fun handleAt(
        rulers: List<Ruler>,
        p: Vec2,
        lockAll: Boolean,
        reachMm: Float = HANDLE_MM / 2f + 1.5f,
        perspective: Perspective? = null,
    ): Grab? {
        var best: Grab? = null
        var bestD = Float.MAX_VALUE
        for (r in rulers.asReversed()) {
            if (locked(r, lockAll)) continue
            for (h in handles(r, perspective)) {
                val d = handlePosition(r, h).distanceTo(p)
                if (d <= reachMm && d < bestD) {
                    best = Grab(r, h)
                    bestD = d
                }
            }
        }
        return best
    }

    /** Handles a ruler offers: an aimed ruler takes its angle from the vanishing point, so it only moves. */
    fun handles(r: Ruler, perspective: Perspective?): List<Handle> =
        if (Aim.active(r, perspective)) listOf(Handle.MOVE) else Handle.entries

    /** Topmost ruler whose bar contains [p], locked or not (a tap selects it). */
    fun rulerAt(rulers: List<Ruler>, p: Vec2): Ruler? = rulers.lastOrNull { RulerGeometry.contains(it, p) }

    /**
     * Where a drag leaves the ruler. [start] is the ruler when the finger went down at [down];
     * the finger is now at [now]. Moving keeps the centre on the page so a ruler cannot be lost
     * off the edge. Rotation is about the centre.
     */
    fun dragged(
        start: Ruler,
        handle: Handle,
        down: Vec2,
        now: Vec2,
        pageWidthMm: Float,
        pageHeightMm: Float,
        snapStepDeg: Float = 0f,
    ): Ruler = when (handle) {
        Handle.MOVE -> {
            val c = RulerGeometry.center(start) + (now - down)
            RulerGeometry.movedTo(start, Vec2(c.x.coerceIn(0f, pageWidthMm), c.y.coerceIn(0f, pageHeightMm)))
        }
        Handle.ROTATE_START -> RulerGeometry.rotatedToward(start, now, byStart = true, snapStepDeg = snapStepDeg)
        Handle.ROTATE_END -> RulerGeometry.rotatedToward(start, now, byStart = false, snapStepDeg = snapStepDeg)
    }

    /**
     * Angle as shown to the user: 0 is horizontal, 45 rises to the right, 90 is vertical, in
     * [0, 180). (The model's angle runs clockwise because screen y points down.)
     */
    fun displayAngle(r: Ruler): Float = Ruler.normalizeAngle(-r.angleDeg)

    /** Sets the shown angle, keeping the ruler's ends (and so its hatch direction) the way they were. */
    fun withDisplayAngle(r: Ruler, deg: Float): Ruler {
        val a = Ruler.wrapAngle(-deg)
        val b = Ruler.wrapAngle(a + 180f)
        fun gap(x: Float): Float {
            val d = abs(x - Ruler.wrapAngle(r.angleDeg)) % 360f
            return if (d > 180f) 360f - d else d
        }
        return r.copy(angleDeg = if (gap(a) <= gap(b)) a else b)
    }

    /** A new horizontal ruler near the middle of the page, staggered so new ones do not stack. */
    fun newRuler(id: Long, pageWidthMm: Float, pageHeightMm: Float, existing: Int): Ruler {
        val length = Ruler.DEFAULT_LENGTH_MM.coerceAtMost(pageWidthMm * 0.8f)
        val y = pageHeightMm * 0.3f + (existing % MAX_RULERS) * (Ruler.DEFAULT_WIDTH_MM + 6f)
        return Ruler(id, pageWidthMm / 2f, y.coerceAtMost(pageHeightMm - Ruler.DEFAULT_WIDTH_MM), 0f, lengthMm = length)
    }
}
