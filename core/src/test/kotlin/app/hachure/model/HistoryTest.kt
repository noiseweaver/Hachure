package app.hachure.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun stroke(id: Long) = Stroke(id, Tool.PENCIL, 0.3f, listOf(InkPoint(id.toFloat(), 0f, 1f, 0)))
    private fun ids(p: Page) = p.strokes.map { it.id }

    @Test fun undoRedoAdd() {
        val p = Page(100f, 100f); val h = History(p)
        h.add(stroke(1)); h.add(stroke(2))
        assertEquals(listOf(2L), h.undo().map { it.id })
        assertEquals(listOf(1L), ids(p))
        assertEquals(listOf(2L), h.redo().map { it.id })
        assertEquals(listOf(1L, 2L), ids(p))
    }

    @Test fun eraseRestoresPaintOrder() {
        val p = Page(100f, 100f); val h = History(p)
        (1L..4L).forEach { h.add(stroke(it)) }
        h.erase(setOf(2L, 4L))
        assertEquals(listOf(1L, 3L), ids(p))
        h.undo()
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(p))
        h.redo()
        assertEquals(listOf(1L, 3L), ids(p))
    }

    @Test fun clearIsUndoable() {
        val p = Page(100f, 100f); val h = History(p)
        h.add(stroke(1)); h.add(stroke(2))
        assertEquals(2, h.clear().size)
        assertTrue(p.strokes.isEmpty())
        h.undo()
        assertEquals(listOf(1L, 2L), ids(p))
    }

    @Test fun newEditDropsRedo() {
        val p = Page(100f, 100f); val h = History(p)
        h.add(stroke(1)); h.undo()
        assertTrue(h.canRedo)
        h.add(stroke(2))
        assertFalse(h.canRedo)
    }

    @Test fun eraseOfNothingIsNotAnEdit() {
        val p = Page(100f, 100f); val h = History(p)
        assertTrue(h.erase(setOf(9L)).isEmpty())
        assertFalse(h.canUndo)
    }

    @Test fun limitDropsOldest() {
        val p = Page(100f, 100f); val h = History(p, limit = 2)
        (1L..3L).forEach { h.add(stroke(it)) }
        h.undo(); h.undo()
        assertFalse(h.canUndo)
        assertEquals(listOf(1L), ids(p))
    }
}
