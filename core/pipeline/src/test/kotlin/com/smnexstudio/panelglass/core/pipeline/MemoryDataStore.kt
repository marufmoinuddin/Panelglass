package com.smnexstudio.panelglass.core.pipeline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * An in-memory preferences DataStore for tests (as core:engine's). A file-backed one cannot rename its temp file over
 * the old one on Windows, so a second write in a test fails there.
 */
class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        state.updateAndGet { transform(it) }
}
