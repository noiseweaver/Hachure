package app.hachure.geometry

import kotlin.math.hypot

/** Immutable 2D vector. Units are whatever the caller uses (page mm in the model). */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)

    infix fun dot(o: Vec2): Float = x * o.x + y * o.y

    /** 2D cross product (z component). */
    infix fun cross(o: Vec2): Float = x * o.y - y * o.x

    fun length(): Float = hypot(x, y)

    fun distanceTo(o: Vec2): Float = (this - o).length()

    /** Unit vector, or [ZERO] for a zero-length vector. */
    fun normalized(): Vec2 {
        val len = length()
        return if (len == 0f) ZERO else this / len
    }

    /** Rotated 90 degrees counter-clockwise in a y-up frame (clockwise on screen, where y points down). */
    fun perpendicular(): Vec2 = Vec2(-y, x)

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}
