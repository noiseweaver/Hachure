package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Stroke
import app.hachure.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

class HitTest {
    private fun pts(vararg xy: Float) = xy.toList().chunked(2).map { InkPoint(it[0], it[1], 0.5f, 0) }
    private val horizontal = Stroke(1, Tool.PENCIL, 0.4f, pts(0f, 10f, 50f, 10f))
    private val dot = Stroke(2, Tool.PENCIL, 0.4f, pts(80f, 80f))

    @Test fun crossingPathHits() {
        assertEquals(setOf(1L), Hit.strokesTouched(listOf(horizontal, dot), pts(25f, 0f, 25f, 20f), 1f))
    }

    @Test fun nearMissWithinRadiusHits() {
        // Path 1.5 mm above the line: reach is radius 1.4 + half width 0.2 = 1.6.
        assertEquals(setOf(1L), Hit.strokesTouched(listOf(horizontal), pts(10f, 8.5f, 20f, 8.5f), 1.4f))
        assertEquals(emptySet<Long>(), Hit.strokesTouched(listOf(horizontal), pts(10f, 8.0f, 20f, 8.0f), 1.4f))
    }

    @Test fun singlePointStrokeAndTapEraser() {
        assertEquals(setOf(2L), Hit.strokesTouched(listOf(horizontal, dot), pts(80.5f, 80.5f), 1f))
    }

    @Test fun segmentDistance() {
        assertEquals(0f, Hit.segmentSegment(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f), 1e-6f)
        assertEquals(1f, Hit.segmentSegment(0f, 0f, 10f, 0f, 0f, 1f, 10f, 1f), 1e-6f)
    }
}
