package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The reader letters an erased balloon inside its paper, in the best of several shapes ([RegionRenderer.balloonShapeOf]). */
class BalloonShapeTest {
    private val paper = 0xFFFFFFFF.toInt()
    private val ink = 0xFF000000.toInt()
    private val art = 0xFF606060.toInt()
    private val cropRect = IntRect(100, 50, 500, 450)

    /** The crop at [cropRect] of grey art holding an erased oval balloon at [balloon] (page pixels). */
    private fun crop(balloon: IntRect, inside: Int = paper): Raster {
        val w = cropRect.width; val h = cropRect.height
        val argb = IntArray(w * h) { i ->
            val dx = (cropRect.left + i % w - balloon.centerX) / (balloon.width / 2f)
            val dy = (cropRect.top + i / w - balloon.centerY) / (balloon.height / 2f)
            val d = dx * dx + dy * dy
            when { d < 0.85f -> inside; d < 1f -> ink; else -> art }
        }
        return Raster(w, h, argb)
    }

    private fun inOval(balloon: IntRect, x: Float, y: Float, limit: Float = 0.86f): Boolean {
        val dx = (x - balloon.centerX) / (balloon.width / 2f); val dy = (y - balloon.centerY) / (balloon.height / 2f)
        return dx * dx + dy * dy < limit
    }

    @Test fun everyTextBoxStaysOnThePaperAndWideAndTallOnesAreOffered() {
        val balloon = IntRect(140, 80, 440, 380)
        val s = RegionRenderer.balloonShapeOf(crop(balloon), cropRect, balloon)
        assertNotNull(s); s!!
        assertTrue(s.boxes.size >= 4, "shapes: " + s.boxes.size)
        assertTrue(s.boxes.any { it.width > it.height * 1.5f } && s.boxes.any { it.height > it.width * 1.5f })
        for (r in s.boxes) for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            assertTrue(inOval(balloon, x.toFloat(), y.toFloat()), "corner $x,$y of $r")
        }
    }

    @Test fun theOutlineFollowsTheBalloonNotItsBox() {
        val balloon = IntRect(140, 80, 440, 380)
        val s = RegionRenderer.balloonShapeOf(crop(balloon), cropRect, balloon)!!
        assertTrue(s.outline.size >= 16)
        // Every outline point lies on the balloon's line (the paper's edge, pushed out to the ink), never in a box corner.
        for ((x, y) in s.outline) {
            val dx = (x - balloon.centerX) / (balloon.width / 2f); val dy = (y - balloon.centerY) / (balloon.height / 2f)
            assertTrue(abs(dx * dx + dy * dy - 0.88f) < 0.12f, "point $x,$y")
        }
    }

    @Test fun aBalloonWithoutPaperOrOutsideTheCropKeepsItsBox() {
        val balloon = IntRect(140, 80, 440, 380)
        assertNull(RegionRenderer.balloonShapeOf(crop(balloon, inside = art), cropRect, balloon))
        assertNull(RegionRenderer.balloonShapeOf(crop(balloon), cropRect, IntRect(600, 600, 700, 700)))
    }
}
