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

    /** Live A2 stroke style. Cycled from the debug toolbar to compare against the final render. */
    enum class LiveStyle(val label: String, val sdkValue: Int) {
        PENCIL("pencil", TouchHelper.STROKE_STYLE_PENCIL),
        FOUNTAIN("fountain", TouchHelper.STROKE_STYLE_FOUNTAIN),
        MARKER("marker", TouchHelper.STROKE_STYLE_MARKER),
    }

    var liveStyle = LiveStyle.PENCIL
        set(value) {
            field = value
            helper?.setStrokeStyle(value.sdkValue)
        }

    /** Live A2 stroke width in px. */
    var liveWidthPx = 3f
        set(value) {
            field = value
            helper?.setStrokeWidth(value)
        }

    /**
     * Whether TouchPoint coordinates are screen-absolute (docs say so [I]) or view-local.
     * Toggleable from the debug toolbar so a wrong guess is fixable without a new build.
     */
    var pointsAreScreenAbsolute = true

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
        // act on them; fingers pass through (they never draw).
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun cleanScreen(target: View) {
        // [I] A full GC refresh while the SDK holds A2 can be ignored or fight the raw layer, so
        // inking is paused around it. [V] EpdController.invalidate(View, UpdateMode) exists.
        val h = helper
        runCatching { h?.setRawDrawingEnabled(false) }
        try {
            EpdController.invalidate(target.rootView, UpdateMode.GC)
        } catch (t: Throwable) {
            lastError = "GC: ${t.javaClass.simpleName}"
            target.rootView.invalidate()
        }
        // Re-enable after the refresh has been queued and drawn.
        target.postDelayed({ applyEnabled() }, 300)
    }

    override fun debugInfo(): List<String> = buildList {
        add("A2 live: ${liveStyle.label} ${String.format(Locale.US, "%.1f", liveWidthPx)}px, coords ${if (pointsAreScreenAbsolute) "screen" else "view"}")
        add("limit $limitRect, ${excludeRects.size} exclude, reopens $reopenCount")
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
        h.setStrokeStyle(liveStyle.sdkValue)
        h.setStrokeColor(Color.BLACK)
        h.setLimitRect(mutableListOf(Rect(limitRect)), excludeRects.map { Rect(it) }.toMutableList())
        h.openRawDrawing()
        h.setRawDrawingRenderEnabled(true)
        // [V] No SDK refresh at pen-up: the app's single commit invalidate replaces it.
        h.setPenUpRefreshEnabled(false)
        // [I] Default true per docs; set explicitly so a stylus button press does not erase.
        h.setBrushRawDrawingEnabled(true)
        // [V per docs] Without this no callbacks fire.
        h.setRawDrawingEnabled(isActive())
    }

    private fun reopen() {
        val h = helper ?: return
        try {
            h.setRawDrawingEnabled(false)
            h.closeRawDrawing()
            configureAndOpen(h)
            reopenCount++
        } catch (t: Throwable) {
            lastError = "reopen: ${t.javaClass.simpleName}"
        }
    }

    private fun isActive() = wantEnabled && !paused

    private fun applyEnabled() {
        val h = helper ?: return
        runCatching { h.setRawDrawingEnabled(isActive()) }
            .onFailure { lastError = "enable: ${it.javaClass.simpleName}" }
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
            main.post { listener?.onStrokeBegin(rp) }
        }

        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
            // Points arrive again in full in the list callback. No invalidate here: each one
            // would cost a 100 to 300 ms e-ink refresh.
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
                lastListDelayMs = delay
                listener?.onStrokeEnd(pts, up)
            }
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {}

        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}

        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            val pts = list.points.map { toRaw(it) }
            if (pts.isEmpty()) return
            val up = SystemClock.uptimeMillis()
            main.post { listener?.onEraseEnd(pts, up) }
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
}
