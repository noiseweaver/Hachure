package app.hachure.ink

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/** One raw stylus sample in view pixels. */
data class RawPoint(
    val x: Float,
    val y: Float,
    /** Normalised 0..1. */
    val pressure: Float,
    /** Event time, [android.os.SystemClock.uptimeMillis] base. */
    val timeMs: Long,
)

/** Callbacks from a backend. All are delivered on the UI thread. */
interface InkListener {
    fun onStrokeBegin(point: RawPoint)

    /**
     * New samples while the pen is down. Backends that render the live stroke themselves
     * (Onyx A2) may batch these or skip them; listeners must not depend on them for the commit.
     */
    fun onStrokeMove(points: List<RawPoint>)

    /** Pen up. [points] is the complete stroke, including the first sample. */
    fun onStrokeEnd(points: List<RawPoint>)

    /** Stylus eraser end: complete erase path. */
    fun onEraseEnd(points: List<RawPoint>)
}

/**
 * Source of stylus input for the drawing surface.
 *
 * Two implementations: [MotionEventInkBackend] (any Android device, the app draws the live
 * stroke) and, from Milestone 2, an Onyx backend where the SDK draws the live stroke in A2.
 */
interface InkBackend {
    /** Short name for the debug overlay. */
    val name: String

    /** True when the backend draws the in-progress stroke itself (the view must not). */
    val rendersLiveStroke: Boolean

    fun attach(view: View, listener: InkListener)

    /**
     * Screen-absolute rects where the backend must not take input or ink (toolbars, handles,
     * overlays). Implementations must reopen their input session when this changes.
     */
    fun setExcludeRects(rects: List<Rect>)

    /** Turn inking on or off, for example while a menu covers the canvas. */
    fun setEnabled(enabled: Boolean)

    /** Forwarded from the view. Returns true when the backend consumed the event. */
    fun onTouchEvent(event: MotionEvent): Boolean

    fun pause()

    fun resume()

    fun detach()
}
