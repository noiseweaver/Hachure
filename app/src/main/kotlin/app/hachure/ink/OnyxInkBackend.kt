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

    private fun isActive() = wantEnabled && !paused && (penNear || !releaseWhenPenAway)

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
            if (!wantMoves || liveInk) return
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveSent < MOVE_THROTTLE_MS) return
            lastMoveSent = now
            val rp = toRaw(p)
            main.post { listener?.onStrokeMove(listOf(rp)) }
        }

        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {
            // [I per docs] Fires before the list callback.
            penUpAt = SystemClock.uptimeMillis()
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points.map { toRaw(it) }
            if (pts.isEmpty()) return
            val up = if (penUpAt > 0) penUpAt else SystemClock.uptimeMillis()
            val delay = SystemClock.uptimeMillis() - up
            main.post {
                strokeInProgress = false
                onPenActivity()
                lastListDelayMs = delay
                listener?.onStrokeEnd(pts, up)
            }
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {
            main.post {
                strokeInProgress = true
                onPenActivity()
            }
        }

        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}

        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points.map { toRaw(it) }
            if (pts.isEmpty()) return
            val up = SystemClock.uptimeMillis()
            main.post {
                strokeInProgress = false
                onPenActivity()
                listener?.onEraseEnd(pts, up)
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

        /** Release the SDK this long after the last pen activity. */
        const val PEN_IDLE_MS = 1500L
    }
}
