package com.smnexstudio.panelglass.core.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.model.ComputeBackend
import com.smnexstudio.panelglass.core.model.QwenBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the models run: the new setting, and what a phone that only ever chose Qwen's backend gets.
 */
class ComputeBackendSettingTest {
    private val ds = MemoryDataStore()
    private val settings = testSettings(ds)

    /** Writes a raw preference, as an older version would have left it. */
    private suspend fun put(key: String, value: String) {
        ds.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey(key), value) } }
    }

    @Test
    fun withoutAnythingStoredTheChoiceIsAutomatic() = runBlocking {
        assertEquals(ComputeBackend.AUTO, settings.current().computeBackend)
    }

    @Test
    fun theOldQwenOnlyChoiceBecomesTheNewOne() = runBlocking {
        put("qwenBackend", QwenBackend.CPU.name)
        assertEquals(ComputeBackend.CPU, settings.current().computeBackend)
    }

    @Test
    fun everyOldQwenChoiceHasAMatchingNewOne() = runBlocking {
        for (old in QwenBackend.entries) {
            put("qwenBackend", old.name)
            val expected = when (old) {
                QwenBackend.AUTO -> ComputeBackend.AUTO
                QwenBackend.GPU -> ComputeBackend.GPU
                QwenBackend.CPU -> ComputeBackend.CPU
            }
            assertEquals(old.name, expected, settings.current().computeBackend)
        }
    }

    @Test
    fun theNewChoiceWinsOverTheOldOne() = runBlocking {
        put("qwenBackend", QwenBackend.CPU.name)
        settings.setComputeBackend(ComputeBackend.GPU)
        assertEquals(ComputeBackend.GPU, settings.current().computeBackend)
    }

    @Test
    fun theChoiceIsSavedAndReadBack() = runBlocking {
        for (v in ComputeBackend.entries) {
            settings.setComputeBackend(v)
            assertEquals(v.name, v, settings.current().computeBackend)
        }
    }
}
