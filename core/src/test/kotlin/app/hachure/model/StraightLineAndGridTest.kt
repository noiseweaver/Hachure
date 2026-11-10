package app.hachure.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StraightLineAndGridTest {
    @Test fun lineIsResampledWithExactEnds() {
        val pts = straightLinePoints(0f, 0f, 10f, 0f, 0.6f, 100)
        assertEquals(21, pts.size)
        assertEquals(10f, pts.last().x, 1e-5f)
        assertEquals(100L, pts.last().t)
        assertEquals(0.6f, pts[5].pressure, 1e-6f)
    }

    @Test fun zeroLengthLineHasTwoPoints() {
        assertEquals(2, straightLinePoints(3f, 3f, 3f, 3f, 1f, 0).size)
    }

    @Test fun gridCountsDotsInRect() {
        var n = 0
        DotGrid(5f).forEachDot(0f, 0f, 20f, 10f) { _, _ -> n++ }
        assertEquals(5 * 3, n)
        n = 0
        DotGrid(5f).forEachDot(1f, 1f, 4f, 4f) { _, _ -> n++ }
        assertEquals(0, n)
    }

    @Test fun widthPresetsIncrease() {
        for (t in Tool.entries) {
            assert(t.widthMm(WidthPreset.FINE) < t.widthMm(WidthPreset.MEDIUM))
            assert(t.widthMm(WidthPreset.MEDIUM) < t.widthMm(WidthPreset.BOLD))
        }
    }
}
