package app.hachure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrokeTest {
    private val eps = 1e-5f

    @Test fun boundsArePaddedByHalfWidth() {
        val s = Stroke(
            id = 1, tool = Tool.PEN, widthMm = 0.4f,
            points = listOf(InkPoint(1f, 2f, 0.5f, 0), InkPoint(5f, -1f, 0.5f, 10)),
        )
        val b = s.bounds()
        assertEquals(0.8f, b.left, eps)
        assertEquals(-1.2f, b.top, eps)
        assertEquals(5.2f, b.right, eps)
        assertEquals(2.2f, b.bottom, eps)
    }

    @Test fun rejectsEmptyStroke() {
        assertThrows(IllegalArgumentException::class.java) {
            Stroke(1, Tool.PEN, 0.4f, emptyList())
        }
    }

    @Test fun rejectsOutOfRangePressure() {
        assertThrows(IllegalArgumentException::class.java) { InkPoint(0f, 0f, 1.5f, 0) }
        assertThrows(IllegalArgumentException::class.java) { InkPoint(0f, 0f, -0.1f, 0) }
    }

    @Test fun toolIdsRoundTrip() {
        for (t in Tool.entries) assertEquals(t, Tool.fromId(t.id))
        assertThrows(IllegalArgumentException::class.java) { Tool.fromId("crayon") }
    }
}
