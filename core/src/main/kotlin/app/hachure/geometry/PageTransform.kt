package app.hachure.geometry

/**
 * Maps page coordinates (mm) to view coordinates (px) with a uniform scale and an offset.
 *
 * At Milestone 1 the page fills the view at the panel's physical density, so 1 mm on the page is
 * 1 mm on the glass. Zoom and pan (a later milestone) only change [scale] and [offset].
 */
data class PageTransform(
    /** Pixels per millimetre. */
    val scale: Float,
    /** View position (px) of the page origin. */
    val offset: Vec2 = Vec2.ZERO,
) {
    init {
        require(scale > 0f) { "scale must be positive, was $scale" }
    }

    fun pageToView(p: Vec2): Vec2 = Vec2(p.x * scale + offset.x, p.y * scale + offset.y)

    fun viewToPage(v: Vec2): Vec2 = Vec2((v.x - offset.x) / scale, (v.y - offset.y) / scale)

    fun lengthToView(mm: Float): Float = mm * scale

    fun lengthToPage(px: Float): Float = px / scale

    companion object {
        fun forDpi(dpi: Float, offset: Vec2 = Vec2.ZERO) =
            PageTransform(scale = dpi / Units.MM_PER_INCH, offset = offset)
    }
}
