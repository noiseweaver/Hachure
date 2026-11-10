package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Ruler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidesTest {
    private val eps = 1e-3f
    private fun ruler(id: Long = 1, cx: Float = 50f, cy: Float = 50f, angle: Float = 0f, locked: Boolean = false) =
        Ruler(id, cx, cy, angle, lengthMm = 100f, widthMm = 14f, locked = locked)

    private fun assertVec(x: Float, y: Float, v: Vec2) {
        assertEquals(x, v.x, eps)
        assertEquals(y, v.y, eps)
    }

    // Edges

    @Test fun edgesOfHorizontalRuler() {
        val r = ruler()
        val below = RulerGeometry.edge(r, 1)
        assertVec(0f, 57f, below.a)
        assertVec(100f, 57f, below.b)
        val above = RulerGeometry.edge(r, -1)
        assertVec(0f, 43f, above.a)
        assertVec(100f, 43f, above.b)
        assertEquals(100f, below.length, eps)
    }

    @Test fun edgesOfVerticalRuler() {
        val r = ruler(angle = 90f)
        val e = RulerGeometry.edge(r, 1)
        assertVec(43f, 0f, e.a)
        assertVec(43f, 100f, e.b)
    }

    @Test fun containsFollowsRotation() {
        val r = ruler(angle = 45f)
        assertTrue(RulerGeometry.contains(r, Vec2(80f, 80f)))
        assertTrue(!RulerGeometry.contains(r, Vec2(80f, 50f)))
    }

    // Projection onto a line

    @Test fun edgeAlongAndDistance() {
        val e = Edge(Vec2(0f, 10f), Vec2(100f, 10f))
        assertEquals(30f, e.along(Vec2(30f, 14f)), eps)
        assertEquals(4f, e.distance(Vec2(30f, 14f)), eps)
        assertEquals(4f, e.distance(Vec2(30f, 6f)), eps)
        assertVec(30f, 10f, e.pointAt(30f))
    }

    @Test fun projectStraightensOntoEdgeAndKeepsPressure() {
        val e = Edge(Vec2(0f, 10f), Vec2(100f, 10f))
        val wobbly = listOf(
            InkPoint(10f, 11f, 0.2f, 0),
            InkPoint(20f, 13f, 0.9f, 10),
            InkPoint(30f, 9f, 0.5f, 20),
            InkPoint(40f, 12f, 0.1f, 30),
        )
        val out = Snap.project(wobbly, e)
        assertEquals(4, out.size)
        out.forEach { assertEquals(10f, it.y, eps) }
        assertEquals(10f, out.first().x, eps)
        assertEquals(40f, out.last().x, eps)
        assertEquals(wobbly.map { it.pressure }, out.map { it.pressure })
        assertEquals(wobbly.map { it.t }, out.map { it.t })
        assertTrue(out.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    @Test fun projectOnSlantedEdge() {
        val e = RulerGeometry.edge(ruler(angle = 45f), 1)
        val out = Snap.project(listOf(InkPoint(40f, 52f, 1f, 0), InkPoint(60f, 75f, 1f, 5)), e)
        out.forEach { assertEquals(0f, e.distance(Vec2(it.x, it.y)), eps) }
    }

    @Test fun projectSinglePointAndEmpty() {
        val e = Edge(Vec2(0f, 10f), Vec2(100f, 10f))
        assertTrue(Snap.project(emptyList(), e).isEmpty())
        val dot = Snap.project(listOf(InkPoint(5f, 12f, 0.7f, 0)), e)
        assertEquals(InkPoint(5f, 10f, 0.7f, 0), dot.single())
    }

    // Nearest edge

    @Test fun nearestEdgeWithinBand() {
        val r = ruler()
        assertEquals(1, Snap.nearestEdge(listOf(r), Vec2(50f, 59f))!!.side)
        assertEquals(-1, Snap.nearestEdge(listOf(r), Vec2(50f, 41f))!!.side)
        // Inside the bar, close to the lower edge.
        assertEquals(1, Snap.nearestEdge(listOf(r), Vec2(50f, 55f))!!.side)
        assertNull(Snap.nearestEdge(listOf(r), Vec2(50f, 62f)))
        assertNull(Snap.nearestEdge(listOf(r), Vec2(50f, 50f), bandMm = 4f))
    }

    @Test fun nearestEdgeStopsPastTheEnds() {
        val r = ruler()
        assertNotNull(Snap.nearestEdge(listOf(r), Vec2(103f, 58f)))
        assertNull(Snap.nearestEdge(listOf(r), Vec2(110f, 58f)))
    }

    @Test fun nearestEdgePicksClosestRuler() {
        val a = ruler(1, cy = 50f)
        val b = ruler(2, cy = 70f) // upper edge at y = 63
        val hit = Snap.nearestEdge(listOf(a, b), Vec2(50f, 61f))!!
        assertEquals(2L, hit.ruler.id)
        assertEquals(-1, hit.side)
        assertEquals(2f, hit.distanceMm, eps)
    }

    @Test fun lockedRulerStillSnaps() {
        assertNotNull(Snap.nearestEdge(listOf(ruler(locked = true)), Vec2(50f, 58f)))
    }

    // Hatching offset

    @Test fun hatchStepFollowsNormalAndDirection() {
        val r = ruler().copy(hatchSpacingMm = 2f, hatchDir = 1)
        assertVec(0f, 2f, Hatch.step(r))
        assertVec(0f, -2f, Hatch.step(r.copy(hatchDir = -1)))
        assertVec(0f, 0f, Hatch.step(r.copy(hatchDir = 0)))
        val moved = Hatch.stepped(r)
        assertEquals(52f, moved.cy, eps)
        assertEquals(50f, moved.cx, eps)
    }

    @Test fun hatchStepOnSlantedRulerIsPerpendicular() {
        val r = ruler(angle = 30f).copy(hatchSpacingMm = 3f, hatchDir = 1)
        val s = Hatch.step(r)
        assertEquals(3f, s.length(), eps)
        assertEquals(0f, s dot RulerGeometry.dir(r), eps)
        // The stepped edge is parallel and exactly one spacing away.
        val before = RulerGeometry.edge(r, 1)
        val after = RulerGeometry.edge(Hatch.stepped(r), 1)
        assertEquals(3f, before.distance(after.a), eps)
        assertEquals(3f, before.distance(after.b), eps)
    }

    @Test fun spacingStaysInRange() {
        val r = ruler()
        assertEquals(2.5f, Hatch.spacingChanged(r, 1).hatchSpacingMm, eps)
        assertEquals(Ruler.MIN_SPACING_MM, Hatch.spacingChanged(r, -100).hatchSpacingMm, eps)
        assertEquals(Ruler.MAX_SPACING_MM, Hatch.spacingChanged(r, 100).hatchSpacingMm, eps)
    }

    @Test fun hatchDirectionCycles() {
        var r = ruler()
        val seen = (1..3).map { r = Hatch.nextDirection(r); r.hatchDir }
        assertEquals(listOf(1, -1, 0), seen)
    }

    // Lock state rules

    @Test fun handlesSitOnTheCentreLine() {
        val r = ruler()
        assertVec(50f, 50f, Guides.handlePosition(r, Handle.MOVE))
        assertVec(8f, 50f, Guides.handlePosition(r, Handle.ROTATE_START))
        assertVec(92f, 50f, Guides.handlePosition(r, Handle.ROTATE_END))
    }

    @Test fun unlockedRulerOffersItsHandles() {
        val r = ruler()
        assertEquals(Handle.MOVE, Guides.handleAt(listOf(r), Vec2(51f, 52f), lockAll = false)!!.handle)
        assertEquals(Handle.ROTATE_END, Guides.handleAt(listOf(r), Vec2(93f, 49f), lockAll = false)!!.handle)
        assertNull(Guides.handleAt(listOf(r), Vec2(70f, 50f), lockAll = false))
    }

    @Test fun lockedRulerHasNoHandles() {
        assertNull(Guides.handleAt(listOf(ruler(locked = true)), Vec2(50f, 50f), lockAll = false))
    }

    @Test fun lockAllHidesEveryHandle() {
        assertNull(Guides.handleAt(listOf(ruler(1), ruler(2, cy = 90f)), Vec2(50f, 90f), lockAll = true))
        assertTrue(Guides.locked(ruler(), lockAll = true))
        assertTrue(Guides.locked(ruler(locked = true), lockAll = false))
        assertTrue(!Guides.locked(ruler(), lockAll = false))
    }

    @Test fun lockedRulerCanStillBeSelectedAndStepped() {
        val r = ruler(locked = true).copy(hatchDir = 1)
        assertEquals(r, Guides.rulerAt(listOf(r), Vec2(50f, 50f)))
        assertEquals(52f, Hatch.stepped(r).cy, eps)
    }

    @Test fun topRulerWinsWhenHandlesOverlap() {
        val under = ruler(1)
        val over = ruler(2, cx = 51f)
        assertEquals(2L, Guides.handleAt(listOf(under, over), Vec2(50.5f, 50f), lockAll = false)!!.ruler.id)
        assertEquals(2L, Guides.rulerAt(listOf(under, over), Vec2(50f, 50f))!!.id)
    }

    // Dragging

    @Test fun moveDragTranslatesAndStaysOnPage() {
        val r = ruler()
        val m = Guides.dragged(r, Handle.MOVE, Vec2(51f, 51f), Vec2(61f, 71f), 157f, 209f)
        assertVec(60f, 70f, RulerGeometry.center(m))
        assertEquals(r.angleDeg, m.angleDeg, eps)
        val off = Guides.dragged(r, Handle.MOVE, Vec2(50f, 50f), Vec2(-500f, 900f), 157f, 209f)
        assertVec(0f, 209f, RulerGeometry.center(off))
    }

    @Test fun rotateDragTurnsAboutTheCentre() {
        val r = ruler()
        val byEnd = Guides.dragged(r, Handle.ROTATE_END, Vec2(92f, 50f), Vec2(50f, 90f), 157f, 209f)
        assertEquals(90f, byEnd.angleDeg, eps)
        assertVec(50f, 50f, RulerGeometry.center(byEnd))
        // Dragging the start handle to the same place points the end the other way.
        val byStart = Guides.dragged(r, Handle.ROTATE_START, Vec2(8f, 50f), Vec2(50f, 90f), 157f, 209f)
        assertEquals(270f, byStart.angleDeg, eps)
    }

    @Test fun rotateSnapsToFifteenDegrees() {
        val r = ruler()
        val m = Guides.dragged(r, Handle.ROTATE_END, Vec2(92f, 50f), Vec2(90f, 72f), 157f, 209f, snapStepDeg = 15f)
        assertEquals(30f, m.angleDeg, eps)
    }

    @Test fun rotatingPastHalfATurnKeepsTheNormal() {
        // 170 then 190 degrees: the angle is not folded, so the normal turns smoothly.
        val a = ruler(angle = 170f)
        val b = ruler(angle = 190f)
        assertTrue((RulerGeometry.normal(a) dot RulerGeometry.normal(b)) > 0.9f)
    }

    @Test fun displayAngleIsCounterClockwise() {
        assertEquals(0f, Guides.displayAngle(ruler(angle = 0f)), eps)
        assertEquals(45f, Guides.displayAngle(ruler(angle = 315f)), eps)
        assertEquals(45f, Guides.displayAngle(ruler(angle = 135f)), eps)
        assertEquals(90f, Guides.displayAngle(ruler(angle = 90f)), eps)
    }

    @Test fun settingDisplayAngleKeepsTheEndsOrder() {
        val r = ruler(angle = 170f)
        val turned = Guides.withDisplayAngle(r, 30f) // 330 or 150: 150 is nearer to 170
        assertEquals(150f, turned.angleDeg, eps)
        assertEquals(30f, Guides.displayAngle(turned), eps)
        assertEquals(0f, Guides.withDisplayAngle(ruler(angle = 10f), 180f).angleDeg, eps)
    }

    @Test fun newRulersAreStaggered() {
        val a = Guides.newRuler(1, 157f, 209f, 0)
        val b = Guides.newRuler(2, 157f, 209f, 1)
        assertTrue(b.cy - a.cy > Ruler.DEFAULT_WIDTH_MM)
        assertEquals(78.5f, a.cx, eps)
    }
}
