package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadStateTest {
    private val box = IntRect(0, 0, 100, 40)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theStateDecidesNotTheText() {
        // Lettering that happens to spell the filler is still text to translate.
        val read = TextRegion(bbox = box, kind = RegionKind.FREE, text = TextLine.UNREAD)
        assertFalse(read.unread)
        assertFalse(read.copy(text = TextLine.UNREADABLE).unreadable)
        assertTrue(TextRegion.unread(box, RegionKind.ENCLOSED).unread)
        assertTrue(TextLine.unreadable(box).state == ReadState.UNREADABLE)
    }

    @Test
    fun aRegionSavedBeforeTheStateExistedIsUpgraded() {
        // A Studio row as written before ReadState: the filler text alone marked it.
        val unread = json.decodeFromString<TextRegion>(
            """{"bbox":{"left":0,"top":0,"right":100,"bottom":40},"kind":"ENCLOSED","text":"[unread]","lines":[{"bbox":{"left":0,"top":0,"right":100,"bottom":40},"text":"[unread]"}]}""",
        )
        assertEquals(ReadState.READ, unread.state)
        val up = unread.upgraded()
        assertTrue(up.unread)
        assertEquals(listOf(ReadState.UNREAD), up.lines.map { it.state })

        val unreadable = json.decodeFromString<TextRegion>(
            """{"bbox":{"left":0,"top":0,"right":100,"bottom":40},"kind":"SFX","text":"[unreadable]"}""",
        )
        assertTrue(unreadable.upgraded().unreadable)
    }

    @Test
    fun aRegionWithAStateRoundTripsUnchanged() {
        val region = TextRegion.unread(box, RegionKind.ENCLOSED)
        val back = json.decodeFromString<TextRegion>(json.encodeToString(TextRegion.serializer(), region))
        assertEquals(region, back)
        assertEquals(region, back.upgraded())
        val plain = TextRegion(bbox = box, kind = RegionKind.FREE, text = "hello")
        assertEquals(plain, plain.upgraded())
    }
}
