package com.smnexstudio.panelglass

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What every start settles: nobody is switched off Qwen by the new default, and the first-start packs are fetched once. */
class StartupTasksTest {
    private val settings = SettingsRepository(object : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = state.updateAndGet { transform(it) }
    })
    private var fetches = 0

    private fun tasks(qwen: Boolean, online: Boolean = true) = StartupTasks(settings, { qwen }, { fetches++; online })

    @Test fun someoneWhoNeverChoseAndHasQwenStaysOnQwen() = runBlocking {
        tasks(qwen = true).run()
        assertEquals(EngineId.QWEN15_LOCAL, settings.current().engineId)
    }

    @Test fun anExplicitChoiceIsNeverChanged() = runBlocking {
        settings.setEngine(EngineId.GOOGLE)
        tasks(qwen = true).run()
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
    }

    @Test fun withoutQwenTheDefaultAppliesAndNothingIsWritten() = runBlocking {
        tasks(qwen = false).run()
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
        assertFalse(settings.hasEngineChoice())
    }

    /** Offline at first start: nothing is marked done, so the next start fetches again. */
    @Test fun thePacksAreFetchedUntilTheyAreAllThereOnce() = runBlocking {
        tasks(qwen = false, online = false).run()
        assertFalse(settings.automaticPacksDone())
        tasks(qwen = false, online = true).run()
        assertTrue(settings.automaticPacksDone())
        tasks(qwen = false, online = true).run()
        assertEquals(2, fetches, "a pack the user deleted later is not fetched again")
    }
}
