package app.hachure.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class Vec2Test {
    private val eps = 1e-5f

    @Test fun arithmetic() {
        val a = Vec2(1f, 2f)
        val b = Vec2(3f, -4f)
        assertEquals(Vec2(4f, -2f), a + b)
        assertEquals(Vec2(-2f, 6f), a - b)
        assertEquals(Vec2(2f, 4f), a * 2f)
        assertEquals(Vec2(0.5f, 1f), a / 2f)
        assertEquals(Vec2(-1f, -2f), -a)
    }

    @Test fun dotAndCross() {
        assertEquals(-5f, Vec2(1f, 2f) dot Vec2(3f, -4f), eps)
        assertEquals(1f, Vec2(1f, 0f) cross Vec2(0f, 1f), eps)
        assertEquals(-1f, Vec2(0f, 1f) cross Vec2(1f, 0f), eps)
    }

    @Test fun lengthAndDistance() {
        assertEquals(5f, Vec2(3f, 4f).length(), eps)
        assertEquals(5f, Vec2(1f, 1f).distanceTo(Vec2(4f, 5f)), eps)
    }

    @Test fun normalized() {
        val n = Vec2(3f, 4f).normalized()
        assertEquals(0.6f, n.x, eps)
        assertEquals(0.8f, n.y, eps)
        assertEquals(Vec2.ZERO, Vec2.ZERO.normalized())
    }

    @Test fun perpendicularIsOrthogonalAndSameLength() {
        val v = Vec2(2f, 7f)
        val p = v.perpendicular()
        assertEquals(0f, v dot p, eps)
        assertEquals(v.length(), p.length(), eps)
    }
}
