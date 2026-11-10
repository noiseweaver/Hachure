package app.hachure.model

/**
 * A ruler on the page: a bar with a drawing edge on each long side. All values in page mm.
 *
 * @property angleDeg direction of the bar from its start end to its end end, clockwise on screen
 *   from the +x axis (y points down), in [0, 360). Not folded to 180: turning the bar half way
 *   round swaps its ends and its normal, and the hatch direction must not flip under the user.
 * @property hatchDir 0 = hatching off; +1 or -1 = after each snapped stroke the ruler steps by
 *   [hatchSpacingMm] along (+1) or against (-1) its normal, see [app.hachure.geometry.RulerGeometry.normal]
 */
data class Ruler(
    val id: Long,
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
    val lengthMm: Float = DEFAULT_LENGTH_MM,
    val widthMm: Float = DEFAULT_WIDTH_MM,
    val locked: Boolean = false,
    val hatchSpacingMm: Float = DEFAULT_SPACING_MM,
    val hatchDir: Int = 0,
) {
    init {
        require(lengthMm > 0f && widthMm > 0f) { "ruler size must be positive" }
        require(hatchSpacingMm > 0f) { "hatch spacing must be positive" }
        require(hatchDir in -1..1) { "hatchDir must be -1, 0 or 1" }
    }

    companion object {
        const val DEFAULT_LENGTH_MM = 130f

        /** Wide enough that the 9 mm handles sit clear of both edges at any angle. */
        const val DEFAULT_WIDTH_MM = 14f
        const val DEFAULT_SPACING_MM = 2f
        const val MIN_SPACING_MM = 0.5f
        const val MAX_SPACING_MM = 20f
        const val SPACING_STEP_MM = 0.5f

        fun wrapAngle(deg: Float): Float {
            var a = deg % 360f
            if (a < 0f) a += 360f
            return if (a >= 360f) 0f else a
        }

        /** Angle in [0, 180), for display: a ruler looks the same turned half way round. */
        fun normalizeAngle(deg: Float): Float {
            var a = deg % 180f
            if (a < 0f) a += 180f
            return if (a >= 180f) 0f else a
        }
    }
}
