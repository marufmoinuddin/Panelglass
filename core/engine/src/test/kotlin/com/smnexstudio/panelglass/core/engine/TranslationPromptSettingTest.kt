package com.smnexstudio.panelglass.core.engine

import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslationPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reader's own instructions for every translation are saved, read back and capped.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationPromptSettingTest {
    private val ds = MemoryDataStore()
    private val repo = SettingsRepository(ds)

    @Test
    fun thePromptIsSavedAndReadBack() = runBlocking {
        repo.setTranslationPrompt("Keep the tone formal.")
        assertEquals("Keep the tone formal.", repo.current().translationPrompt)
    }

    @Test
    fun anEmptyPromptIsSavedAsNone() = runBlocking {
        repo.setTranslationPrompt("Keep the tone formal.")
        repo.setTranslationPrompt("")
        assertEquals("", repo.current().translationPrompt)
    }

    @Test
    fun thePromptIsTrimmedAndCapped() = runBlocking {
        repo.setTranslationPrompt("   spaced   ")
        assertEquals("spaced", repo.current().translationPrompt)
        repo.setTranslationPrompt("z".repeat(5000))
        assertEquals(TranslationPrompt.MAX_CHARS, repo.current().translationPrompt.length)
    }

    @Test
    fun thePromptSurvivesANewRepositoryOverTheSameStore() = runBlocking {
        repo.setTranslationPrompt("Keep the tone formal.")
        val again = SettingsRepository(ds)
        assertEquals("Keep the tone formal.", again.current().translationPrompt)
    }

    @Test
    fun thePromptDoesNotDisturbTheOtherSettings() = runBlocking {
        repo.setDefaultSourceLang(Lang.KO)
        repo.setTranslationPrompt("Keep the tone formal.")
        val s = repo.current()
        assertEquals(Lang.KO, s.defaultSourceLang)
        assertEquals("Keep the tone formal.", s.translationPrompt)
    }

    @Test
    fun thePromptIsStoredUnderItsOwnKey() = runBlocking {
        repo.setTranslationPrompt("Keep the tone formal.")
        val key = stringPreferencesKey("translationPrompt")
        assertEquals("Keep the tone formal.", ds.data.first()[key])
        assertTrue(repo.current().translationPrompt.isNotEmpty())
    }
}
