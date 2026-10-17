package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SeriesResolverTest {
    private val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.GEMINI)

    @Test
    fun nothingBeyondThePageHasNoDigest() {
        assertEquals("", SeriesResolver.digest(cfg))
    }

    @Test
    fun theDigestFollowsTermsAndNotesButNotTheirOrder() {
        val a = cfg.copy(glossary = linkedMapOf("ミナ" to "Mina", "レン" to "Ren"), notes = "Keep honorifics.")
        val b = cfg.copy(glossary = linkedMapOf("レン" to "Ren", "ミナ" to "Mina"), notes = "Keep honorifics.")
        assertEquals(16, SeriesResolver.digest(a).length)
        assertEquals(SeriesResolver.digest(a), SeriesResolver.digest(b))
        assertNotEquals(SeriesResolver.digest(a), SeriesResolver.digest(a.copy(notes = "Drop honorifics.")))
        assertNotEquals(SeriesResolver.digest(a), SeriesResolver.digest(a.copy(glossary = mapOf("ミナ" to "Meena", "レン" to "Ren"))))
    }
}
