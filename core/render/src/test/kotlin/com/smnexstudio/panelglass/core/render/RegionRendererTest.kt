package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.FreeTextMode
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import com.smnexstudio.panelglass.core.ocr.raster.RasterPixelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's patches ([RegionRenderer.render] / [RegionRenderer.eraseOnly]) on synthetic pages: where a patch sits
 * on the page, when nothing is drawn, and what an erase leaves behind. Robolectric native canvas (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegionRendererTest {
    private val renderer = RegionRenderer(TextEraser(), PatchEncoder())
    private val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.GOOGLE)
    private val w = 600
    private val h = 800
    private val grey = 0xFFAAAAAA.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private val balloon = IntRect(150, 200, 450, 500)
    private val textBox = IntRect(200, 300, 400, 380)

    /** Grey art, a white rectangular balloon, and black "lettering" blocks in its text box. */
    private fun page(): Raster {
        val px = IntArray(w * h) { grey }
        fun fill(r: IntRect, c: Int) { for (y in r.top until r.bottom) for (x in r.left until r.right) px[y * w + x] = c }
        fill(balloon, white)
        for (k in 0 until 6) for (row in 0 until 2) fill(IntRect(208 + k * 32, 308 + row * 36, 228 + k * 32, 332 + row * 36), black)
        return Raster(w, h, px)
    }

    private fun region(kind: RegionKind = RegionKind.ENCLOSED, box: IntRect = textBox, container: IntRect? = balloon, state: ReadState = ReadState.READ) =
        TextRegion(bbox = box, kind = kind, text = "kou otsu", lines = listOf(TextLine(box, "kou otsu", fontSizePx = 24f)), container = container, state = state)

    private fun covers(p: Patch, r: IntRect): Boolean =
        p.xPct * w / 100f <= r.left + 1 && p.yPct * h / 100f <= r.top + 1 &&
            (p.xPct + p.wPct) * w / 100f >= r.right - 1 && (p.yPct + p.hPct) * h / 100f >= r.bottom - 1

    private fun inPage(p: Patch) = p.xPct >= 0f && p.yPct >= 0f && p.xPct + p.wPct <= 100.01f && p.yPct + p.hPct <= 100.01f

    @Test fun balloonTextBecomesAPatchOverTheWholeBalloon() {
        val p = renderer.render(RasterPixelSource(page()), region(), "Hello there", cfg, null)
        assertNotNull(p); p!!
        assertTrue(inPage(p))
        assertTrue("the erase covers the balloon, not only the text box", covers(p, balloon))
        val bmp = p.image as Bitmap
        assertEquals(Math.round(p.wPct * w / 100f), bmp.width)
        assertEquals(Math.round(p.hPct * h / 100f), bmp.height)
    }

    /** The art outside the balloon is not repainted: the patch is transparent there (or the art's own colour). */
    @Test fun theArtAroundTheBalloonIsKept() {
        val p = renderer.render(RasterPixelSource(page()), region(), "Hello there", cfg, null)!!
        val bmp = p.image as Bitmap
        val left = Math.round(p.xPct * w / 100f); val top = Math.round(p.yPct * h / 100f)
        val around = balloon.inflate(3)
        var outside = 0; var changed = 0; var first = ""
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            if (around.contains(x + left, y + top)) continue
            outside++
            // Transparent (the page shows through the patch) or the art's own colour: never painted over.
            val px = bmp.getPixel(x, y)
            if (px ushr 24 != 0 && px != grey) { if (changed++ == 0) first = "(${x + left},${y + top})=%08x".format(px) }
        }
        assertTrue("the patch reaches past the balloon (crop padding)", outside > 0)
        assertEquals("art pixels repainted outside the balloon, first $first of $outside", 0, changed)
    }

    @Test fun nothingIsDrawnForAnEmptyTranslationOrWhereTheSettingsSaySo() {
        val src = RasterPixelSource(page())
        assertNull(renderer.render(src, region(), "   ", cfg, null))
        val free = region(kind = RegionKind.FREE, container = null)
        assertNull("free text off", renderer.render(src, free, "Hello", cfg.copy(freeTextMode = FreeTextMode.OFF), null))
        val sfx = region(kind = RegionKind.SFX, container = null)
        assertNull("sound effects kept", renderer.render(src, sfx, "BAM", cfg.copy(sfxMode = SfxMode.SKIP), null))
        assertNotNull(renderer.render(src, sfx, "BAM", cfg.copy(sfxMode = SfxMode.REPLACE), null))
    }

    /** Overlay mode covers free text with a panel instead of repainting the art. */
    @Test fun freeTextInOverlayModeGetsAPanelOverItsFootprint() {
        val free = region(kind = RegionKind.FREE, container = null)
        val p = renderer.render(RasterPixelSource(page()), free, "Hello", cfg.copy(freeTextMode = FreeTextMode.OVERLAY), null)
        assertNotNull(p); p!!
        assertTrue(inPage(p))
        assertTrue(covers(p, textBox))
    }

    /** A sound effect nothing could read, with Replace on: erased to its background, nothing drawn in its place. */
    @Test fun anUnreadableEffectIsErasedToItsBackground() {
        val effect = IntRect(200, 300, 400, 380)
        val raster = page()
        val p = renderer.eraseOnly(RasterPixelSource(raster), region(kind = RegionKind.SFX, box = effect, container = null, state = ReadState.UNREADABLE), cfg)
        assertNotNull(p); p!!
        val bmp = p.image as Bitmap
        val left = Math.round(p.xPct * w / 100f); val top = Math.round(p.yPct * h / 100f)
        // Every lettering block is gone: the pixel at each block's centre is the balloon's white now.
        for (k in 0 until 6) for (row in 0 until 2) {
            val x = 218 + k * 32 - left; val y = 320 + row * 36 - top
            if (x in 0 until bmp.width && y in 0 until bmp.height && bmp.getPixel(x, y) != 0) assertEquals("block $k,$row", white, bmp.getPixel(x, y))
        }
    }

    /** Nothing to erase: no patch rather than a blank box. */
    @Test fun anEraseThatChangesNothingIsNoPatch() {
        val flat = Raster(w, h, IntArray(w * h) { white })
        assertNull(renderer.eraseOnly(RasterPixelSource(flat), region(kind = RegionKind.SFX, container = null, state = ReadState.UNREADABLE), cfg))
    }
}
