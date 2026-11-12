package app.hachure.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import app.hachure.geometry.Aim
import app.hachure.geometry.Guides
import app.hachure.geometry.Handle
import app.hachure.geometry.Hatch
import app.hachure.geometry.PageTransform
import app.hachure.geometry.PerspectiveGeometry
import app.hachure.geometry.PerspectiveItem
import app.hachure.geometry.Vec2
import app.hachure.ink.InkBackend
import app.hachure.model.Page
import app.hachure.model.Perspective
import app.hachure.model.Ruler
import app.hachure.render.GuideRenderer
import java.util.Locale

/**
 * Guides on the canvas (rulers and perspective): drawing them, and moving them with a finger.
 *
 * "Move guides with finger, draw with stylus": only finger events come here. A hand resting on
 * a ruler while hatching must not move it, so a finger counts only when the pen has not touched
 * the page for [PEN_GUARD_MS], the pen touching down cancels a drag in progress, only a short tap
 * selects, and only handles (never the bar) drag or long-press. Locking a guide removes its
 * handle altogether.
 *
 * While a handle is dragged only a dashed outline moves (one small partial refresh per step);
 * the guide itself is redrawn once, when the finger lifts.
 */
class GuideController(private val host: Host) {

    interface Host {
        val guidePage: Page?
        val guideTransform: PageTransform
        val guideBackend: InkBackend
        fun invalidateGuides(r: Rect)

        /** A guide changed on the page: let the repaint reach the screen and save. */
        fun guidesEdited()
    }

    var snapEnabled = true

    var lockAll = false
        set(value) {
            if (field == value) return
            field = value
            redrawEverything()
            host.guideBackend.showRepaint()
            onSelectionChanged?.invoke()
        }

    /** Rotation snaps to 15 degree steps. */
    var angleSnap = false

    var selectedId = 0L
        private set

    val selected: Ruler? get() = host.guidePage?.ruler(selectedId)

    /** The selected perspective item (the perspective strip is open), or null. */
    var perspectiveItem: PerspectiveItem? = null
        private set

    val perspective: Perspective? get() = host.guidePage?.perspective

    /** Selection, or a property of the selected guide, changed. */
    var onSelectionChanged: (() -> Unit)? = null

    /** Long-press on a ruler handle: ask for a numeric angle. */
    var onAngleEntry: ((Ruler) -> Unit)? = null

    var fingerEvents = 0
        private set
    var ignoredForPen = 0
        private set

    private val renderer = GuideRenderer()
    private val main = Handler(Looper.getMainLooper())
    private val tmp = Rect()
    private val tmp2 = Rect()

    // Gesture state. One of [grab] (ruler handle), [pGrab] (perspective handle) or just
    // [pressedRuler] (a press on a ruler's bar, which can only become a tap).
    private var grab: Guides.Grab? = null
    private var pGrab: PerspectiveItem? = null
    private var pStart: Perspective? = null
    private var pressedRuler: Ruler? = null
    private var gestureOn = false
    private var down = Vec2.ZERO
    private var downAt = 0L
    private var dragging = false
    private var ghost: Ruler? = null
    private var pGhost: Perspective? = null
    private var gestureDead = false
    private var lastGhostAt = 0L
    private val longPress = Runnable { onLongPress() }

    fun reset() {
        cancelGesture()
        selectedId = 0L
        perspectiveItem = null
        onSelectionChanged?.invoke()
    }

    fun boundsOf(r: Ruler, out: Rect) = renderer.bounds(r, host.guideTransform, out)

    fun draw(canvas: Canvas) {
        val pg = host.guidePage ?: return
        val t = host.guideTransform
        val ps = pg.perspective
        renderer.drawPerspective(canvas, ps, t, pg.widthMm, pg.heightMm, lockAll, perspectiveItem)
        for (r in pg.rulers) {
            renderer.bounds(r, t, tmp)
            if (canvas.quickReject(tmp.left.toFloat(), tmp.top.toFloat(), tmp.right.toFloat(), tmp.bottom.toFloat(), Canvas.EdgeType.BW)) continue
            renderer.draw(canvas, r, t, r.id == selectedId, Guides.locked(r, lockAll), Aim.active(r, ps))
        }
        ghost?.let { g ->
            val label = if (grab?.handle == Handle.MOVE) null else angleLabel(g)
            renderer.drawGhost(canvas, g, t, label)
        }
        val pg2 = pGhost
        val item = pGrab
        if (pg2 != null && item != null) renderer.drawPerspectiveGhost(canvas, pg2, item, t, pg.widthMm, pg.heightMm)
    }

