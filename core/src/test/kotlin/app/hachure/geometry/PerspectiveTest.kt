package app.hachure.geometry

import app.hachure.model.InkPoint
import app.hachure.model.Perspective
import app.hachure.model.Ruler
import app.hachure.model.VanishingPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectiveTest {
    private val eps = 1e-2f
    private val w = 157f
    private val h = 209f

    private fun persp(points: Int, vararg vps: VanishingPoint) = Perspective(
        points,
        List(3) { i -> vps.getOrElse(i) { VanishingPoint(500f + i, 500f) } },
    )

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20) = List(n) { i ->
        val f = i / (n - 1f)
        InkPoint(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0.3f + 0.5f * f, i * 5L)
    }

    private fun assertVec(x: Float, y: Float, v: Vec2) {
        assertEquals(x, v.x, eps)
        assertEquals(y, v.y, eps)
    }

    // Lines

    @Test fun clipLineThroughPage() {
        val (a, b) = PerspectiveGeometry.clipLine(Vec2(50f, 80f), Vec2(1f, 0f), w, h)!!
        assertVec(0f, 80f, a)
        assertVec(w, 80f, b)
    }

    @Test fun clipLineFromOutside() {
        val seg = PerspectiveGeometry.clipLine(Vec2(-100f, 100f), Vec2(1f, 0f), w, h)!!
        assertVec(0f, 100f, seg.first)
        assertNull(PerspectiveGeometry.clipLine(Vec2(-100f, -50f), Vec2(1f, 0f), w, h))
        assertNull(PerspectiveGeometry.clipLine(Vec2(-10f, 100f), Vec2(0f, 1f), w, h))
    }

    @Test fun raysFromPointOnPage() {
        val rays = PerspectiveGeometry.rays(VanishingPoint(80f, 100f), 24, w, h)
        assertEquals(24, rays.size)
        // Every ray passes through the vanishing point.
        for ((a, b) in rays) assertEquals(0f, Edge(a, b).distance(Vec2(80f, 100f)), eps)
    }

    @Test fun raysFromPointOffPageAreFewer() {
        val rays = PerspectiveGeometry.rays(VanishingPoint(-200f, 100f), 24, w, h)
        assertTrue(rays.size in 1..23)
        for ((a, b) in rays) assertEquals(0f, Edge(a, b).distance(Vec2(-200f, 100f)), 0.05f)
    }

    // Horizon

    @Test fun horizonIsHorizontalInOnePoint() {
        val (o, d) = PerspectiveGeometry.horizon(persp(1, VanishingPoint(40f, 70f)))!!
        assertVec(40f, 70f, o)
        assertVec(1f, 0f, d)
        assertNull(PerspectiveGeometry.horizon(persp(0)))
    }

    @Test fun horizonJoinsVp1AndVp2() {
        val (_, d) = PerspectiveGeometry.horizon(persp(2, VanishingPoint(0f, 50f), VanishingPoint(100f, 150f)))!!
        assertEquals(d.x, d.y, eps)
    }

    @Test fun horizonHandleAvoidsVanishingPointHandles() {
        val p = persp(1, VanishingPoint(w / 2f, 80f))
        val hh = PerspectiveGeometry.horizonHandle(p, w, h)!!
        assertEquals(80f, hh.y, eps)
        assertTrue(hh.distanceTo(Vec2(w / 2f, 80f)) > PerspectiveGeometry.HANDLE_MM)
        assertNull(PerspectiveGeometry.horizonHandle(persp(1, VanishingPoint(40f, -50f)), w, h))
    }

    // Off-page markers

    @Test fun handleOfPointOnPageIsThePoint() {
        assertVec(40f, 70f, PerspectiveGeometry.handlePosition(VanishingPoint(40f, 70f), w, h))
        assertNull(PerspectiveGeometry.markerDirection(VanishingPoint(40f, 70f), w, h))
    }

    @Test fun offPagePointGetsEdgeMarkerWithArrow() {
        val vp = VanishingPoint(-300f, h / 2f)
        val m = PerspectiveGeometry.handlePosition(vp, w, h)
        assertVec(PerspectiveGeometry.MARKER_INSET_MM, h / 2f, m)
        assertVec(-1f, 0f, PerspectiveGeometry.markerDirection(vp, w, h)!!)
        val corner = PerspectiveGeometry.handlePosition(VanishingPoint(1000f, 1500f), w, h)
        assertTrue(PerspectiveGeometry.onPage(corner, w, h))
    }

    // Lock state rules

    @Test fun handlesFollowLocks() {
        val p = persp(2, VanishingPoint(40f, 70f), VanishingPoint(120f, 70f, locked = true))
        assertEquals(PerspectiveItem.Vp(0), PerspectiveGeometry.handleAt(p, Vec2(41f, 71f), w, h, lockAll = false))
        assertNull(PerspectiveGeometry.handleAt(p, Vec2(120f, 70f), w, h, lockAll = false))
        assertNull(PerspectiveGeometry.handleAt(p, Vec2(41f, 71f), w, h, lockAll = true))
        val hh = PerspectiveGeometry.horizonHandle(p, w, h)!!
        assertEquals(PerspectiveItem.Horizon, PerspectiveGeometry.handleAt(p, hh, w, h, lockAll = false))
        assertNull(PerspectiveGeometry.handleAt(p.copy(horizonLocked = true), hh, w, h, lockAll = false))
    }

    @Test fun unusedPointsHaveNoHandle() {
        val p = persp(1, VanishingPoint(40f, 70f), VanishingPoint(120f, 70f))
        assertNull(PerspectiveGeometry.handleAt(p, Vec2(120f, 70f), w, h, lockAll = false))
        assertNull(PerspectiveGeometry.handleAt(p.copy(points = 0), Vec2(40f, 70f), w, h, lockAll = false))
    }

    @Test fun toggleLocks() {
        val p = persp(2, VanishingPoint(40f, 70f), VanishingPoint(120f, 70f))
        assertTrue(PerspectiveGeometry.toggledLock(p, PerspectiveItem.Vp(1)).vps[1].locked)
        assertTrue(PerspectiveGeometry.toggledLock(p, PerspectiveItem.Horizon).horizonLocked)
        assertTrue(PerspectiveGeometry.locked(p, PerspectiveItem.Vp(0), lockAll = true))
        assertTrue(!PerspectiveGeometry.locked(p, PerspectiveItem.Vp(0), lockAll = false))
    }

    // Dragging

    @Test fun vanishingPointFollowsFingerAndCanLeaveThePage() {
        val p = persp(2, VanishingPoint(10f, 70f), VanishingPoint(120f, 70f))
        val once = PerspectiveGeometry.dragged(p, PerspectiveItem.Vp(0), Vec2(-40f, 5f), w, h)
        assertVec(-30f, 75f, PerspectiveGeometry.pos(once.vps[0]))
        // A second drag from the edge marker pushes it further out.
        val twice = PerspectiveGeometry.dragged(once, PerspectiveItem.Vp(0), Vec2(-40f, 0f), w, h)
        assertVec(-70f, 75f, PerspectiveGeometry.pos(twice.vps[0]))
        assertEquals(p.vps[1], twice.vps[1])
    }

    @Test fun horizonDragMovesVp1AndVp2Vertically() {
        val p = persp(3, VanishingPoint(10f, 70f), VanishingPoint(120f, 90f), VanishingPoint(80f, 200f))
        val d = PerspectiveGeometry.dragged(p, PerspectiveItem.Horizon, Vec2(33f, 12f), w, h)
        assertVec(10f, 82f, PerspectiveGeometry.pos(d.vps[0]))
        assertVec(120f, 102f, PerspectiveGeometry.pos(d.vps[1]))
        assertEquals(p.vps[2], d.vps[2])
    }

    @Test fun lockedHorizonKeepsVanishingPointsOnIt() {
        val p = persp(2, VanishingPoint(10f, 70f), VanishingPoint(120f, 70f)).copy(horizonLocked = true)
        val d = PerspectiveGeometry.dragged(p, PerspectiveItem.Vp(1), Vec2(15f, 40f), w, h)
        assertVec(135f, 70f, PerspectiveGeometry.pos(d.vps[1]))
        // The third point is not on the horizon and stays free.
        val p3 = p.copy(points = 3)
        val d3 = PerspectiveGeometry.dragged(p3, PerspectiveItem.Vp(2), Vec2(15f, 40f), w, h)
        assertVec(p3.vps[2].x + 15f, p3.vps[2].y + 40f, PerspectiveGeometry.pos(d3.vps[2]))
    }

    @Test fun vanishingPointsStayWithinReach() {
        val p = persp(1, VanishingPoint(10f, 70f))
        val d = PerspectiveGeometry.dragged(p, PerspectiveItem.Vp(0), Vec2(-1e6f, 1e6f), w, h)
        assertEquals(-PerspectiveGeometry.MAX_REACH * w, d.vps[0].x, eps)
        assertEquals((PerspectiveGeometry.MAX_REACH + 1f) * h, d.vps[0].y, eps)
    }

    @Test fun densitySteps() {
        val p = persp(1)
        assertEquals(36, PerspectiveGeometry.raysChanged(p, 1).rays)
        assertEquals(12, PerspectiveGeometry.raysChanged(p, -5).rays)
        assertEquals(72, PerspectiveGeometry.raysChanged(p, 5).rays)
    }

    // VP selection by angle

    @Test fun initialDirectionUsesFirstFiveMillimetres() {
        // Starts heading right, then bends down: the start decides.
        val pts = stroke(10f, 10f, 20f, 10f) + stroke(20f, 10f, 20f, 60f)
        assertVec(1f, 0f, PerspectiveSnap.initialDirection(pts)!!)
        assertNull(PerspectiveSnap.initialDirection(stroke(10f, 10f, 12f, 11f)))
        assertNull(PerspectiveSnap.initialDirection(listOf(InkPoint(1f, 1f, 1f, 0))))
    }

    @Test fun picksTheVanishingPointTheStrokeHeadsFor() {
        val p = persp(3, VanishingPoint(-100f, 80f), VanishingPoint(300f, 80f), VanishingPoint(80f, 600f))
        val toLeft = PerspectiveSnap.direction(stroke(80f, 120f, 40f, 113f), p)!!
        assertTrue(PerspectiveSnap.lineAngle(toLeft, (Vec2(-100f, 80f) - Vec2(80f, 120f)).normalized()) < 0.01f)
        val toRight = PerspectiveSnap.direction(stroke(80f, 120f, 120f, 114f), p)!!
        assertTrue(PerspectiveSnap.lineAngle(toRight, (Vec2(300f, 80f) - Vec2(80f, 120f)).normalized()) < 0.01f)
        val down = PerspectiveSnap.direction(stroke(80f, 120f, 81f, 160f), p)!!
        assertTrue(PerspectiveSnap.lineAngle(down, (Vec2(80f, 600f) - Vec2(80f, 120f)).normalized()) < 0.01f)
    }

    @Test fun drawingAwayFromAVanishingPointAlsoMatches() {
        val p = persp(1, VanishingPoint(80f, 80f))
        assertNotNull(PerspectiveSnap.direction(stroke(100f, 100f, 130f, 131f), p))
    }

    @Test fun outsideToleranceStaysFreehand() {
        val p = persp(3, VanishingPoint(-100f, 80f), VanishingPoint(300f, 80f), VanishingPoint(80f, 600f))
        // About 45 degrees: nowhere near any of the three.
        assertNull(PerspectiveSnap.direction(stroke(80f, 120f, 110f, 150f), p))
        assertNull(PerspectiveSnap.apply(stroke(80f, 120f, 110f, 150f), p))
    }

    @Test fun closestCandidateWinsWhenTwoAreInTolerance() {
        // Two points almost in line from the start: 0 degrees and about 5.7 degrees.
        val p = persp(2, VanishingPoint(200f, 100f), VanishingPoint(200f, 110f))
        val d = PerspectiveSnap.direction(stroke(100f, 100f, 130f, 102.4f), p)!!
        // 4.6 degrees: nearer to the second (5.7) than to the first (0) or horizontal (0).
        assertTrue(PerspectiveSnap.lineAngle(d, (Vec2(200f, 110f) - Vec2(100f, 100f)).normalized()) < 0.01f)
    }

    @Test fun verticalAndHorizontalInOneAndTwoPointOnly() {
        val vps = arrayOf(VanishingPoint(-400f, 80f), VanishingPoint(600f, 80f), VanishingPoint(80f, 900f))
        val vertical = stroke(50f, 100f, 51f, 140f)
        assertVec(0f, 1f, PerspectiveSnap.direction(vertical, persp(2, *vps))!!)
        assertVec(0f, 1f, PerspectiveSnap.direction(vertical, persp(1, *vps))!!)
        // In 3-point the only near-vertical candidate is the third point itself.
        val d3 = PerspectiveSnap.direction(vertical, persp(3, *vps))!!
        assertTrue(d3.x != 0f)
        assertNull(PerspectiveSnap.direction(vertical, persp(0, *vps)))
    }

    @Test fun snappedStrokeIsStraightTowardThePointAndKeepsPressure() {
        val vp = VanishingPoint(-100f, 80f)
        val raw = stroke(80f, 120f, 40f, 113f)
        val out = PerspectiveSnap.apply(raw, persp(1, vp))!!
        assertEquals(raw.first().x, out.first().x, eps)
        assertEquals(raw.first().y, out.first().y, eps)
        val line = Edge(Vec2(80f, 120f), Vec2(-100f, 80f))
        out.forEach { assertEquals(0f, line.distance(Vec2(it.x, it.y)), eps) }
        assertEquals(raw.map { it.pressure }, out.map { it.pressure })
    }

    // Resolution order

    @Test fun rulerEdgeBeatsPerspective() {
        val p = persp(1, VanishingPoint(80f, 20f))
        val r = Ruler(1, 80f, 100f, 0f, lengthMm = 100f)
        // Starts 1 mm from the lower edge (y = 107) and heads straight for the vanishing point.
        val res = SnapResolver.resolve(stroke(80f, 108f, 80f, 60f), listOf(r), p, enabled = true)
        assertTrue(res is SnapResolver.OnRuler)
        res.points.forEach { assertEquals(107f, it.y, eps) }
    }

    @Test fun perspectiveWhenNoRulerIsNear() {
        val p = persp(1, VanishingPoint(80f, 20f))
        val r = Ruler(1, 80f, 180f, 0f, lengthMm = 100f)
        val res = SnapResolver.resolve(stroke(80f, 108f, 81f, 60f), listOf(r), p, enabled = true)
        assertTrue(res is SnapResolver.OnPerspective)
        res.points.forEach { assertEquals(80f, it.x, eps) }
    }

    @Test fun freehandWhenNothingMatchesOrSnapIsOff() {
        val p = persp(3, VanishingPoint(-100f, 80f), VanishingPoint(300f, 80f), VanishingPoint(80f, 600f))
        val raw = stroke(80f, 120f, 110f, 150f)
        assertEquals(SnapResolver.Freehand(raw), SnapResolver.resolve(raw, emptyList(), p, enabled = true))
        val toVp = stroke(80f, 120f, 40f, 113f)
        assertEquals(SnapResolver.Freehand(toVp), SnapResolver.resolve(toVp, emptyList(), p, enabled = false))
        assertEquals(SnapResolver.Freehand(toVp), SnapResolver.resolve(toVp, emptyList(), null, enabled = true))
    }

    // Ruler aimed at a vanishing point

    @Test fun aimedEdgePassesThroughTheVanishingPoint() {
        val p = persp(2, VanishingPoint(-60f, 40f), VanishingPoint(300f, 40f))
        for ((cx, cy) in listOf(80f to 120f, 30f to 190f, 140f to 10f)) {
            val r = Aim.apply(Ruler(1, cx, cy, 0f, aimVp = 1), p)
            val e = RulerGeometry.edge(r, Aim.AIMED_SIDE)
            assertEquals(0f, e.distance(Vec2(-60f, 40f)), 0.02f)
            assertVec(cx, cy, RulerGeometry.center(r))
        }
    }

    @Test fun aimKeepsTheNearerOrientation() {
        val p = persp(1, VanishingPoint(300f, 100f))
        val a = Aim.apply(Ruler(1, 80f, 100f, 10f, aimVp = 1), p)
        val b = Aim.apply(Ruler(1, 80f, 100f, 170f, aimVp = 1), p)
        assertTrue(a.angleDeg < 90f || a.angleDeg > 270f)
        assertTrue(b.angleDeg in 90f..270f)
        assertEquals(0f, RulerGeometry.edge(b, Aim.AIMED_SIDE).distance(Vec2(300f, 100f)), 0.02f)
    }

    @Test fun aimNeedsThePointInUse() {
        val p = persp(1, VanishingPoint(300f, 100f), VanishingPoint(0f, 0f))
        val r = Ruler(1, 80f, 100f, 33f, aimVp = 2)
        assertTrue(!Aim.active(r, p))
        assertEquals(r, Aim.apply(r, p))
        assertEquals(r, Aim.apply(r, null))
        // Too close to turn: unchanged.
        val near = Ruler(1, 300f, 101f, 33f, aimVp = 1)
        assertEquals(near, Aim.apply(near, p))
    }

    @Test fun aimCyclesThroughPointsInUse() {
        val p = persp(2, VanishingPoint(-60f, 40f), VanishingPoint(300f, 40f))
        var r = Ruler(1, 80f, 120f, 0f)
        val seen = (1..3).map { r = Aim.next(r, p); r.aimVp }
        assertEquals(listOf(1, 2, 0), seen)
    }

    @Test fun aimedRulerOnlyOffersTheMoveHandle() {
        val p = persp(1, VanishingPoint(300f, 100f))
        val r = Aim.apply(Ruler(1, 80f, 100f, 0f, lengthMm = 100f, aimVp = 1), p)
        val end = Guides.handlePosition(r, Handle.ROTATE_END)
        assertNull(Guides.handleAt(listOf(r), end, lockAll = false, perspective = p))
        assertNotNull(Guides.handleAt(listOf(r), end, lockAll = false))
        assertEquals(Handle.MOVE, Guides.handleAt(listOf(r), Vec2(80f, 100f), lockAll = false, perspective = p)!!.handle)
    }
}
