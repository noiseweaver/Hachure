package app.hachure.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {
    private val eps = 1e-3f

    @Test fun roundTripWithOffset() {
        val t = PageTransform(scale = 8.937f, offset = Vec2(12f, -30f))
        val page = Vec2(10f, 20f)
        val back = t.viewToPage(t.pageToView(page))
        assertEquals(page.x, back.x, eps)
        assertEquals(page.y, back.y, eps)
    }

    @Test fun forDpiMapsMmToPhysicalPixels() {
        val t = PageTransform.forDpi(227f)
        assertEquals(227f, t.pageToView(Vec2(25.4f, 0f)).x, eps)
        assertEquals(227f, t.lengthToView(25.4f), eps)
        assertEquals(25.4f, t.lengthToPage(227f), eps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveScale() {
        PageTransform(scale = 0f)
    }
}
