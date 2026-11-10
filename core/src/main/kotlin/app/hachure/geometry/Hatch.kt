package app.hachure.geometry

import app.hachure.model.Ruler

/** Hatching: the ruler steps sideways after each stroke drawn along it. */
object Hatch {
    /** Offset one hatch step applies to the ruler's centre; zero when hatching is off. */
    fun step(r: Ruler): Vec2 =
        if (r.hatchDir == 0) Vec2.ZERO else RulerGeometry.normal(r) * (r.hatchSpacingMm * r.hatchDir)

    fun stepped(r: Ruler): Ruler {
        val s = step(r)
        return r.copy(cx = r.cx + s.x, cy = r.cy + s.y)
    }

    /** Spacing changed by [steps] increments, kept inside the allowed range. */
    fun spacingChanged(r: Ruler, steps: Int): Ruler = r.copy(
        hatchSpacingMm = (r.hatchSpacingMm + steps * Ruler.SPACING_STEP_MM)
            .coerceIn(Ruler.MIN_SPACING_MM, Ruler.MAX_SPACING_MM),
    )

    /** Cycles hatching: off, along the normal, against the normal. */
    fun nextDirection(r: Ruler): Ruler = r.copy(
        hatchDir = when (r.hatchDir) {
            0 -> 1
            1 -> -1
            else -> 0
        },
    )
}
