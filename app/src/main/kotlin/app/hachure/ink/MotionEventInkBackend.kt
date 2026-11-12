package app.hachure.ink

import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Plain [MotionEvent] input. Works on any device, including the emulator used in CI.
 *
 * The stylus draws. A finger never draws, except on devices with no stylus at all (emulator),
 * where it stands in for one so the UI can be exercised.
 */
class MotionEventInkBackend : InkBackend {
    override val name = "MotionEvent"
    override val rendersLiveStroke = false

    private var view: View? = null
    private var listener: InkListener? = null
    private var enabled = true
    private var paused = false
    private var excludeRects: List<Rect> = emptyList()
    private var fingerDraws = false

    private val current = ArrayList<RawPoint>(512)
    private var activeTool = MotionEvent.TOOL_TYPE_UNKNOWN

    override fun attach(view: View, listener: InkListener) {
        this.view = view
        this.listener = listener
        fingerDraws = !hasStylusDevice()
    }

    override fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects.map { Rect(it) }
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) cancel()
    }

    override fun pause() {
        paused = true
        cancel()
    }

    override fun resume() {
        paused = false
    }

    override fun detach() {
        cancel()
        view = null
        listener = null
    }

    private var lastPenAt = 0L

    override fun msSincePen(): Long =
        if (lastPenAt == 0L) Long.MAX_VALUE else android.os.SystemClock.uptimeMillis() - lastPenAt

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    override fun cleanScreen(target: View) {
        target.invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = listener ?: return false
        if (!enabled || paused) return false
        if (isPen(event.getToolType(0))) lastPenAt = android.os.SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = event.getToolType(0)
                if (!isInkTool(tool)) return false
                if (isExcluded(event.rawX, event.rawY)) return false
                activeTool = tool
                current.clear()
                val p = sample(event, 0, -1)
                current += p
                l.onStrokeBegin(p, liveInked = false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (current.isEmpty()) return false
                val batch = ArrayList<RawPoint>(event.historySize + 1)
                for (h in 0 until event.historySize) batch += sample(event, 0, h)
                batch += sample(event, 0, -1)
                current += batch
                l.onStrokeMove(batch)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (current.isEmpty()) return false
                val last = sample(event, 0, -1)
                if (current.last().x != last.x || current.last().y != last.y) current += last
                val stroke = ArrayList(current)
                current.clear()
                if (activeTool == MotionEvent.TOOL_TYPE_ERASER) {
                    l.onEraseEnd(stroke, event.eventTime)
                } else {
                    l.onStrokeEnd(stroke, event.eventTime)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancel()
                return true
            }

            // A second pointer (palm, second finger) is ignored, the stroke continues.
            else -> return current.isNotEmpty()
        }
    }

    private fun cancel() {
        current.clear()
    }

    private fun isInkTool(tool: Int): Boolean = when (tool) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE -> true
        MotionEvent.TOOL_TYPE_FINGER -> fingerDraws
        else -> false
    }

    private fun isExcluded(rawX: Float, rawY: Float): Boolean {
        val x = rawX.toInt()
        val y = rawY.toInt()
        return excludeRects.any { it.contains(x, y) }
    }

    /** [historyIndex] -1 means the current sample. Coordinates are view-local. */
    private fun sample(e: MotionEvent, pointer: Int, historyIndex: Int): RawPoint {
        return if (historyIndex < 0) {
            RawPoint(e.getX(pointer), e.getY(pointer), e.getPressure(pointer).coerceIn(0f, 1f), e.eventTime)
        } else {
            RawPoint(
                e.getHistoricalX(pointer, historyIndex),
                e.getHistoricalY(pointer, historyIndex),
                e.getHistoricalPressure(pointer, historyIndex).coerceIn(0f, 1f),
                e.getHistoricalEventTime(historyIndex),
            )
        }
    }

    private fun hasStylusDevice(): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val dev = InputDevice.getDevice(id) ?: return@any false
            dev.supportsSource(InputDevice.SOURCE_STYLUS)
        }
}
