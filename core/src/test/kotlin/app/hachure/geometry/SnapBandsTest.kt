package app.hachure.geometry

import app.hachure.model.Rect
import app.hachure.model.Ruler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SnapBandsTest {
    private fun Rect.has(p: Vec2) = p.x in left..right && p.y in top..bottom

    @Test fun axisAlignedEdgeIsOneExactRect() {
        val t = SnapBands.tiles(Edge(Vec2(10f, 50f), Vec2(110f, 50f)), 4f)
        assertEquals(listOf(Rect(6f, 46f, 114f, 54f)), t)
        assertEquals(1, SnapBands.tiles(Edge(Vec2(30f, 10f), Vec2(30f, 90f)), 4f).size)
    }

    @Test fun slantedEdgeIsARowOfSmallSquares() {
        val e = RulerGeometry.edge(Ruler(1, 80f, 100f, 37f, lengthMm = 130f), 1)
        val t = SnapBands.tiles(e, 4f)
        assertTrue(t.size in 10..40)
        t.forEach { assertEquals(10f, it.width, 1e-3f) }
        // Far smaller than the bounding box of the band.
        val area = t.sumOf { (it.width * it.height).toDouble() }
        assertTrue(area < 130.0 * 100.0 * 0.5)
    }

    @Test fun everySnappingStartPointIsInsideATile() {
        val rnd = Random(7)
        for (angle in listOf(0f, 12f, 45f, 90f, 133f, 271f)) {
            val r = Ruler(1, 80f, 100f, angle, lengthMm = 130f)
            val tiles = SnapBands.tiles(listOf(r), 4f)
            repeat(4000) {
                val p = Vec2(rnd.nextFloat() * 160f, rnd.nextFloat() * 210f)
                if (SnapBands.contains(listOf(r), p, 4f)) {
                    assertTrue("angle $angle, point $p not covered", tiles.any { it.has(p) })
                }
            }
        }
    }

    @Test fun bothEdgesOfEveryRuler() {
        val rulers = listOf(Ruler(1, 80f, 60f, 0f), Ruler(2, 80f, 120f, 90f))
        assertEquals(4, SnapBands.tiles(rulers, 4f).size)
        assertTrue(SnapBands.tiles(emptyList(), 4f).isEmpty())
    }
}
