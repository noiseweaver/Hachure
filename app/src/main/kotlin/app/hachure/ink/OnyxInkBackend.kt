package app.hachure.ink

import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import app.hachure.model.Tool
import java.util.Locale

/**
 * Onyx pen SDK backend. The SDK draws the live stroke in A2 (fast, black and white); the app
 * commits the finished stroke to its own bitmap with one invalidate per pen-up.
 *
 * Evidence tags for every SDK behavior relied on:
 * - [V] verified: method exists with this signature in the vendored 1.4.12 / 1.7.7 / 1.2.31 AARs
 *   (javap), and the behavior is described in the unofficial docs.
 * - [I] inferred: from docs or reference apps, not confirmed for Android 11 / Note Air 2.
 * - [G] guess: needs on-device confirmation (see the PR checklist).
 */
class OnyxInkBackend : InkBackend {
    override val name = "Onyx SDK (A2)"
    override val rendersLiveStroke = true

    /**
     * Live A2 stroke style for the current brush ([V] STROKE_STYLE_* constants 0..6 in 1.4.12).
     * Neo brush and charcoal v2 are disabled by default in the notable app over crash reports
     * [I], so the UI labels them experimental.
     */
    var liveTool = Tool.PENCIL
        set(value) {
            field = value
            helper?.setStrokeStyle(sdkStyle(value))
        }

    /**
     * False while the line or eraser tool is active: the SDK still reports points but must not
     * ink them. [I per docs] setRawDrawingRenderEnabled(false) keeps callbacks flowing.
     * [G] that move callbacks keep arriving with render off (needed for the line preview).
     */
    var liveInk = true
        set(value) {
            if (field == value) return
            field = value
            appliedActive = null
            applyEnabled()
        }

    /** Wants move samples (line preview). Only sent while [liveInk] is off, throttled. */
    var wantMoves = false

    /** [V] EpdController.getMaxTouchPressure() exists. [I] about 4095 on Wacom EMR panels. */
    val maxTouchPressure: Float by lazy {
        runCatching { EpdController.getMaxTouchPressure() }.getOrNull()?.takeIf { it > 1f } ?: 4095f
    }

    /** Live A2 stroke width in px. */
    var liveWidthPx = 3f
        set(value) {
            field = value
            helper?.setStrokeWidth(value)
        }

    /**
     * Whether TouchPoint coordinates are screen-absolute or view-local. The docs say screen, but
     * on the Note Air 2 (Android 11) they are view-local: verified on device 2026-11-08 (strokes
     * landed under the live ink with "view"). Kept switchable in the debug toolbar for other
     * devices.
     */
    var pointsAreScreenAbsolute = false

    /**
     * Release the SDK whenever the pen is away, so Boox system gestures (status bar, settings
     * pull-down) work. The SDK's raw drawing claim blocks them while enabled ([V] on device
     * 2026-11-08; the docs describe the same). [G] Disabling raw drawing releases the claim and
     * re-enabling on pen proximity is fast enough not to lose the start of a stroke.
     */
    var releaseWhenPenAway = true
        set(value) {
            field = value
            applyEnabled()
        }

    /** Pen is near the canvas: hovering, drawing, or recently lifted. */
    private var penNear = false
    private var strokeInProgress = false
    private var wakeCount = 0
    private val idleRelease = Runnable { onPenIdle() }

    private var view: View? = null
    private var listener: InkListener? = null
    private var helper: TouchHelper? = null
    private val main = Handler(Looper.getMainLooper())

    private var limitRect = Rect()
    private var excludeRects: List<Rect> = emptyList()
    private var wantEnabled = true
    private var paused = false
    private var epdApplied = false
    private val viewOnScreen = IntArray(2)

    // Written on main, read on the SDK reader thread.
    @Volatile private var viewOffsetX = 0
    @Volatile private var viewOffsetY = 0

    private var reopenCount = 0
    private var lastError: String? = null
    @Volatile private var penUpAt = 0L
    private var lastListDelayMs = -1L

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { onLayout() }

    override fun attach(view: View, listener: InkListener) {
        this.view = view
        this.listener = listener
        // [V] TouchHelper must be created after the view has its real size and screen position,
        // so creation happens on the first global layout, not here.
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        applyEpdMode(view)
        if (view.isLaidOut) onLayout()
    }

