package app.hachure.model

/** A vanishing point in page mm. It may lie outside the page. */
data class VanishingPoint(val x: Float, val y: Float, val locked: Boolean = false)

/**
 * Perspective guides of a page.
 *
 * @property points 0 = off, otherwise 1, 2 or 3-point perspective; the first [points] entries of
 *   [vps] are in use. All three are always stored so switching modes keeps their places.
 * @property horizonLocked the horizon cannot be dragged, and VP1 and VP2 only slide along it
 * @property rays radiating guide lines per vanishing point (full lines through it)
 */
data class Perspective(
    val points: Int = 0,
    val vps: List<VanishingPoint>,
    val horizonLocked: Boolean = false,
    val linesVisible: Boolean = true,
    val rays: Int = DEFAULT_RAYS,
) {
    init {
        require(points in 0..3) { "points must be 0..3" }
        require(vps.size == 3) { "three vanishing points are always stored" }
        require(rays > 0) { "rays must be positive" }
    }

    val active: List<VanishingPoint> get() = vps.subList(0, points)

    fun withVp(index: Int, vp: VanishingPoint): Perspective =
        copy(vps = vps.mapIndexed { i, old -> if (i == index) vp else old })

    companion object {
        const val DEFAULT_RAYS = 24
        val RAY_STEPS = listOf(12, 24, 36, 72)

        /** Off, with the three points placed sensibly for when it is switched on. */
        fun default(pageWidthMm: Float, pageHeightMm: Float) = Perspective(
            points = 0,
            vps = listOf(
                VanishingPoint(pageWidthMm * 0.2f, pageHeightMm * 0.4f),
                VanishingPoint(pageWidthMm * 0.8f, pageHeightMm * 0.4f),
                VanishingPoint(pageWidthMm * 0.5f, pageHeightMm * 0.92f),
            ),
        )
    }
}
