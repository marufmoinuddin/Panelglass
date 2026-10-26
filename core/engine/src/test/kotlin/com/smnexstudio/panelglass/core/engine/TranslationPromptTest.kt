package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslationPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's own instructions for every translation (Settings › Translation prompt): they must reach every AI
 * engine's prompt, and be kept out of the way when there are none.
 */
class TranslationPromptTest {
    private val items = listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "おはよう"))

    @Test
    fun thePromptReachesTheCloudEnginesPrompt() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN, prompt = "Keep the tone formal.")
        val p = LlmContract.systemPrompt(req)
        assertTrue(p.contains("Keep the tone formal."))
        // It is introduced as the reader's own instructions, so the model follows it.
        assertTrue(p.contains("instructions for every translation"))
    }

    @Test
    fun thePromptReachesTheOnDeviceModelsPrompt() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN, prompt = "Keep the tone formal.")
        assertTrue(LocalPrompt.system(req).contains("Keep the tone formal."))
        assertTrue(LocalPrompt.plain(items[0], req).system.contains("Keep the tone formal."))
    }

    @Test
    fun aSubsetRequestKeepsThePrompt() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN, prompt = "Keep the tone formal.")
        assertTrue(LlmContract.systemPrompt(req.withItems(items.take(1))).contains("Keep the tone formal."))
    }

    @Test
    fun anEmptyPromptIsNotInThePrompt() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN)
        val p = LlmContract.systemPrompt(req)
        assertFalse(p.contains("instructions for every translation"))
        assertFalse(LocalPrompt.system(req).contains("Reader's instructions"))
    }

    @Test
    fun thePromptIsCappedAndTrimmed() {
        val long = "x".repeat(TranslationPrompt.MAX_CHARS + 500)
        val req = TranslateRequest(items, Lang.JA, Lang.EN, prompt = long)
        val p = LlmContract.systemPrompt(req)
        // The capped text is in it; the overflow is not.
        assertTrue(p.contains("x".repeat(TranslationPrompt.MAX_CHARS)))
        assertFalse(p.contains("x".repeat(TranslationPrompt.MAX_CHARS + 1)))
    }

    @Test
    fun thePromptIsCappedInTheLocalPromptToo() {
        val long = "y".repeat(TranslationPrompt.MAX_CHARS + 500)
        val req = TranslateRequest(items, Lang.JA, Lang.EN, prompt = long)
        assertFalse(LocalPrompt.system(req).contains("y".repeat(TranslationPrompt.MAX_CHARS + 1)))
    }

    @Test
    fun cleanTrimsAndCaps() {
        assertEquals("hello", TranslationPrompt.clean("  hello  "))
        assertEquals("", TranslationPrompt.clean("   "))
        assertEquals(TranslationPrompt.MAX_CHARS, TranslationPrompt.clean("z".repeat(5000)).length)
    }

    @Test
    fun theSettingsPromptReachesTheConfig() {
        val s = Settings(translationPrompt = "Keep the tone formal.")
        assertEquals("Keep the tone formal.", s.resolve(null).prompt)
    }

    @Test
    fun thePromptAndTheNotesAreSeparate() {
        // The notes are about the series; the prompt is about every translation. Both are sent, in that order.
        val req = TranslateRequest(items, Lang.JA, Lang.EN, notes = "A note.", prompt = "An instruction.")
        val p = LlmContract.systemPrompt(req)
        assertTrue(p.contains("A note."))
        assertTrue(p.contains("An instruction."))
        assertTrue(p.indexOf("A note.") < p.indexOf("An instruction."))
    }

    @Test
    fun theConfigCarriesThePrompt() {
        val cfg = TranslateConfig(Lang.JA, Lang.EN, com.smnexstudio.panelglass.core.model.EngineId.GOOGLE, prompt = "An instruction.")
        assertEquals("An instruction.", cfg.prompt)
    }
}
