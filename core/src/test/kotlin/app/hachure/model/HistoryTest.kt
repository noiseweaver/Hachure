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

    @Test fun groupedErasesUndoAsOneStepInOrder() {
        val p = Page(100f, 100f); val h = History(p)
        (1L..5L).forEach { h.add(stroke(it)) }
        h.beginGroup()
        h.erase(setOf(2L))
        h.erase(setOf(4L))
        h.erase(setOf(1L))
        h.endGroup()
        assertEquals(listOf(3L, 5L), ids(p))
        assertEquals(3, h.undo().size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(p))
        h.redo()
        assertEquals(listOf(3L, 5L), ids(p))
        h.undo()
        h.undo()
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(p))
    }

    @Test fun addInsideGroupStartsNewStep() {
        val p = Page(100f, 100f); val h = History(p)
        (1L..3L).forEach { h.add(stroke(it)) }
        h.beginGroup()
        h.erase(setOf(1L))
        h.add(stroke(9))
        h.erase(setOf(2L))
        h.endGroup()
        h.undo()
        assertEquals(setOf(2L, 3L, 9L), ids(p).toSet())
    }

    // Hatching: the ruler's step is part of the stroke's undo step.

    @Test fun hatchedStrokeStepsRulerAndUndoesWithIt() {
        val p = Page(100f, 100f); val h = History(p)
        p.putRuler(Ruler(1, 50f, 50f, 0f, hatchSpacingMm = 2f, hatchDir = 1))
        h.add(stroke(1), hatched = p.ruler(1))
        h.add(stroke(2), hatched = p.ruler(1))
        assertEquals(54f, p.ruler(1)!!.cy, 1e-4f)
        h.undo()
        assertTrue(h.guideMoved)
        assertEquals(52f, p.ruler(1)!!.cy, 1e-4f)
        assertEquals(listOf(1L), ids(p))
        h.redo()
        assertTrue(h.guideMoved)
        assertEquals(54f, p.ruler(1)!!.cy, 1e-4f)
        assertEquals(listOf(1L, 2L), ids(p))
    }

    @Test fun plainStrokeLeavesRulerAlone() {
        val p = Page(100f, 100f); val h = History(p)
        p.putRuler(Ruler(1, 50f, 50f, 0f, hatchDir = 1))
        h.add(stroke(1))
        h.undo()
        assertFalse(h.guideMoved)
        assertEquals(50f, p.ruler(1)!!.cy, 1e-4f)
    }

    @Test fun hatchOffMeansNoStep() {
        val p = Page(100f, 100f); val h = History(p)
        p.putRuler(Ruler(1, 50f, 50f, 0f, hatchDir = 0))
        h.add(stroke(1), hatched = p.ruler(1))
        assertEquals(50f, p.ruler(1)!!.cy, 1e-4f)
        h.undo()
        assertFalse(h.guideMoved)
    }

    @Test fun undoAfterRulerDeletedOnlyRemovesStroke() {
        val p = Page(100f, 100f); val h = History(p)
        p.putRuler(Ruler(1, 50f, 50f, 0f, hatchDir = 1))
        h.add(stroke(1), hatched = p.ruler(1))
        p.removeRuler(1)
        h.undo()
        assertFalse(h.guideMoved)
        assertTrue(p.strokes.isEmpty())
    }

    @Test fun aimedRulerTurnsAsItStepsAndTurnsBackOnUndo() {
        val p = Page(200f, 200f); val h = History(p)
        p.perspective = p.perspective.copy(points = 1).withVp(0, VanishingPoint(-50f, 100f))
        val start = app.hachure.geometry.Aim.apply(Ruler(1, 100f, 100f, 0f, hatchSpacingMm = 5f, hatchDir = 1, aimVp = 1), p.perspective)
        p.putRuler(start)
        h.add(stroke(1), hatched = p.ruler(1))
        val stepped = p.ruler(1)!!
        assertTrue(stepped.angleDeg != start.angleDeg)
        val edge = app.hachure.geometry.RulerGeometry.edge(stepped, 1)
        assertEquals(0f, edge.distance(app.hachure.geometry.Vec2(-50f, 100f)), 0.02f)
        h.undo()
        assertEquals(start.cx, p.ruler(1)!!.cx, 1e-3f)
        assertEquals(start.cy, p.ruler(1)!!.cy, 1e-3f)
        assertEquals(start.angleDeg, p.ruler(1)!!.angleDeg, 0.01f)
    }
}
