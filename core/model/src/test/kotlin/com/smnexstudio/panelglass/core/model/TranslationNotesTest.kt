package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranslationNotesTest {
    private fun term(source: String, target: String, pronoun: Pronoun? = null) =
        GlossaryEntry(seriesKey = "s", source = source, target = target, pronoun = pronoun)

    @Test
    fun charactersComeFirstThenSeriesThenGlobalNotes() {
        val notes = TranslationNotes.compose(
            listOf(term("ユキ", "Yuki", Pronoun.SHE), term("先輩", "senpai"), term("レン", "Ren", Pronoun.HE)),
            series = "Ren and Yuki are siblings.",
            global = "Keep honorifics.",
        )
        assertEquals(
            "Characters:\n- Yuki (ユキ): female, she/her\n- Ren (レン): male, he/him\nRen and Yuki are siblings.\nKeep honorifics.",
            notes,
        )
    }

    @Test
    fun aTermWithoutPronounIsNotACharacter() {
        assertEquals("", TranslationNotes.compose(listOf(term("先輩", "senpai")), "", ""))
    }

    @Test
    fun aNameKeptAsWrittenIsNotRepeated() {
        assertEquals("Characters:\n- Kai: they/them, no gendered forms", TranslationNotes.compose(listOf(term("Kai", "Kai", Pronoun.THEY)), "", " "))
    }

    @Test
    fun eachNoteIsCapped() {
        val long = "x".repeat(TranslationNotes.MAX_CHARS + 50)
        assertEquals(TranslationNotes.MAX_CHARS * 2 + 1, TranslationNotes.compose(emptyList(), long, long).length)
    }
}