    // Ruler edits from the toolbar and the guide strip

    /** Adds a ruler and selects it. False when the page already has the maximum. */
    fun addRuler(): Boolean {
        val pg = host.guidePage ?: return false
        if (pg.rulers.size >= Guides.MAX_RULERS) return false
        val r = Guides.newRuler(pg.newGuideId(), pg.widthMm, pg.heightMm, pg.rulers.size)
        pg.putRuler(r)
        val hadPerspective = perspectiveItem != null
        perspectiveItem = null
        val old = pg.ruler(selectedId)
        selectedId = r.id
        if (hadPerspective) redrawEverything() else redraw(r, old)
        host.guidesEdited()
        onSelectionChanged?.invoke()
        return true
    }

    fun deleteSelected() {
        val pg = host.guidePage ?: return
        val r = selected ?: return
        pg.removeRuler(r.id)
        selectedId = 0L
        redraw(r, null)
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    /** Closes whichever strip is open. */
    fun deselect() {
        if (perspectiveItem != null) selectPerspective(null) else select(0L)
    }

    fun updateSelected(change: (Ruler) -> Ruler) {
        val pg = host.guidePage ?: return
        val old = selected ?: return
        val new = Aim.apply(change(old), pg.perspective)
        if (new == old) return
        pg.putRuler(new)
        redraw(old, new)
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    fun setDisplayAngle(id: Long, deg: Float) {
        val pg = host.guidePage ?: return
        val old = pg.ruler(id) ?: return
        // Typing an angle frees a ruler that was aimed at a vanishing point.
        val new = Guides.withDisplayAngle(old.copy(aimVp = 0), deg)
        pg.putRuler(new)
        redraw(old, new)
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    fun cycleHatch() = updateSelected { Hatch.nextDirection(it) }

    fun changeSpacing(steps: Int) = updateSelected { Hatch.spacingChanged(it, steps) }

    fun toggleLock() = updateSelected { it.copy(locked = !it.locked) }

    /** Points the selected ruler at the next vanishing point in use, then back to free. */
    fun cycleAim() {
        val ps = perspective ?: return
        updateSelected { Aim.next(it, ps) }
    }

    private fun select(id: Long) {
        val pg = host.guidePage ?: return
        if (selectedId == id && perspectiveItem == null) return
        val old = pg.ruler(selectedId)
        selectedId = id
        if (perspectiveItem != null) {
            perspectiveItem = null
            redrawEverything()
        } else {
            redraw(old, pg.ruler(id))
        }
        host.guideBackend.showRepaint()
        onSelectionChanged?.invoke()
    }

    // Perspective edits

    /** The rail button: opens the perspective strip, or closes it when it is open. */
    fun togglePerspectiveStrip() {
        selectPerspective(if (perspectiveItem == null) PerspectiveItem.Horizon else null)
    }

    private fun selectPerspective(item: PerspectiveItem?) {
        val pg = host.guidePage ?: return
        if (perspectiveItem == item && (item == null || selectedId == 0L)) return
        val oldRuler = pg.ruler(selectedId)
        val oldItem = perspectiveItem
        selectedId = 0L
        perspectiveItem = item
        redraw(oldRuler, null)
        redrawItem(pg, oldItem)
        redrawItem(pg, item)
        host.guideBackend.showRepaint()
        onSelectionChanged?.invoke()
    }

    /** Steps the strip's selection through the vanishing points in use and the horizon. */
    fun cyclePerspectiveItem() {
        val ps = perspective ?: return
        val items = (0 until ps.points).map { PerspectiveItem.Vp(it) } + PerspectiveItem.Horizon
        val i = items.indexOf(perspectiveItem)
        selectPerspective(items[(i + 1) % items.size])
    }

    private fun updatePerspective(change: (Perspective) -> Perspective) {
        val pg = host.guidePage ?: return
        val new = change(pg.perspective)
        if (new == pg.perspective) return
        pg.perspective = new
        reAimRulers(pg)
        // The selected point may no longer be in use.
        val item = perspectiveItem
        if (item is PerspectiveItem.Vp && item.index >= new.points) perspectiveItem = PerspectiveItem.Horizon
        redrawEverything()
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    /** Off, 1, 2, 3 points, off. */
    fun cyclePoints() = updatePerspective { it.copy(points = (it.points + 1) % 4) }

    fun toggleLines() = updatePerspective { it.copy(linesVisible = !it.linesVisible) }

    fun changeRays(steps: Int) = updatePerspective { PerspectiveGeometry.raysChanged(it, steps) }

    fun togglePerspectiveLock() {
        val item = perspectiveItem ?: return
        updatePerspective { PerspectiveGeometry.toggledLock(it, item) }
    }

    private fun reAimRulers(pg: Page) {
        for (r in pg.rulers.toList()) {
            val aimed = Aim.apply(r, pg.perspective)
            if (aimed != r) pg.putRuler(aimed)
        }
    }

    // Invalidation

    private fun redraw(a: Ruler?, b: Ruler?) {
        tmp2.setEmpty()
        for (r in listOfNotNull(a, b)) {
            boundsOf(r, tmp)
            tmp2.union(tmp)
        }
        if (!tmp2.isEmpty) host.invalidateGuides(tmp2)
    }

    private fun redrawItem(pg: Page, item: PerspectiveItem?) {
        if (item == null) return
        renderer.itemBounds(pg.perspective, item, host.guideTransform, pg.widthMm, pg.heightMm, tmp)
        if (!tmp.isEmpty) host.invalidateGuides(tmp)
        if (item == PerspectiveItem.Horizon) {
            renderer.horizonBounds(pg.perspective, host.guideTransform, pg.widthMm, pg.heightMm, tmp)
            if (!tmp.isEmpty) host.invalidateGuides(tmp)
        }
    }

    /** Perspective lines cross the whole page, so a change to them repaints it (one partial refresh). */
    private fun redrawEverything() {
        val pg = host.guidePage ?: return
        val z = host.guideTransform.pageToView(Vec2(pg.widthMm, pg.heightMm))
        tmp2.set(0, 0, z.x.toInt() + 1, z.y.toInt() + 1)
        host.invalidateGuides(tmp2)
    }

    /** Union of all ruler bounds, for repainting after undo or redo moved one. */
    fun allBounds(out: Rect) {
        out.setEmpty()
        val pg = host.guidePage ?: return
        for (r in pg.rulers) {
            boundsOf(r, tmp)
            out.union(tmp)
        }
    }

    private fun ghostBounds(pg: Page, p: Perspective?, item: PerspectiveItem, out: Rect) {
        out.setEmpty()
        if (p == null) return
        val t = host.guideTransform
        val moved = when (item) {
            is PerspectiveItem.Vp -> listOf(item)
            PerspectiveItem.Horizon -> (0 until minOf(2, p.points)).map { PerspectiveItem.Vp(it) }
        }
        for (m in moved) {
            renderer.itemBounds(p, m, t, pg.widthMm, pg.heightMm, tmp)
            out.union(tmp)
        }
        renderer.horizonBounds(p, t, pg.widthMm, pg.heightMm, tmp)
        if (!tmp.isEmpty) out.union(tmp)
    }

    // Finger input

    /** The pen is in use: fingers on the glass are a resting hand, not a command. */
    fun penSeen() {
        if (gestureOn) cancelGesture()
    }

    /** Returns true when the gesture belongs to a guide. */
    fun onFingerEvent(e: MotionEvent): Boolean {
        val pg = host.guidePage ?: return false
        val t = host.guideTransform
        val p = t.viewToPage(Vec2(e.x, e.y))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerEvents++
                cancelGesture()
                if (host.guideBackend.msSincePen() < PEN_GUARD_MS) {
                    ignoredForPen++
                    return false
                }
                grab = Guides.handleAt(pg.rulers, p, lockAll, perspective = pg.perspective)
                if (grab == null) {
                    pGrab = PerspectiveGeometry.handleAt(pg.perspective, p, pg.widthMm, pg.heightMm, lockAll)
                    pStart = pg.perspective
                }
                pressedRuler = grab?.ruler ?: if (pGrab == null) Guides.rulerAt(pg.rulers, p) else null
                if (grab == null && pGrab == null && pressedRuler == null) return false
                gestureOn = true
                down = p
                downAt = SystemClock.uptimeMillis()
                gestureDead = false
                if (grab != null) main.postDelayed(longPress, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureOn) return false
                if (gestureDead) return true
                if (!dragging && p.distanceTo(down) >= SLOP_MM) {
                    main.removeCallbacks(longPress)
                    if (grab == null && pGrab == null) {
                        // A slide over the bar is not a tap. Only handles move a guide.
                        gestureDead = true
                        return true
                    }
                    dragging = true
                    host.guideBackend.setGuideDrag(true)
                    lastGhostAt = 0L
                }
                if (dragging) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGhostAt >= GHOST_FRAME_MS) {
                        lastGhostAt = now
                        moveGhost(pg, p)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!gestureOn) return false
                main.removeCallbacks(longPress)
                if (dragging) drop(pg, p) else tap(pg)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val owned = gestureOn
                cancelGesture()
                return owned
            }
            // Extra fingers are ignored; the first one keeps the gesture.
            else -> return gestureOn
        }
    }

    private fun rulerAt(pg: Page, g: Guides.Grab, p: Vec2): Ruler = Aim.apply(
        Guides.dragged(g.ruler, g.handle, down, p, pg.widthMm, pg.heightMm, if (angleSnap) Guides.ANGLE_SNAP_DEG else 0f),
        pg.perspective,
    )

    private fun perspectiveAt(pg: Page, item: PerspectiveItem, p: Vec2): Perspective =
        PerspectiveGeometry.dragged(pStart ?: pg.perspective, item, p - down, pg.widthMm, pg.heightMm)

    private fun moveGhost(pg: Page, p: Vec2) {
        val g = grab
        val item = pGrab
        if (g != null) {
            val new = rulerAt(pg, g, p)
            val old = ghost
            if (old == new) return
            ghost = new
            redraw(old, new)
        } else if (item != null) {
            val new = perspectiveAt(pg, item, p)
            val old = pGhost
            if (old == new) return
            ghostBounds(pg, old, item, tmp2)
            val before = Rect(tmp2)
            pGhost = new
            ghostBounds(pg, new, item, tmp2)
            before.union(tmp2)
            if (!before.isEmpty) host.invalidateGuides(before)
        }
    }

    private fun drop(pg: Page, p: Vec2) {
        val g = grab
        val item = pGrab
        if (g != null) {
            val new = rulerAt(pg, g, p)
            val old = pg.ruler(g.ruler.id)
            val oldGhost = ghost
            endGesture()
            if (old != null) {
                pg.putRuler(new)
                redraw(old, new)
                oldGhost?.let { redraw(it, null) }
                host.guidesEdited()
                if (new.id == selectedId) onSelectionChanged?.invoke()
            }
        } else if (item != null) {
            val new = perspectiveAt(pg, item, p)
            endGesture()
            pg.perspective = new
            reAimRulers(pg)
            redrawEverything()
            host.guidesEdited()
            onSelectionChanged?.invoke()
        } else {
            endGesture()
        }
    }

    private fun tap(pg: Page) {
        val ruler = pressedRuler
        val item = pGrab
        val isTap = !gestureDead && SystemClock.uptimeMillis() - downAt <= TAP_MS
        endGesture()
        if (!isTap) return
        if (item != null) {
            selectPerspective(if (perspectiveItem == item) null else item)
        } else if (ruler != null) {
            select(if (selectedId == ruler.id && perspectiveItem == null) 0L else ruler.id)
        }
    }

    private fun onLongPress() {
        val r = grab?.ruler ?: return
        if (dragging) return
        gestureDead = true
        host.guidePage?.ruler(r.id)?.let { onAngleEntry?.invoke(it) }
    }

    private fun cancelGesture() {
        val pg = host.guidePage
        val g = ghost
        val pgh = pGhost
        val item = pGrab
        endGesture()
        var shown = false
        g?.let {
            redraw(it, null)
            shown = true
        }
        if (pg != null && pgh != null && item != null) {
            ghostBounds(pg, pgh, item, tmp2)
            if (!tmp2.isEmpty) host.invalidateGuides(tmp2)
            shown = true
        }
        if (shown) host.guideBackend.showRepaint()
    }

    private fun endGesture() {
        main.removeCallbacks(longPress)
        if (dragging) host.guideBackend.setGuideDrag(false)
        dragging = false
        gestureOn = false
        grab = null
        pGrab = null
        pStart = null
        pressedRuler = null
        ghost = null
        pGhost = null
        gestureDead = false
    }

    fun debugLine() = "guides: finger downs $fingerEvents, ignored for pen $ignoredForPen"

    companion object {
        /** Finger input counts only this long after the pen was last seen. */
        const val PEN_GUARD_MS = 500L
        private const val LONG_PRESS_MS = 600L

        /** A resting hand stays down much longer than a tap. */
        private const val TAP_MS = 400L
        private const val SLOP_MM = 2.5f
        private const val GHOST_FRAME_MS = 50L

        fun angleLabel(r: Ruler): String {
            val a = Guides.displayAngle(r)
            val text = if (a == Math.rint(a.toDouble()).toFloat()) a.toInt().toString() else String.format(Locale.US, "%.1f", a)
            return "$text°"
        }
    }
}
