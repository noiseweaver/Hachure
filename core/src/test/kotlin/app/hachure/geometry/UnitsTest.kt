package app.hachure.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test fun oneInchIsDpiPixels() {
        assertEquals(227f, Units.mmToPx(25.4f, 227f), 1e-3f)
    }

    @Test fun roundTrip() {
        val mm = 5f
        assertEquals(mm, Units.pxToMm(Units.mmToPx(mm, 227f), 227f), 1e-4f)
    }

    @Test fun fiveMmOnNoteAir2() {
        // Default dot grid spacing: 5 mm is about 44.7 px at 227 dpi.
        assertEquals(44.685f, Units.mmToPx(5f, Units.NOTE_AIR_2_DPI), 1e-2f)
    }
}
