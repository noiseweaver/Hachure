package app.hachure.geometry

/**
 * Conversions between page units (millimetres) and device pixels.
 *
 * All stored geometry is in mm so that a future zoom or a different panel does not break files.
 */
object Units {
    const val MM_PER_INCH = 25.4f

    /** Note Air 2 / Note Air 2 Plus panel density. */
    const val NOTE_AIR_2_DPI = 227f

    fun mmToPx(mm: Float, dpi: Float): Float = mm * dpi / MM_PER_INCH

    fun pxToMm(px: Float, dpi: Float): Float = px * MM_PER_INCH / dpi
}