    override fun detach() {
        val v = view
        v?.viewTreeObserver?.removeOnGlobalLayoutListener(layoutListener)
        main.removeCallbacks(idleRelease)
        helper?.let {
            runCatching { it.setRawDrawingEnabled(false) }
            // [V] closeRawDrawing releases the native reader and the A2 claim.
            runCatching { it.closeRawDrawing() }
        }
        helper = null
        if (v != null && epdApplied) {
            // [V] resetViewUpdateMode(View) exists; restores the system default for the view.
            runCatching { EpdController.resetViewUpdateMode(v) }
            epdApplied = false
        }
        view = null
        listener = null
    }

    override fun setExcludeRects(rects: List<Rect>) {
        val copy = rects.map { Rect(it) }
        if (copy == excludeRects) return
        excludeRects = copy
        // [V per docs] Updating rects on an open helper does nothing; close and reopen.
        if (helper != null) reopen()
    }

    override fun setEnabled(enabled: Boolean) {
        wantEnabled = enabled
        applyEnabled()
    }

    /**
     * [V on device 2026-11-10] With raw drawing enabled the expanded naviball got no touches.
     * [G] Opening the naviball menu takes window focus from the activity, so releasing on focus
     * loss frees it. The debug overlay shows the focus state to confirm.
     */
    private var windowFocused = true
    private var focusLosses = 0

    override fun setWindowFocused(focused: Boolean) {
        if (windowFocused == focused) return
        windowFocused = focused
        if (!focused) focusLosses++
        applyEnabled()
    }

    /**
     * [V] TouchHelper.setEraserRawDrawingEnabled exists in 1.4.12 and forwards to the device's
     * EPD controller, next to setBrushRawDrawingEnabled. [I] It makes the system ink the eraser
     * end's path live, like the pen tip. [G] Erase callbacks still fire with it on. Switchable
     * from More in case either guess is wrong on the Note Air 2.
     */
    private var eraserTrail = true

    override fun setEraserTrail(on: Boolean) {
        if (eraserTrail == on) return
        eraserTrail = on
        helper?.let { h -> runCatching { h.setEraserRawDrawingEnabled(on) }.onFailure { lastError = "trail: ${it.javaClass.simpleName}" } }
    }

    /**
     * [V on device 2026-11-10] The expanded naviball does not take window focus, so focus loss
     * alone did not free it. The activity listens for the Boox system UI broadcasts instead and
     * reports them here. [V on device 2026-11-10] Enabling raw drawing while the naviball menu
     * is open leaves the menu dead until it is collapsed and expanded again, so there is no
     * inking at all while it is open; the pen wake-up must not override this.
     */
    private var systemUiOpen: String? = null
    private var systemUiBlocks = 0

    override fun setSystemUiOpen(reason: String?) {
        if (systemUiOpen == reason) return
        if (reason != null && systemUiOpen == null) systemUiBlocks++
        systemUiOpen = reason
        applyEnabled()
    }

    override fun pause() {
        paused = true
        applyEnabled()
    }

    override fun resume() {
        paused = false
        applyEnabled()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The SDK reads the digitizer directly. Swallow stylus MotionEvents so the view does not
        // act on them; fingers pass through (they never draw). A stylus event here also means the
        // SDK was released, so wake it.
        val tool = event.getToolType(0)
        val isPen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (isPen) onPenActivity()
        return isPen
    }

    override fun onHoverEvent(event: MotionEvent) {
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        // Any hover sample, including exit, counts as activity; the idle timer handles release.
        onPenActivity()
    }

    /** Main thread only. */
    private fun onPenActivity() {
        main.removeCallbacks(idleRelease)
        if (!penNear) {
            penNear = true
            wakeCount++
            applyEnabled()
        }
        main.postDelayed(idleRelease, PEN_IDLE_MS)
    }

    private fun onPenIdle() {
        if (strokeInProgress) {
            main.postDelayed(idleRelease, PEN_IDLE_MS)
            return
        }
        penNear = false
        applyEnabled()
    }

