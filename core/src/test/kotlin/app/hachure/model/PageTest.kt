package app.hachure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTest {
    private fun stroke(id: Long) = Stroke(id, Tool.PENCIL, 0.3f, listOf(InkPoint(0f, 0f, 1f, 0)))

    @Test fun idsIncreaseAndSkipPastLoadedIds() {
        val page = Page(100f, 100f)
        assertEquals(1L, page.newStrokeId())
        page.add(stroke(10))
        assertEquals(11L, page.newStrokeId())
    }

    @Test fun snapshotIsDetached() {
        val page = Page(100f, 100f)
        page.add(stroke(1))
        val snap = page.snapshot()
        page.clear()
        assertEquals(1, snap.size)
        assertTrue(page.strokes.isEmpty())
    }

    @Test fun noteAir2PageSize() {
        assertEquals(157.10f, Page.NOTE_AIR_2_WIDTH_MM, 0.01f)
        assertEquals(209.46f, Page.NOTE_AIR_2_HEIGHT_MM, 0.01f)
    }

    @Test fun rulersAddReplaceRemove() {
        val p = Page(100f, 100f)
        val id = p.newGuideId()
        p.putRuler(Ruler(id, 10f, 10f, 0f))
        p.putRuler(Ruler(p.newGuideId(), 20f, 20f, 0f))
        p.putRuler(p.ruler(id)!!.copy(locked = true))
        assertEquals(2, p.rulers.size)
        assertEquals(true, p.rulers[0].locked)
        p.putRuler(Ruler(9, 0f, 0f, 0f))
        assertEquals(10L, p.newGuideId())
        assertEquals(true, p.removeRuler(id))
        assertEquals(false, p.removeRuler(id))
        assertEquals(null, p.ruler(id))
    }

    @Test fun clearingStrokesKeepsRulers() {
        val p = Page(100f, 100f)
        p.putRuler(Ruler(1, 10f, 10f, 0f))
        p.clear()
        assertEquals(1, p.rulers.size)
    }
}
