package app.hachure.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import app.hachure.R
import app.hachure.geometry.Hatch
import app.hachure.geometry.RulerGeometry
import app.hachure.model.Ruler
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The strip along the bottom while a ruler is selected: its angle, 15 degree snap, hatch
 * direction and spacing, lock, delete, done. Same buttons and sizes (mm) as the rail.
 *
 * Its size never changes while it is shown, so its exclude rect is sent to the ink backend only
 * when it appears or goes.
 */
class GuideStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    enum class Action(val label: Int) {
        ANGLE(R.string.guide_angle),
        ANGLE_SNAP(R.string.guide_angle_snap),
        HATCH(R.string.guide_hatch),
        SPACING_DOWN(R.string.guide_closer),
        SPACING(R.string.guide_spacing),
        SPACING_UP(R.string.guide_wider),
        LOCK(R.string.guide_lock),
        DELETE(R.string.guide_delete),
        DONE(R.string.guide_done),
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
            b.glyphScale = if (a == Action.ANGLE || a == Action.SPACING) 0.5f else 0.62f
            when (a) {
                Action.LOCK -> b.icon = context.getDrawable(R.drawable.ic_lock)
                Action.DELETE -> b.icon = context.getDrawable(R.drawable.ic_delete)
                Action.SPACING_DOWN -> b.glyph = "−"
                Action.SPACING_UP -> b.glyph = "+"
                Action.ANGLE_SNAP -> b.glyph = "15°"
                Action.DONE -> b.glyph = "✓"
                else -> {}
            }
            if (a == Action.SPACING) b.isClickable = false else b.setOnClickListener { onAction?.invoke(a) }
            buttons[a] = b
            addView(b)
        }
        applySizes()
        visibility = GONE
    }

    private fun applySizes() {
        val h = mm(if (showLabels) 12f else 10f)
        for ((a, b) in buttons) {
            val w = mm(if (a == Action.ANGLE || a == Action.SPACING) 14f else 11f)
            b.layoutParams = LayoutParams(w, h)
            b.iconPx = mm(5f)
        }
        requestLayout()
    }

    /** Shows the strip for [r], or hides it when null. */
    fun show(r: Ruler?, angleSnap: Boolean) {
        if (r == null) {
            visibility = GONE
            return
        }
        buttons[Action.ANGLE]?.glyph = GuideController.angleLabel(r)
        buttons[Action.ANGLE_SNAP]?.isSelected = angleSnap
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
