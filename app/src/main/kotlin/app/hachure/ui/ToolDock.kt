package app.hachure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.hachure.R
import app.hachure.model.Tool
import app.hachure.model.WidthPreset
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The tool dock: a rail docked to the left or right edge, or a floating puck that opens a ring.
 *
 * - Drag the rail's grip away from the edge: it becomes the puck where it is let go.
 * - Drag the puck to either edge: it docks there as the rail.
 * - Tap the puck to open or close its ring; a finger tap anywhere else also closes it.
 *
 * Sizes are physical (mm, from the panel's xdpi), not dp: buttons are 9 mm, the fingertip minimum
 * from the spec. Every switch is one layout pass, so one partial e-ink refresh; the activity
 * re-sends exclude rects from its global layout listener.
 */
class ToolDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Dock { LEFT, RIGHT, FLOATING }

    enum class Item(val icon: Int, val label: Int) {
        SHELF(R.drawable.ic_shelf, R.string.tool_shelf),
        PENCIL(R.drawable.ic_pencil, R.string.tool_pencil),
        FOUNTAIN(R.drawable.ic_fountain, R.string.tool_fountain),
        STYLE(0, R.string.tool_more_styles),
        LINE(R.drawable.ic_line, R.string.tool_line),
        ERASER(R.drawable.ic_eraser, R.string.tool_eraser),
        WIDTH(0, R.string.tool_width),
        RULER(R.drawable.ic_ruler, R.string.tool_ruler),
        SNAP(R.drawable.ic_snap, R.string.tool_snap),
        LOCK(R.drawable.ic_lock, R.string.tool_lock_all),
        UNDO(R.drawable.ic_undo, R.string.tool_undo),
        REDO(R.drawable.ic_redo, R.string.tool_redo),
        CLEAN(R.drawable.ic_clean, R.string.tool_clean),
        MORE(R.drawable.ic_more, R.string.tool_more),
    }

    /** What the dock shows as selected and enabled. */
    data class State(
        val tool: Tool,
        val mode: CanvasView.Mode,
        val width: WidthPreset,
        val extraTool: Tool,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val snap: Boolean = true,
        val lockAll: Boolean = false,
    )

    /** Tapped item and the button it came from (anchor for menus). */
    var onItem: ((Item, View) -> Unit)? = null

    /** Dock position changed by a drag; the activity saves it. */
    var onDockChanged: ((Dock, Float, Float) -> Unit)? = null

    private val dpmm = resources.displayMetrics.xdpi.let { if (it in 100f..700f) it else 227f } / 25.4f
    private fun mm(v: Float) = (v * dpmm).toInt()

    private val rail = Rail(context)
    private val grip = DockButton(context)
    private val puck = DockButton(context)
    private val ring = FrameLayout(context)
    private val railButtons = LinkedHashMap<Item, DockButton>()
    private val ringButtons = LinkedHashMap<Item, DockButton>()

    var dock = Dock.LEFT
        private set
    private var puckX = 0f
    private var puckY = 0f
    private var pendingFraction: Pair<Float, Float>? = null
    private var ringOpen = false

    var showLabels = false
        set(value) {
            field = value
            (railButtons.values + ringButtons.values + listOf(grip, puck)).forEach { it.showLabel = value }
            applyDock()
        }

    private var state: State? = null

    init {
        clipChildren = false
        rail.orientation = LinearLayout.VERTICAL
        rail.setBackgroundColor(Color.WHITE)
        grip.glyph = GRIP_GLYPH
        grip.label = context.getString(R.string.tool_grip)
        grip.contentDescription = grip.label
        grip.setOnTouchListener(GripDrag())
        rail.addView(grip)

        for (item in Item.entries) {
            if (item == Item.UNDO) rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            railButtons[item] = makeButton(item).also { rail.addView(it) }
            ringButtons[item] = makeButton(item).also {
                it.round = true
                ring.addView(it)
            }
        }
        addView(rail)

        puck.round = true
        puck.label = context.getString(R.string.tool_puck)
        puck.setOnTouchListener(PuckDrag())
        addView(puck)

        ring.visibility = GONE
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ring.clipChildren = false
        applyDock()
    }

    private fun makeButton(item: Item) = DockButton(context).apply {
        if (item.icon != 0) icon = context.getDrawable(item.icon)
        label = context.getString(item.label)
        setOnClickListener { v ->
            onItem?.invoke(item, v)
            if (ringOpen && item.closesRing()) setRingOpen(false)
        }
    }

    private fun Item.closesRing() = when (this) {
        Item.UNDO, Item.REDO, Item.WIDTH, Item.CLEAN, Item.SNAP, Item.LOCK -> false
        else -> true
    }

    fun setState(s: State) {
        state = s
        val drawing = s.mode == CanvasView.Mode.DRAW
        for (map in listOf(railButtons, ringButtons)) {
            map[Item.PENCIL]?.isSelected = drawing && s.tool == Tool.PENCIL
            map[Item.FOUNTAIN]?.isSelected = drawing && s.tool == Tool.FOUNTAIN
            map[Item.STYLE]?.let {
                it.isSelected = drawing && s.tool != Tool.PENCIL && s.tool != Tool.FOUNTAIN
                it.glyph = shortName(if (it.isSelected) s.tool else s.extraTool) + "▾"
            }
            map[Item.LINE]?.isSelected = s.mode == CanvasView.Mode.LINE
            map[Item.ERASER]?.isSelected = s.mode == CanvasView.Mode.ERASE
            map[Item.WIDTH]?.widthBar = mm(WIDTH_BAR_MM[s.width.ordinal]).toFloat().coerceAtLeast(2f)
            map[Item.SNAP]?.isSelected = s.snap
            map[Item.LOCK]?.isSelected = s.lockAll
            map[Item.UNDO]?.isEnabled = s.canUndo
            map[Item.REDO]?.isEnabled = s.canRedo
        }
        puck.icon = context.getDrawable(
            when (s.mode) {
                CanvasView.Mode.LINE -> R.drawable.ic_line
                CanvasView.Mode.ERASE -> R.drawable.ic_eraser
                CanvasView.Mode.DRAW -> if (s.tool == Tool.FOUNTAIN) R.drawable.ic_fountain else R.drawable.ic_pencil
            },
        )
    }

    /** Restores a saved position. Fractions are of the dock's own size, applied after layout. */
    fun restore(d: Dock, fx: Float, fy: Float) {
        dock = d
        pendingFraction = fx to fy
        applyDock()
    }

    /** Screen rects the ink backend must leave alone. */
    fun excludeRects(): List<Rect> {
        val out = ArrayList<Rect>()
        fun add(v: View) {
            val r = Rect()
            if (v.visibility == VISIBLE && v.getGlobalVisibleRect(r)) out += r
        }
        if (dock == Dock.FLOATING) {
            add(puck)
            if (ringOpen) ringButtons.values.forEach { add(it) }
        } else {
            add(rail)
        }
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pendingFraction?.let { (fx, fy) ->
            puckX = fx * w
            puckY = fy * h
            pendingFraction = null
        }
        if (dock == Dock.FLOATING) post { applyDock() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nothing of ours was hit. A finger tap closes the ring; everything else falls through to
        // the canvas below (stylus never closes it, so drawing next to the ring works).
        if (ringOpen && event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        ) {
            setRingOpen(false)
            return true
        }
        return false
    }

    private fun railWidth() = mm(if (showLabels) RAIL_LABELLED_MM else RAIL_MM)
    private fun buttonHeight() = mm(if (showLabels) BUTTON_LABELLED_MM else BUTTON_MM)

    private fun applyDock() {
        val docked = dock != Dock.FLOATING
        rail.visibility = if (docked) VISIBLE else GONE
        puck.visibility = if (docked) GONE else VISIBLE
        if (docked) ringOpen = false
        ring.visibility = if (ringOpen) VISIBLE else GONE
        rail.ruleOnRight = dock == Dock.LEFT

        rail.layoutParams = LayoutParams(railWidth(), LayoutParams.MATCH_PARENT).apply {
            gravity = if (dock == Dock.RIGHT) Gravity.END else Gravity.START
        }
        val iconPx = mm(ICON_MM)
        grip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, mm(GRIP_MM))
        grip.iconPx = iconPx
        for (b in railButtons.values) {
            b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, buttonHeight())
            b.iconPx = iconPx
        }

        val puckSize = mm(PUCK_MM)
        if (!docked && width > 0) {
            val r = mm(RING_RADIUS_MM) + buttonHeight() / 2 + mm(EDGE_MARGIN_MM)
            puckX = puckX.coerceIn(r.toFloat(), (width - r).toFloat().coerceAtLeast(r.toFloat()))
            puckY = puckY.coerceIn(r.toFloat(), (height - r).toFloat().coerceAtLeast(r.toFloat()))
        }
        puck.iconPx = iconPx
        puck.layoutParams = LayoutParams(puckSize, puckSize).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (puckX - puckSize / 2f).toInt()
            topMargin = (puckY - puckSize / 2f).toInt()
        }

        val n = ringButtons.size
        val size = buttonHeight()
        val radius = mm(RING_RADIUS_MM).toFloat()
        ringButtons.values.forEachIndexed { i, b ->
            val a = -Math.PI / 2 + 2 * Math.PI * i / n
            b.iconPx = iconPx
            b.layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (puckX + radius * cos(a) - size / 2f).toInt()
                topMargin = (puckY + radius * sin(a) - size / 2f).toInt()
            }
        }
        requestLayout()
    }

    private fun setRingOpen(open: Boolean) {
        if (ringOpen == open) return
        ringOpen = open
        ring.visibility = if (open) VISIBLE else GONE
        puck.isSelected = open
    }

    private fun moveTo(d: Dock, x: Float, y: Float) {
        dock = d
        puckX = x
        puckY = y
        ringOpen = false
        puck.isSelected = false
        applyDock()
        onDockChanged?.invoke(d, if (width > 0) puckX / width else 0.5f, if (height > 0) puckY / height else 0.5f)
    }

    /** Where a drag that ends at screen-local x lands. */
    private fun dockFor(x: Float): Dock = when {
        x < mm(DOCK_ZONE_MM) -> Dock.LEFT
        x > width - mm(DOCK_ZONE_MM) -> Dock.RIGHT
        else -> Dock.FLOATING
    }

    private fun localX(e: MotionEvent): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return e.rawX - loc[0]
    }

    private fun localY(e: MotionEvent): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return e.rawY - loc[1]
    }

    // Drag ghost: a dashed outline of where the dock will land, drawn over everything while a
    // finger drags the grip or the puck. Redrawn at most every GHOST_FRAME_MS, invalidating only
    // the old and new outline, so each step is one small partial refresh.

    private enum class GhostKind { NONE, PUCK, RAIL_LEFT, RAIL_RIGHT }

    private var ghostKind = GhostKind.NONE
    private var ghostX = 0f
    private var ghostY = 0f
    private var lastGhostAt = 0L
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = RULE_PX
    }
    private val ghostDash by lazy { DashPathEffect(floatArrayOf(mm(2f).toFloat(), mm(1.2f).toFloat()), 0f) }
    private val ghostRect = Rect()

    init {
        setWillNotDraw(false)
    }

    private fun ghostBounds(out: Rect) {
        val pad = RULE_PX.toInt() + 2
        when (ghostKind) {
            GhostKind.NONE -> out.setEmpty()
            GhostKind.PUCK -> {
                val r = mm(PUCK_MM) / 2
                out.set(ghostX.toInt() - r - pad, ghostY.toInt() - r - pad, ghostX.toInt() + r + pad, ghostY.toInt() + r + pad)
            }
            GhostKind.RAIL_LEFT -> out.set(0, 0, railWidth() + pad, height)
            GhostKind.RAIL_RIGHT -> out.set(width - railWidth() - pad, 0, width, height)
        }
    }

    private fun showGhost(e: MotionEvent, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastGhostAt < GHOST_FRAME_MS) return
        lastGhostAt = now
        val x = localX(e)
        val kind = when (dockFor(x)) {
            Dock.LEFT -> GhostKind.RAIL_LEFT
            Dock.RIGHT -> GhostKind.RAIL_RIGHT
            Dock.FLOATING -> GhostKind.PUCK
        }
        val old = Rect()
        ghostBounds(old)
        ghostKind = kind
        ghostX = x
        ghostY = localY(e)
        ghostBounds(ghostRect)
        if (ghostKind != GhostKind.NONE && old.isEmpty) fastRefresh(true)
        old.union(ghostRect)
        invalidate(old)
    }

    private fun hideGhost() {
        if (ghostKind == GhostKind.NONE) return
        ghostBounds(ghostRect)
        ghostKind = GhostKind.NONE
        invalidate(ghostRect)
        fastRefresh(false)
    }

    /**
     * [V] EpdController.setViewDefaultUpdateMode / resetViewUpdateMode exist in onyxsdk-device
     * 1.2.31. [I] DU makes the moving outline refresh quickly in black and white. No-op off Boox.
     */
    private fun fastRefresh(on: Boolean) {
        if (!app.hachure.ink.InkBackends.isOnyxDevice()) return
        runCatching {
            if (on) {
                com.onyx.android.sdk.api.device.epd.EpdController.setViewDefaultUpdateMode(
                    this, com.onyx.android.sdk.api.device.epd.UpdateMode.DU,
                )
            } else {
                com.onyx.android.sdk.api.device.epd.EpdController.resetViewUpdateMode(this)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (ghostKind == GhostKind.NONE) return
        ghostPaint.pathEffect = ghostDash
        val half = RULE_PX / 2
        when (ghostKind) {
            GhostKind.PUCK -> canvas.drawCircle(ghostX, ghostY, mm(PUCK_MM) / 2f - half, ghostPaint)
            GhostKind.RAIL_LEFT -> canvas.drawRect(half, half, railWidth() - half, height - half, ghostPaint)
            GhostKind.RAIL_RIGHT -> canvas.drawRect(width - railWidth() + half, half, width - half, height - half, ghostPaint)
            GhostKind.NONE -> {}
        }
        ghostPaint.pathEffect = null
    }

    private inner class GripDrag : OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> showGhost(e)
                MotionEvent.ACTION_UP -> {
                    hideGhost()
                    val x = localX(e)
                    val target = dockFor(x)
                    if (target != dock) moveTo(target, x, localY(e))
                }
                MotionEvent.ACTION_CANCEL -> hideGhost()
            }
            return true
        }
    }

    private inner class PuckDrag : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) >= mm(TAP_SLOP_MM)) {
                        dragging = true
                        if (ringOpen) setRingOpen(false)
                        showGhost(e, force = true)
                    } else if (dragging) {
                        showGhost(e)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    hideGhost()
                    if (!dragging) {
                        setRingOpen(!ringOpen)
                    } else {
                        val x = localX(e)
                        moveTo(dockFor(x), x, localY(e))
                    }
                }
                MotionEvent.ACTION_CANCEL -> hideGhost()
            }
            return true
        }
    }

    /** Rail with the 2 px rule on its canvas-facing edge. */
    private class Rail(context: Context) : LinearLayout(context) {
        var ruleOnRight = true
        private val rule = Paint().apply { color = Color.BLACK }

        init {
            setWillNotDraw(false)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val x = if (ruleOnRight) width - RULE_PX else 0f
            canvas.drawRect(x, 0f, x + RULE_PX, height.toFloat(), rule)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    private companion object {
        const val RAIL_MM = 10f
        const val RAIL_LABELLED_MM = 15f
        const val BUTTON_MM = 9f
        const val BUTTON_LABELLED_MM = 12f
        const val GRIP_MM = 7f
        const val ICON_MM = 5f
        const val PUCK_MM = 12f
        /** 14 buttons of 9 mm need a circumference of at least 126 mm. */
        const val RING_RADIUS_MM = 23f
        const val EDGE_MARGIN_MM = 2f
        const val DOCK_ZONE_MM = 15f
        const val TAP_SLOP_MM = 3f
        const val RULE_PX = 4f
        const val GRIP_GLYPH = "≡"
        const val GHOST_FRAME_MS = 50L

        /** Visual bar thickness for fine, medium, bold. */
        val WIDTH_BAR_MM = floatArrayOf(0.35f, 0.8f, 1.5f)

        fun shortName(t: Tool) = when (t) {
            Tool.PENCIL -> "PN"
            Tool.FOUNTAIN -> "FT"
            Tool.MARKER -> "MK"
            Tool.BRUSH -> "BR"
            Tool.CHARCOAL -> "CH"
            Tool.DASH -> "DS"
            Tool.CHARCOAL2 -> "C2"
        }
    }
}
