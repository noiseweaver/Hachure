package app.hachure.geometry

import app.hachure.model.Perspective
import app.hachure.model.VanishingPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What a finger can grab on the perspective guides: a vanishing point (0..2) or the horizon. */
sealed interface PerspectiveItem {
    data class Vp(val index: Int) : PerspectiveItem
    data object Horizon : PerspectiveItem
}

/**
 * Pure geometry and handling rules of perspective guides.
 *
 * Lock rules: a locked vanishing point or a locked horizon has no handle. A locked horizon also
 * keeps VP1 and VP2 on it: they still slide along the line but cannot leave it. "Lock all"
 * removes every handle. Locks never affect snapping.
 */
object PerspectiveGeometry {
    const val HANDLE_MM = 9f

    /** How far an off-page vanishing point's edge marker sits inside the page edge. */
    const val MARKER_INSET_MM = 7f

    /** Vanishing points stay within this many page sizes of the page. */
    const val MAX_REACH = 4f

    fun pos(vp: VanishingPoint) = Vec2(vp.x, vp.y)

    /**
     * The horizon as a point and unit direction: horizontal through VP1 in 1-point, through VP1
     * and VP2 otherwise. Null when perspective is off.
     */
    fun horizon(p: Perspective): Pair<Vec2, Vec2>? {
        if (p.points == 0) return null
        val a = pos(p.vps[0])
        if (p.points == 1) return a to Vec2(1f, 0f)
        val d = (pos(p.vps[1]) - a).normalized()
        return a to (if (d == Vec2.ZERO) Vec2(1f, 0f) else d)
    }

    /** The part of the infinite line through [point] along [dir] inside the page, if any. */
    fun clipLine(point: Vec2, dir: Vec2, w: Float, h: Float): Pair<Vec2, Vec2>? {
        var t0 = Float.NEGATIVE_INFINITY
        var t1 = Float.POSITIVE_INFINITY
        // Slab clipping on x then y.
        for (axis in 0..1) {
            val o = if (axis == 0) point.x else point.y
            val d = if (axis == 0) dir.x else dir.y
            val max = if (axis == 0) w else h
            if (abs(d) < 1e-6f) {
                if (o < 0f || o > max) return null
            } else {
                var a = (0f - o) / d
                var b = (max - o) / d
                if (a > b) a = b.also { b = a }
                if (a > t0) t0 = a
                if (b < t1) t1 = b
            }
        }
        if (t0 >= t1) return null
        return (point + dir * t0) to (point + dir * t1)
    }

    /** Guide lines through [vp], evenly spread, clipped to the page. [rays] lines in all. */
    fun rays(vp: VanishingPoint, rays: Int, w: Float, h: Float): List<Pair<Vec2, Vec2>> {
        val out = ArrayList<Pair<Vec2, Vec2>>(rays)
        val p = pos(vp)
        for (k in 0 until rays) {
            val a = Math.PI * k / rays
            clipLine(p, Vec2(cos(a).toFloat(), sin(a).toFloat()), w, h)?.let { out += it }
        }
        return out
    }

    fun onPage(v: Vec2, w: Float, h: Float) = v.x in 0f..w && v.y in 0f..h

    /**
     * Where a vanishing point's handle is drawn and grabbed: at the point when it is on the
     * page, otherwise at an edge marker on the way from the page centre to the point.
     */
    fun handlePosition(vp: VanishingPoint, w: Float, h: Float): Vec2 {
        val p = pos(vp)
        val inset = MARKER_INSET_MM
        if (p.x in inset..(w - inset) && p.y in inset..(h - inset)) return p
        val c = Vec2(w / 2f, h / 2f)
        val v = p - c
        // Scale the ray from the centre so it ends on the inset rectangle.
        var s = 1f
        if (abs(v.x) > 1e-6f) s = minOf(s, (w / 2f - inset) / abs(v.x))
        if (abs(v.y) > 1e-6f) s = minOf(s, (h / 2f - inset) / abs(v.y))
        return c + v * s
    }

    /** Unit direction from the edge marker toward an off-page point; null when it is on the page. */
    fun markerDirection(vp: VanishingPoint, w: Float, h: Float): Vec2? {
        val p = pos(vp)
        if (onPage(p, w, h)) return null
        val d = (p - handlePosition(vp, w, h)).normalized()
        return if (d == Vec2.ZERO) null else d
    }

