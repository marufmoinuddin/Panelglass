package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PatchTest {
    @Test
    fun aRenderedPatchIsEncodedOnlyWhenItsBytesAreRead() {
        val pixels = Any()
        var encodes = 0
        val patch = Patch(1f, 2f, 3f, 4f, image = pixels) { encodes++; byteArrayOf(7, 8) }
        // Shown first: nothing encoded yet.
        assertSame(pixels, patch.image)
        assertEquals(0, encodes)
        // The cache reads the bytes, then the dump: one encode.
        assertArrayEquals(byteArrayOf(7, 8), patch.webp)
        assertArrayEquals(byteArrayOf(7, 8), patch.webp)
        assertEquals(1, encodes)
    }

    @Test
    fun aCachedPatchHasBytesOnly() {
        val patch = Patch(1f, 2f, 3f, 4f, byteArrayOf(1))
        assertNull(patch.image)
        assertArrayEquals(byteArrayOf(1), patch.webp)
    }
}
