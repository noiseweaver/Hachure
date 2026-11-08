package app.hachure.model

/**
 * One sampled stylus point in page units.
 *
 * @property x page x in mm
 * @property y page y in mm
 * @property pressure normalised 0..1
 * @property t milliseconds since the stroke started
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val t: Long,
) {
    init {
        require(pressure in 0f..1f) { "pressure must be in 0..1, was $pressure" }
        require(t >= 0) { "t must be non-negative, was $t" }
    }
}