    /** Handle of the horizon: the visible point of the line nearest the page centre, if any. */
    fun horizonHandle(p: Perspective, w: Float, h: Float): Vec2? {
        val (o, d) = horizon(p) ?: return null
        val seg = clipLine(o, d, w, h) ?: return null
        val c = Vec2(w / 2f, h / 2f)
        val len = seg.first.distanceTo(seg.second)
        if (len < HANDLE_MM) return null
        val u = (seg.second - seg.first) / len
        // Keep clear of the page edge, and off the vanishing point handles.
        var t = ((c - seg.first) dot u).coerceIn(HANDLE_MM / 2f, len - HANDLE_MM / 2f)
        var pos = seg.first + u * t
        for (vp in p.active) {
            if (handlePosition(vp, w, h).distanceTo(pos) < HANDLE_MM * 1.2f) {
                t = (t + HANDLE_MM * 1.5f).coerceAtMost(len - HANDLE_MM / 2f)
                pos = seg.first + u * t
            }
        }
        return pos
    }

    fun locked(p: Perspective, item: PerspectiveItem, lockAll: Boolean): Boolean = lockAll || when (item) {
        is PerspectiveItem.Vp -> p.vps[item.index].locked
        PerspectiveItem.Horizon -> p.horizonLocked
    }

    /** The unlocked handle under a finger at [at], nearest first; vanishing points beat the horizon. */
    fun handleAt(p: Perspective, at: Vec2, w: Float, h: Float, lockAll: Boolean, reachMm: Float = HANDLE_MM / 2f + 1.5f): PerspectiveItem? {
        if (p.points == 0 || lockAll) return null
        var best: PerspectiveItem? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until p.points) {
            if (p.vps[i].locked) continue
            val d = handlePosition(p.vps[i], w, h).distanceTo(at)
            if (d <= reachMm && d < bestD) {
                best = PerspectiveItem.Vp(i)
                bestD = d
            }
        }
        if (best == null && !p.horizonLocked && p.points >= 1) {
            val hh = horizonHandle(p, w, h)
            if (hh != null && hh.distanceTo(at) <= reachMm) best = PerspectiveItem.Horizon
        }
        return best
    }

    /**
     * The perspective after a finger that grabbed [item] moved by [delta] since it went down
     * on [start]. A vanishing point follows the finger (also from its edge marker, so repeated
     * drags push it further off the page). The horizon moves up and down only and takes VP1 and
     * VP2 with it. With the horizon locked, VP1 and VP2 slide along it.
     */
    fun dragged(start: Perspective, item: PerspectiveItem, delta: Vec2, w: Float, h: Float): Perspective {
        fun clamp(v: Vec2) = Vec2(
            v.x.coerceIn(-MAX_REACH * w, (MAX_REACH + 1f) * w),
            v.y.coerceIn(-MAX_REACH * h, (MAX_REACH + 1f) * h),
        )
        return when (item) {
            is PerspectiveItem.Vp -> {
                val vp = start.vps[item.index]
                var d = delta
                if (start.horizonLocked && item.index < 2 && item.index < start.points) {
                    val dir = horizon(start)!!.second
                    d = dir * (delta dot dir)
                }
                val n = clamp(pos(vp) + d)
                start.withVp(item.index, vp.copy(x = n.x, y = n.y))
            }
            PerspectiveItem.Horizon -> {
                var out = start
                for (i in 0 until minOf(2, start.points)) {
                    val vp = start.vps[i]
                    val n = clamp(Vec2(vp.x, vp.y + delta.y))
                    out = out.withVp(i, vp.copy(y = n.y))
                }
                out
            }
        }
    }

    fun toggledLock(p: Perspective, item: PerspectiveItem): Perspective = when (item) {
        is PerspectiveItem.Vp -> p.withVp(item.index, p.vps[item.index].let { it.copy(locked = !it.locked) })
        PerspectiveItem.Horizon -> p.copy(horizonLocked = !p.horizonLocked)
    }

    /** Next or previous density step. */
    fun raysChanged(p: Perspective, steps: Int): Perspective {
        val list = Perspective.RAY_STEPS
        val i = list.indexOfFirst { it >= p.rays }.let { if (it < 0) list.lastIndex else it }
        return p.copy(rays = list[(i + steps).coerceIn(0, list.lastIndex)])
    }
}
