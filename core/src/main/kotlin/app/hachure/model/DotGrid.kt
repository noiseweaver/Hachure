package app.hachure.model

import kotlin.math.ceil
import kotlin.math.floor

/** A square dot grid in page mm, anchored at the page origin. */
data class DotGrid(val spacingMm: Float = 5f, val dotDiameterMm: Float = 0.35f) {
    init {
        require(spacingMm > 0f && dotDiameterMm > 0f)
    }

    /** Calls [dot] for every grid point inside the rect (inclusive). */
    inline fun forEachDot(left: Float, top: Float, right: Float, bottom: Float, dot: (Float, Float) -> Unit) {
        val i0 = ceil(left / spacingMm).toInt()
        val i1 = floor(right / spacingMm).toInt()
        val j0 = ceil(top / spacingMm).toInt()
        val j1 = floor(bottom / spacingMm).toInt()
        for (j in j0..j1) for (i in i0..i1) dot(i * spacingMm, j * spacingMm)
    }
}
