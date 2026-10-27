package com.smnexstudio.panelglass.feature.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric (JUnit 4 via vintage): the glossary lives in Room. */
@RunWith(RobolectricTestRunner::class)
class SeriesMemoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
    private val repo = GlossaryRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = MutableStateFlow("")
    private val memory = SeriesMemory(repo, scope, key, keyNow = { key.value })

    @After fun tearDown() { scope.cancel(); db.close() }

    @Test
    fun nothingIsSavedBeforeTheSeriesIsKnown() = runBlocking {
        memory.saveTerm(GlossaryEntry(seriesKey = "", source = "ミナ", target = "Mina"))
        memory.saveNotes("Mina is a knight.")
        assertTrue(memory.takeWrites().isEmpty())
    }

    @Test
    fun editsGoToTheCurrentSeriesTrimmedAndAreHandedOverOnce() = runBlocking {
        key.value = "comics.example/series"
        memory.saveTerm(GlossaryEntry(seriesKey = "ignored", source = " ミナ ", target = " Mina "))
        memory.saveTerm(GlossaryEntry(seriesKey = "ignored", source = "先輩", target = " "))
        memory.saveNotes("Mina is a knight.")
        val writes = memory.takeWrites()
        assertEquals(2, writes.size)
        writes.joinAll()
        assertTrue(memory.takeWrites().isEmpty())

        val saved = repo.entries("comics.example/series")
        assertEquals(listOf("ミナ" to "Mina"), saved.map { it.source to it.target })
        assertEquals("Mina is a knight.", repo.notes("comics.example/series"))
        assertTrue(repo.entries("ignored").isEmpty())
    }
}