    // While raw drawing is enabled the SDK holds the panel inside the limit rect: view
    // invalidates are not shown until it lets go. [V on device 2026-11-10] erased strokes stayed
    // on screen until the 1.5 s idle release, or for good while the pen stayed near. New ink
    // hides this (the commit matches the live ink), removed ink does not. So after such a repaint
    // raw drawing is switched off for one refresh, the pattern the Onyx demos use around redraws.
    // [G] REPAINT_HOLD_MS is long enough for the partial refresh to start.

    private var repaintPending = false
    private var repaintHold = false
    private var repaintCount = 0
    private val repaintDone = Runnable {
        repaintHold = false
        applyEnabled()
    }

    override fun showRepaint() {
        repaintPending = true
        // Toggling mid-gesture would cut the stroke or rub short; the end callbacks flush.
        if (!strokeInProgress) flushRepaint()
    }

    private fun flushRepaint() {
        if (!repaintPending) return
        repaintPending = false
        main.removeCallbacks(repaintDone)
        repaintHold = true
        repaintCount++
        applyEnabled()
        main.postDelayed(repaintDone, REPAINT_HOLD_MS)
    }

    override fun cleanScreen(target: View) {
        // [I] A full GC refresh while the SDK holds A2 can be ignored or fight the raw layer, so
        // inking is paused around it. [V] EpdController.invalidate(View, UpdateMode) exists.
        val h = helper
        runCatching { h?.setRawDrawingEnabled(false) }
        appliedActive = null
        try {
            EpdController.invalidate(target.rootView, UpdateMode.GC)
        } catch (t: Throwable) {
            lastError = "GC: ${t.javaClass.simpleName}"
            target.rootView.invalidate()
        }
        // Re-enable after the refresh has been queued and drawn.
        target.postDelayed({ applyEnabled() }, 300)
    }

    override fun setBrush(tool: Tool, widthPx: Float, liveInk: Boolean, wantMoves: Boolean) {
        liveTool = tool
        liveWidthPx = widthPx
        this.wantMoves = wantMoves
        this.liveInk = liveInk
    }

    override fun debugInfo(): List<String> = buildList {
        add("A2 live: ${liveTool.id}${if (liveInk) "" else " (ink off)"} ${String.format(Locale.US, "%.1f", liveWidthPx)}px, coords ${if (pointsAreScreenAbsolute) "screen" else "view"}")
        add("limit $limitRect, ${excludeRects.size} exclude, reopens $reopenCount")
        add("release when pen away: ${if (releaseWhenPenAway) "on" else "off"}, pen ${if (penNear) "near" else "away"}, wakes $wakeCount")
        add("commit from: $lastCommitSource, repaints $repaintCount")
        add("eraser trail ${if (eraserTrail) "on" else "off"}")
        add("system ui: ${systemUiOpen ?: "none"}, blocks $systemUiBlocks")
        add("window ${if (windowFocused) "focused" else "not focused"}, focus losses $focusLosses")
        if (lastListDelayMs >= 0) add("pen-up to point list: $lastListDelayMs ms")
        lastError?.let { add("error: $it") }
    }

    // Setup

    private fun onLayout() {
        val v = view ?: return
        if (v.width == 0 || v.height == 0) return
        val r = Rect()
        // [I] Limit rects are screen-absolute; getGlobalVisibleRect gives window coordinates,
        // which equal screen coordinates for this full-screen activity.
        if (!v.getGlobalVisibleRect(r)) return
        v.getLocationOnScreen(viewOnScreen)
        viewOffsetX = viewOnScreen[0]
        viewOffsetY = viewOnScreen[1]
        if (helper == null) {
            limitRect = r
            try {
                open(v)
            } catch (t: Throwable) {
                val l = listener
                // Detach from the layout pass before the listener swaps backends.
                main.post { l?.onBackendFailed(t) }
            }
        } else if (r != limitRect) {
            limitRect = r
            reopen()
        }
    }

    private fun open(v: View) {
        try {
            // [V] create(View, RawInputCallback). Sequence below follows the docs exactly.
            val h = TouchHelper.create(v, callback)
            helper = h
            configureAndOpen(h)
        } catch (t: Throwable) {
            lastError = "open: ${t.javaClass.simpleName}: ${t.message}"
            helper = null
            throw t
        }
    }

