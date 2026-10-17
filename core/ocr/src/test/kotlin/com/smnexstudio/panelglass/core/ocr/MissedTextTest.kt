package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MissedTextTest {
    private fun map(w: Int, h: Int, vararg blobs: Pair<IntRect, Float>): FloatArray {
        val p = FloatArray(w * h)
        for ((r, v) in blobs) for (y in r.top until r.bottom) for (x in r.left until r.right) p[y * w + x] = v
        return p
    }

    @Test
    fun aConfidentBlobBecomesAGrownBox() {
        val found = DbPostProcess.boxes(map(100, 100, IntRect(40, 40, 60, 50) to 0.9f), 100, 100)
        assertEquals(1, found.size)
        val b = found[0].box
        // 20x10 core, grown by 20*10*1.5 / 60 = 5 px on every side: PaddleOCR's unclip.
        assertEquals(IntRect(35, 35, 65, 55), b)
        assertTrue(found[0].score > 0.85f)
    }

    @Test
    fun aFaintBlobIsNotText() {
        assertTrue(DbPostProcess.boxes(map(100, 100, IntRect(10, 10, 30, 30) to 0.45f), 100, 100).isEmpty())
    }

    /** 타 and 닥 come out as two boxes a stroke apart: one effect. Distant boxes stay apart. */
    @Test
    fun nearbyCharactersMergeIntoOneBlock() {
        val ta = TextBox(IntRect(100, 100, 200, 200), BoxKind.TEXT_FREE, 0.8f)
        val dak = TextBox(IntRect(220, 180, 320, 280), BoxKind.TEXT_FREE, 0.9f)
        val far = TextBox(IntRect(700, 700, 760, 760), BoxKind.TEXT_FREE, 0.7f)
        val merged = DbPostProcess.merge(listOf(ta, dak, far))
        assertEquals(2, merged.size)
        assertEquals(IntRect(100, 100, 320, 280), merged.first { it.bbox.left == 100 }.bbox)
        assertEquals(0.9f, merged.first { it.bbox.left == 100 }.confidence)
    }

    @Test
    fun onlyWhatTheComicDetectorMissedIsKept() {
        val balloonText = TextBox(IntRect(100, 100, 400, 200), BoxKind.TEXT_BUBBLE, 0.9f)
        val inBalloon = TextBox(IntRect(150, 120, 350, 180), BoxKind.TEXT_FREE, 0.9f)
        val sfx = TextBox(IntRect(500, 600, 700, 800), BoxKind.TEXT_FREE, 0.9f)
        val tinyArtText = TextBox(IntRect(50, 900, 60, 908), BoxKind.TEXT_FREE, 0.9f)
        val kept = MissedTextDetector.outside(listOf(inBalloon, sfx, tinyArtText), listOf(balloonText), 1220, 2000)
        assertEquals(listOf(sfx), kept)
    }
}
