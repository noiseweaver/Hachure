package app.hachure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import app.hachure.R
import app.hachure.geometry.Aim
import app.hachure.geometry.Hatch
import app.hachure.geometry.PerspectiveGeometry
import app.hachure.geometry.PerspectiveItem
import app.hachure.model.Perspective
import app.hachure.model.Ruler
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The strip along the bottom while a guide is selected. Same buttons and sizes (mm) as the rail.
 *
 * - Ruler: angle, 15 degree snap, aim at a vanishing point, hatch direction and spacing, lock,
 *   delete, done.
 * - Perspective: which item is selected (VP1..3 or the horizon), number of points, guide lines
 *   on or off, their density, lock for the selected item, done.
 *
 * Its size changes only when it appears, goes, or switches between the two, so its exclude rect
 * is re-sent to the ink backend only then.
 */
class GuideStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private enum class Mode { RULER, PERSPECTIVE, BOTH }

    enum class Action(val label: Int, private val mode: Mode, val wide: Boolean = false) {
        ANGLE(R.string.guide_angle, Mode.RULER, wide = true),
        ANGLE_SNAP(R.string.guide_angle_snap, Mode.RULER),
        AIM(R.string.guide_aim, Mode.RULER),
        HATCH(R.string.guide_hatch, Mode.RULER),
        SPACING_DOWN(R.string.guide_closer, Mode.RULER),
        SPACING(R.string.guide_spacing, Mode.RULER, wide = true),
        SPACING_UP(R.string.guide_wider, Mode.RULER),
        ITEM(R.string.persp_item, Mode.PERSPECTIVE, wide = true),
        POINTS(R.string.persp_points, Mode.PERSPECTIVE, wide = true),
        LINES(R.string.persp_lines, Mode.PERSPECTIVE, wide = true),
        FEWER(R.string.persp_fewer, Mode.PERSPECTIVE),
        DENSITY(R.string.persp_density, Mode.PERSPECTIVE),
        MORE(R.string.persp_more, Mode.PERSPECTIVE),
        LOCK(R.string.guide_lock, Mode.BOTH),
        DELETE(R.string.guide_delete, Mode.RULER),
        DONE(R.string.guide_done, Mode.BOTH);

        fun inRuler() = mode != Mode.PERSPECTIVE
        fun inPerspective() = mode != Mode.RULER
    }

    var onAction: ((Action) -> Unit)? = null

    private val dpmm = resources.displayMetrics.xdpi.let { if (it in 100f..700f) it else 227f } / 25.4f
    private fun mm(v: Float) = (v * dpmm).toInt()
    private val buttons = LinkedHashMap<Action, DockButton>()
    private val rule = Paint().apply { color = Color.BLACK }

    var showLabels = false
        set(value) {
            field = value
            buttons.values.forEach { it.showLabel = value }
            applySizes()
        }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        setWillNotDraw(false)
        setPadding(RULE_PX.toInt(), RULE_PX.toInt(), RULE_PX.toInt(), 0)
        for (a in Action.entries) {
            val b = DockButton(context)
            b.label = context.getString(a.label)
            b.glyphScale = if (a.wide) 0.5f else 0.62f
            when (a) {
                Action.LOCK -> b.icon = context.getDrawable(R.drawable.ic_lock)
                Action.DELETE -> b.icon = context.getDrawable(R.drawable.ic_delete)
                Action.SPACING_DOWN, Action.FEWER -> b.glyph = "−"
                Action.SPACING_UP, Action.MORE -> b.glyph = "+"
                Action.ANGLE_SNAP -> b.glyph = "15°"
                Action.DONE -> b.glyph = "✓"
                else -> {}
            }
            if (a == Action.SPACING || a == Action.DENSITY) b.isClickable = false else b.setOnClickListener { onAction?.invoke(a) }
            buttons[a] = b
            addView(b)
        }
        applySizes()
        visibility = GONE
    }

    private fun applySizes() {
        val h = mm(if (showLabels) 12f else 10f)
        for ((a, b) in buttons) {
            b.layoutParams = LayoutParams(mm(if (a.wide) 14f else 11f), h)
            b.iconPx = mm(5f)
        }
        requestLayout()
    }

    fun hide() {
        visibility = GONE
    }

    /** Shows the ruler controls for [r]. */
    fun showRuler(r: Ruler, angleSnap: Boolean, perspective: Perspective?) {
        for ((a, b) in buttons) b.visibility = if (a.inRuler()) VISIBLE else GONE
        val aimed = Aim.active(r, perspective)
        buttons[Action.ANGLE]?.glyph = GuideController.angleLabel(r)
        buttons[Action.ANGLE_SNAP]?.let {
            it.isSelected = angleSnap && !aimed
            it.isEnabled = !aimed
        }
        buttons[Action.AIM]?.let {
            it.glyph = if (aimed) context.getString(R.string.guide_aim_vp, r.aimVp) else context.getString(R.string.guide_aim_free)
            it.isSelected = aimed
            it.isEnabled = perspective != null && perspective.points > 0
        }
        buttons[Action.HATCH]?.let {
            it.glyph = hatchGlyph(r)
            it.isSelected = r.hatchDir != 0
        }
        buttons[Action.SPACING]?.glyph = String.format(Locale.US, "%.1fmm", r.hatchSpacingMm)
        buttons[Action.SPACING_DOWN]?.isEnabled = r.hatchSpacingMm > Ruler.MIN_SPACING_MM
        buttons[Action.SPACING_UP]?.isEnabled = r.hatchSpacingMm < Ruler.MAX_SPACING_MM
        buttons[Action.LOCK]?.isSelected = r.locked
        visibility = VISIBLE
    }

    /** Shows the perspective controls with [item] selected. */
    fun showPerspective(p: Perspective, item: PerspectiveItem) {
        for ((a, b) in buttons) b.visibility = if (a.inPerspective()) VISIBLE else GONE
        val on = p.points > 0
        buttons[Action.ITEM]?.let {
            it.glyph = when (item) {
                is PerspectiveItem.Vp -> context.getString(R.string.persp_vp, item.index + 1)
                PerspectiveItem.Horizon -> context.getString(R.string.persp_horizon)
            }
            it.isEnabled = on
        }
        buttons[Action.POINTS]?.let {
            it.glyph = if (on) context.getString(R.string.persp_points_n, p.points) else context.getString(R.string.guide_hatch_off)
            it.isSelected = on
        }
        buttons[Action.LINES]?.let {
            it.glyph = context.getString(R.string.persp_lines_glyph)
            it.isSelected = on && p.linesVisible
            it.isEnabled = on
        }
        val steps = Perspective.RAY_STEPS
        buttons[Action.DENSITY]?.glyph = p.rays.toString()
        buttons[Action.FEWER]?.isEnabled = on && p.linesVisible && p.rays > steps.first()
        buttons[Action.MORE]?.isEnabled = on && p.linesVisible && p.rays < steps.last()
        buttons[Action.LOCK]?.let {
            it.isSelected = on && PerspectiveGeometry.locked(p, item, lockAll = false)
            it.isEnabled = on
        }
        visibility = VISIBLE
    }

    /** An arrow pointing the way the ruler will step on screen, or "off". */
    private fun hatchGlyph(r: Ruler): String {
        if (r.hatchDir == 0) return context.getString(R.string.guide_hatch_off)
        val s = Hatch.step(r)
        val octant = (Math.toDegrees(atan2(s.y.toDouble(), s.x.toDouble())) / 45.0).roundToInt().mod(8)
        return ARROWS[octant]
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat()
        canvas.drawRect(0f, 0f, w, RULE_PX, rule)
        canvas.drawRect(0f, 0f, RULE_PX, height.toFloat(), rule)
        canvas.drawRect(w - RULE_PX, 0f, w, height.toFloat(), rule)
    }

    /** Touches between buttons must not fall through to the canvas. */
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private companion object {
        const val RULE_PX = 4f

        /** Screen directions clockwise from "right" (screen y points down). */
        val ARROWS = arrayOf("→", "↘", "↓", "↙", "←", "↖", "↑", "↗")
    }
}
