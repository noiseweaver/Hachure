package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Stroke
import kotlin.math.hypot

/** Hit testing for the stroke eraser. All values in page mm. */
object Hit {

    /** Ids of strokes that the eraser path [path] touches, with an eraser of [radiusMm]. */
    fun strokesTouched(strokes: List<Stroke>, path: List<InkPoint>, radiusMm: Float): Set<Long> {
        if (path.isEmpty()) return emptySet()
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in path) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y
        }
        val hits = HashSet<Long>()
        for (s in strokes) {
            val b = s.bounds()
            if (b.right < minX - radiusMm || b.left > maxX + radiusMm ||
                b.bottom < minY - radiusMm || b.top > maxY + radiusMm
            ) continue
            if (touches(s, path, radiusMm + s.widthMm / 2f)) hits += s.id
        }
        return hits
    }

    private fun touches(s: Stroke, path: List<InkPoint>, reach: Float): Boolean {
        val sp = s.points
        for (i in path.indices) {
            val a = path[i]
            val b = path[if (i + 1 < path.size) i + 1 else i]
            if (sp.size == 1) {
                if (pointSegment(sp[0].x, sp[0].y, a.x, a.y, b.x, b.y) <= reach) return true
                continue
            }
            for (k in 0 until sp.size - 1) {
                val c = sp[k]; val d = sp[k + 1]
                if (segmentSegment(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y) <= reach) return true
            }
        }
        return false
    }

    fun pointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    fun segmentSegment(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (intersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            minOf(pointSegment(ax, ay, cx, cy, dx, dy), pointSegment(bx, by, cx, cy, dx, dy)),
            minOf(pointSegment(cx, cy, ax, ay, bx, by), pointSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    private fun intersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float) =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

}
