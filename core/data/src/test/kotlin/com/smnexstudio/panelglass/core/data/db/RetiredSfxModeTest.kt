package com.smnexstudio.panelglass.core.data.db

import com.smnexstudio.panelglass.core.model.SfxEditMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Studio effects saved as Gloss or Overlay, removed on 2026-10-18, open as Replace with the rest of the edit kept. */
class RetiredSfxModeTest {
    private val converters = StudioConverters()

    @Test
    fun aSavedGlossOpensAsReplaceAndKeepsItsEdits() {
        val saved = """{"mode":"GLOSS","text":"BOOM","transform":{"cx":10.0,"cy":20.0,"rotationDeg":-75.0}}"""
        val e = converters.textToSfx(saved)!!
        assertEquals(SfxEditMode.REPLACE, e.mode)
        assertEquals("BOOM", e.text)
        assertEquals(-75f, e.transform.rotationDeg)
    }

    @Test
    fun overlayTheOldDefaultWasNeverWrittenAndOpensAsReplace() {
        // Written with encodeDefaults = false: no "mode" key at all.
        assertEquals(SfxEditMode.REPLACE, converters.textToSfx("""{"transform":{"cx":1.0,"cy":2.0}}""")!!.mode)
        // Should one have been written explicitly, it maps too.
        assertEquals(SfxEditMode.REPLACE, converters.textToSfx("""{"mode" : "OVERLAY","transform":{"cx":1.0,"cy":2.0}}""")!!.mode)
    }

    @Test
    fun keepAndReplaceAreReadAsSaved() {
        assertEquals(SfxEditMode.KEEP, converters.textToSfx("""{"mode":"KEEP","transform":{"cx":1.0,"cy":2.0}}""")!!.mode)
        assertEquals(SfxEditMode.REPLACE, converters.textToSfx("""{"mode":"REPLACE","transform":{"cx":1.0,"cy":2.0}}""")!!.mode)
    }
}
