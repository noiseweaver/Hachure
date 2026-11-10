package app.hachure.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import app.hachure.geometry.Guides
import app.hachure.geometry.Handle
import app.hachure.geometry.Hatch
import app.hachure.geometry.PageTransform
import app.hachure.geometry.Vec2
import app.hachure.ink.InkBackend
import app.hachure.model.Page
import app.hachure.model.Ruler
import app.hachure.render.GuideRenderer
import java.util.Locale

/**
 * Rulers on the canvas: drawing them, and moving them with a finger.
 *
 * "Move guides with finger, draw with stylus": only finger events come here. A hand resting on
 * a ruler while hatching must not move it, so a finger counts only when the pen has not touched
 * the page for [PEN_GUARD_MS], the pen touching down cancels a drag in progress, only a short tap
 * selects, and only handles (never the bar) drag or long-press. Locking a ruler removes its
 * handles altogether.
 *
 * While a handle is dragged only a dashed outline moves (one small partial refresh per step);
 * the ruler itself is redrawn once, when the finger lifts.
 */
class GuideController(private val host: Host) {

    interface Host {
        val guidePage: Page?
        val guideTransform: PageTransform
        val guideBackend: InkBackend
        fun invalidateGuides(r: Rect)

        /** A ruler changed on the page: let the repaint reach the screen and save. */
        fun guidesEdited()
    }

    var snapEnabled = true

    var lockAll = false
        set(value) {
            if (field == value) return
            field = value
            redrawAll()
        }

    /** Rotation snaps to 15 degree steps. */
    var angleSnap = false

    var selectedId = 0L
        private set

    val selected: Ruler? get() = host.guidePage?.ruler(selectedId)

    /** Selection, or a property of the selected ruler, changed. */
    var onSelectionChanged: (() -> Unit)? = null

    /** Long-press on a handle: ask for a numeric angle. */
    var onAngleEntry: ((Ruler) -> Unit)? = null

    var fingerEvents = 0
        private set
    var ignoredForPen = 0
        private set

    private val renderer = GuideRenderer()
    private val main = Handler(Looper.getMainLooper())
    private val tmp = Rect()
    private val tmp2 = Rect()

    // Gesture state
    private var grab: Guides.Grab? = null
    private var pressed: Ruler? = null
    private var down = Vec2.ZERO
    private var downAt = 0L
    private var dragging = false
    private var ghost: Ruler? = null
    private var gestureDead = false
    private var lastGhostAt = 0L
    private val longPress = Runnable { onLongPress() }

    fun reset() {
        cancelGesture()
        selectedId = 0L
        onSelectionChanged?.invoke()
    }

    fun boundsOf(r: Ruler, out: Rect) = renderer.bounds(r, host.guideTransform, out)

    fun draw(canvas: Canvas) {
        val pg = host.guidePage ?: return
        val t = host.guideTransform
        for (r in pg.rulers) {
            renderer.bounds(r, t, tmp)
            if (canvas.quickReject(tmp.left.toFloat(), tmp.top.toFloat(), tmp.right.toFloat(), tmp.bottom.toFloat(), Canvas.EdgeType.BW)) continue
            renderer.draw(canvas, r, t, r.id == selectedId, Guides.locked(r, lockAll))
        }
        ghost?.let { g ->
            val label = if (grab?.handle == Handle.MOVE) null else angleLabel(g)
            renderer.drawGhost(canvas, g, t, label)
        }
    }

    // Edits from the toolbar and the guide strip

