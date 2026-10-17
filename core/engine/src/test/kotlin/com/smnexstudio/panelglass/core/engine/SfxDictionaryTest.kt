package com.smnexstudio.panelglass.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SfxDictionaryTest {
    private val table = SfxDictionary.parse("""{"ガラガラ": "CLATTER", "ガツン": "WHAM", "ドカーン": "KABOOM"}""")

    private fun find(text: String): String? {
        val key = SfxDictionary.key(text)
        return table.exact[key] ?: table.loose[SfxDictionary.loose(key)]
    }

    @Test
    fun hiraganaFindsTheKatakanaEntry() {
        assertEquals("CLATTER", find("がらがら"))
        assertEquals("CLATTER", find("ガラガラ！"))
    }

    @Test
    fun anotherStopOrLengthMarkStillMatches() {
        assertEquals("WHAM", find("ガツーン"))
        assertEquals("WHAM", find("がつんっ"))
        assertEquals("KABOOM", find("ドカン"))
    }

    @Test
    fun otherWordsStillMiss() {
        assertEquals(null, find("ガタン"))
    }

    /** The bundled tables parse, and every entry has a rendering. */
    @Test
    fun bundledTablesAreWellFormed() {
        for (name in listOf("sfx_ja.json", "sfx_ko.json")) {
            val t = SfxDictionary.parse(File("src/main/assets/$name").readText())
            assertTrue(t.exact.size > 600, name)
            assertTrue(t.exact.values.none { it.isBlank() }, name)
        }
    }
}
