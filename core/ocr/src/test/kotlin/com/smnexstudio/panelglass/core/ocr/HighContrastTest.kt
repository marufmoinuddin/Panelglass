package com.smnexstudio.panelglass.core.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HighContrastTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** [ink] pixels out of 100 in [inkColor], the rest [bg]. */
    private fun crop(ink: Int, inkColor: Int, bg: Int) = IntArray(100) { if (it < ink) inkColor else bg }

    @Test
    fun darkLetteringOnLightStaysBlackOnWhite() {
        val out = HighContrast.binarize(crop(20, 0xFF202020.toInt(), 0xFFF0F0F0.toInt()))!!
        assertEquals(20, out.count { it == black })
    }

    /** Narration in white (or a light colour) on dark art comes out as black ink on white for ML Kit. */
    @Test
    fun lightLetteringOnDarkIsInverted() {
        val out = HighContrast.binarize(crop(25, 0xFFF0E070.toInt(), 0xFF101028.toInt()))!!
        assertEquals(25, out.count { it == black })
        assertEquals(black, out[0])
        assertEquals(white, out[99])
    }

    @Test
    fun aFlatCropHasNothingToSeparate() {
        assertNull(HighContrast.binarize(IntArray(100) { 0xFF808080.toInt() }))
        assertNull(HighContrast.binarize(IntArray(0)))
    }
}