    /** Adds a ruler and selects it. False when the page already has the maximum. */
    fun addRuler(): Boolean {
        val pg = host.guidePage ?: return false
        if (pg.rulers.size >= Guides.MAX_RULERS) return false
        val r = Guides.newRuler(pg.newGuideId(), pg.widthMm, pg.heightMm, pg.rulers.size)
        pg.putRuler(r)
        selectedId = r.id
        redraw(r, null)
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

    fun deselect() = select(0L)

    fun updateSelected(change: (Ruler) -> Ruler) {
        val pg = host.guidePage ?: return
        val old = selected ?: return
        val new = change(old)
        if (new == old) return
        pg.putRuler(new)
        redraw(old, new)
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    fun setDisplayAngle(id: Long, deg: Float) {
        val pg = host.guidePage ?: return
        val old = pg.ruler(id) ?: return
        val new = Guides.withDisplayAngle(old, deg)
        pg.putRuler(new)
        redraw(old, new)
        host.guidesEdited()
        onSelectionChanged?.invoke()
    }

    fun cycleHatch() = updateSelected { Hatch.nextDirection(it) }

    fun changeSpacing(steps: Int) = updateSelected { Hatch.spacingChanged(it, steps) }

    fun toggleLock() = updateSelected { it.copy(locked = !it.locked) }

    private fun select(id: Long) {
        if (selectedId == id) return
        val pg = host.guidePage ?: return
        val old = pg.ruler(selectedId)
        selectedId = id
        redraw(old, pg.ruler(id))
        host.guideBackend.showRepaint()
        onSelectionChanged?.invoke()
    }

    private fun redraw(a: Ruler?, b: Ruler?) {
        tmp2.setEmpty()
        for (r in listOfNotNull(a, b)) {
            boundsOf(r, tmp)
            tmp2.union(tmp)
        }
        if (!tmp2.isEmpty) host.invalidateGuides(tmp2)
    }

    private fun redrawAll() {
        val pg = host.guidePage ?: return
        tmp2.setEmpty()
        for (r in pg.rulers) {
            boundsOf(r, tmp)
            tmp2.union(tmp)
        }
        if (!tmp2.isEmpty) {
            host.invalidateGuides(tmp2)
            host.guideBackend.showRepaint()
        }
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

    // Finger input

    /** The pen is in use: fingers on the glass are a resting hand, not a command. */
    fun penSeen() {
        if (grab != null || pressed != null) cancelGesture()
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
                grab = Guides.handleAt(pg.rulers, p, lockAll)
                pressed = grab?.ruler ?: Guides.rulerAt(pg.rulers, p)
                if (pressed == null) return false
                down = p
                downAt = SystemClock.uptimeMillis()
                gestureDead = false
                if (grab != null) main.postDelayed(longPress, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed == null) return false
                if (gestureDead) return true
                val g = grab
                if (!dragging && p.distanceTo(down) >= SLOP_MM) {
                    main.removeCallbacks(longPress)
                    if (g == null) {
                        // A slide over the bar is not a tap. Only handles move a ruler.
                        gestureDead = true
                        return true
                    }
                    dragging = true
                    host.guideBackend.setGuideDrag(true)
                    lastGhostAt = 0L
                }
                if (dragging && g != null) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGhostAt >= GHOST_FRAME_MS) {
                        lastGhostAt = now
                        moveGhost(dragTo(pg, g, p))
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val was = pressed ?: return false
                main.removeCallbacks(longPress)
                val g = grab
                if (dragging && g != null) {
                    val new = dragTo(pg, g, p)
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
                } else {
                    val tap = !gestureDead && SystemClock.uptimeMillis() - downAt <= TAP_MS
                    endGesture()
                    if (tap) select(if (selectedId == was.id) 0L else was.id)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val owned = pressed != null
                cancelGesture()
                return owned
            }
            // Extra fingers are ignored; the first one keeps the gesture.
            else -> return pressed != null
        }
    }

    private fun dragTo(pg: Page, g: Guides.Grab, p: Vec2): Ruler = Guides.dragged(
        g.ruler, g.handle, down, p, pg.widthMm, pg.heightMm,
        if (angleSnap) Guides.ANGLE_SNAP_DEG else 0f,
    )

    private fun moveGhost(new: Ruler) {
        val old = ghost
        if (old == new) return
        ghost = new
        redraw(old, new)
    }

    private fun onLongPress() {
        val r = pressed ?: return
        if (dragging) return
        gestureDead = true
        host.guidePage?.ruler(r.id)?.let { onAngleEntry?.invoke(it) }
    }

    private fun cancelGesture() {
        val g = ghost
        endGesture()
        g?.let {
            redraw(it, null)
            host.guideBackend.showRepaint()
        }
    }

    private fun endGesture() {
        main.removeCallbacks(longPress)
        if (dragging) host.guideBackend.setGuideDrag(false)
        dragging = false
        grab = null
        pressed = null
        ghost = null
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