    private fun configureAndOpen(h: TouchHelper) {
        h.setStrokeWidth(liveWidthPx)
        h.setStrokeStyle(sdkStyle(liveTool))
        h.setStrokeColor(Color.BLACK)
        h.setLimitRect(mutableListOf(Rect(limitRect)), excludeRects.map { Rect(it) }.toMutableList())
        h.openRawDrawing()
        h.setRawDrawingRenderEnabled(liveInk)
        // [V] No SDK refresh at pen-up: the app's single commit invalidate replaces it.
        h.setPenUpRefreshEnabled(false)
        // [I] Default true per docs; set explicitly so a stylus button press does not erase.
        h.setBrushRawDrawingEnabled(true)
        runCatching { h.setEraserRawDrawingEnabled(eraserTrail) }
        // [V per docs] Without this no callbacks fire.
        h.setRawDrawingEnabled(isActive())
        appliedActive = isActive()
        if (appliedActive == false) h.setRawDrawingRenderEnabled(false)
    }

    private fun reopen() {
        val h = helper ?: return
        try {
            h.setRawDrawingEnabled(false)
            h.closeRawDrawing()
            appliedActive = null
            configureAndOpen(h)
            reopenCount++
        } catch (t: Throwable) {
            lastError = "reopen: ${t.javaClass.simpleName}"
        }
    }

    private fun isActive() =
        wantEnabled && !paused && windowFocused && systemUiOpen == null && !repaintHold && (penNear || !releaseWhenPenAway)

    private var appliedActive: Boolean? = null

    private fun applyEnabled() {
        val h = helper ?: return
        val active = isActive()
        if (active == appliedActive) return
        runCatching {
            if (active) {
                h.setRawDrawingRenderEnabled(liveInk)
                h.setRawDrawingEnabled(true)
            } else {
                h.setRawDrawingEnabled(false)
                // [I per docs] Turning render off is what hands gesture routing back to the system.
                h.setRawDrawingRenderEnabled(false)
            }
            appliedActive = active
        }.onFailure { lastError = "enable: ${it.javaClass.simpleName}" }
    }

    private fun applyEpdMode(v: View) {
        try {
            // [V] Both methods exist. [I] HAND_WRITING_REPAINT_MODE + enablePost(view, 1) is the
            // pattern used by reference apps so app redraws after a stroke stay fast and clean.
            EpdController.setViewDefaultUpdateMode(v, UpdateMode.HAND_WRITING_REPAINT_MODE)
            EpdController.enablePost(v, 1)
            epdApplied = true
        } catch (t: Throwable) {
            lastError = "epd: ${t.javaClass.simpleName}"
        }
    }

    // Callbacks. [I] The SDK delivers these on its reader thread, so everything is posted to main.

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {
            val rp = toRaw(p)
            strokeSeq++
            penUpAt = 0L
            moveBuffer.clear()
            moveBuffer += rp
            main.post {
                strokeInProgress = true
                onPenActivity()
                listener?.onStrokeBegin(rp)
            }
        }

        override fun onPenActive(p: TouchPoint) {
            main.post { onPenActivity() }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
            // Points arrive again in full in the list callback. While the SDK inks live they are
            // ignored: each app invalidate would cost a 100 to 300 ms e-ink refresh. The line
            // preview takes them, throttled to one per MOVE_THROTTLE_MS.
            moveBuffer += toRaw(p)
            if (!wantMoves || liveInk) return
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveSent < MOVE_THROTTLE_MS) return
            lastMoveSent = now
            val rp = moveBuffer.last()
            main.post { listener?.onStrokeMove(listOf(rp)) }
        }

        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {
            // [V on device 2026-11-09] Fires at pen-up, but the point list can arrive seconds
            // later (3.4 s seen on the Note Air 2). So the stroke is committed here from the
            // move samples, and the list is only a fallback when moves did not arrive.
            val up = SystemClock.uptimeMillis()
            penUpAt = up
            moveBuffer += toRaw(p)
            val pts = ArrayList(moveBuffer)
            val seq = strokeSeq
            // [G] Move callbacks carry every sample. If they did not arrive (2 points for a long
            // stroke), wait for the list instead of committing a straight segment.
            val usable = pts.size >= 3 || isDot(pts)
            if (usable) committedSeq = seq
            main.post {
                strokeInProgress = false
                onPenActivity()
                if (usable) {
                    lastCommitSource = "moves (${pts.size} pts)"
                    listener?.onStrokeEnd(pts, up)
                flushRepaint()
                }
            }
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val now = SystemClock.uptimeMillis()
            val up = if (penUpAt > 0) penUpAt else now
            val delay = now - up
            val seq = strokeSeq
            if (committedSeq == seq) {
                main.post { lastListDelayMs = delay }
                return
            }
            val pts = list.points.map { toRaw(it) }
            if (pts.isEmpty()) return
            committedSeq = seq
            main.post {
                strokeInProgress = false
                onPenActivity()
                lastListDelayMs = delay
                lastCommitSource = "point list (${pts.size} pts)"
                listener?.onStrokeEnd(pts, up)
                flushRepaint()
            }
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {
            strokeSeq++
            moveBuffer.clear()
            moveBuffer += toRaw(p)
            main.post {
                strokeInProgress = true
                onPenActivity()
            }
        }

        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {
            moveBuffer += toRaw(p)
        }

        // Same late-list issue as drawing: erase at pen-up from the move samples.
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {
            moveBuffer += toRaw(p)
            val pts = ArrayList(moveBuffer)
            val up = SystemClock.uptimeMillis()
            committedSeq = strokeSeq
            main.post {
                strokeInProgress = false
                onPenActivity()
                listener?.onEraseEnd(pts, up)
                flushRepaint()
            }
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            if (committedSeq == strokeSeq) return
            val pts = list.points.map { toRaw(it) }
            if (pts.isEmpty()) return
            committedSeq = strokeSeq
            val up = SystemClock.uptimeMillis()
            main.post {
                strokeInProgress = false
                onPenActivity()
                listener?.onEraseEnd(pts, up)
                flushRepaint()
            }
        }
    }

    private fun toRaw(p: TouchPoint): RawPoint {
        var x = p.x
        var y = p.y
        if (pointsAreScreenAbsolute) {
            x -= viewOffsetX
            y -= viewOffsetY
        }
        // [I] Pressure is normalised 0..1 in 1.4.x; clamp in case it is raw 0..4095.
        val pressure = if (p.pressure > 1f) p.pressure / 4095f else p.pressure
        // [V] TouchPoint.timestamp exists. [G] Its clock base is unconfirmed, so it is only used
        // for spacing within a stroke, never compared with uptime.
        return RawPoint(x, y, pressure.coerceIn(0f, 1f), p.timestamp)
    }

    @Volatile private var lastMoveSent = 0L

    // Reader thread only ([I] all RawInputCallback methods run on one SDK thread).
    private val moveBuffer = ArrayList<RawPoint>(512)
    @Volatile private var strokeSeq = 0
    @Volatile private var committedSeq = -1
    private var lastCommitSource = "none"

    /** A tap: begin and end within half a millimetre or so. */
    private fun isDot(pts: List<RawPoint>): Boolean {
        val a = pts.first()
        val b = pts.last()
        return kotlin.math.hypot(a.x - b.x, a.y - b.y) < 6f
    }

    private companion object {
        /** Line preview refresh interval. [G] fast enough to feel live in A2, slow enough to keep up. */
        const val MOVE_THROTTLE_MS = 60L

        fun sdkStyle(t: Tool): Int = when (t) {
            Tool.PENCIL -> TouchHelper.STROKE_STYLE_PENCIL
            Tool.FOUNTAIN -> TouchHelper.STROKE_STYLE_FOUNTAIN
            Tool.MARKER -> TouchHelper.STROKE_STYLE_MARKER
            Tool.BRUSH -> TouchHelper.STROKE_STYLE_NEO_BRUSH
            Tool.CHARCOAL -> TouchHelper.STROKE_STYLE_CHARCOAL
            Tool.DASH -> TouchHelper.STROKE_STYLE_DASH
            Tool.CHARCOAL2 -> TouchHelper.STROKE_STYLE_CHARCOAL_V2
        }

        /** Raw drawing stays off this long after an app repaint, so it reaches the screen. */
        const val REPAINT_HOLD_MS = 250L

        /** Release the SDK this long after the last pen activity. */
        const val PEN_IDLE_MS = 1500L
    }
}
