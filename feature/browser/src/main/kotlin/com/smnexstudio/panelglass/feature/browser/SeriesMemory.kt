package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The series' names, pronouns and notes (reader ⋮ › Glossary & pronouns) for the page's series scope
 * ([Site.seriesKeyFor]); "" before the config is resolved, and then nothing is shown or saved. Edits are collected
 * until the sheet closes ([takeWrites]) so the screen is translated again once, after they have landed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesMemory(
    private val glossary: GlossaryRepository,
    private val scope: CoroutineScope,
    seriesKey: Flow<String>,
    /** The key now, read when an edit is saved (the flow may not have delivered it yet). */
    private val keyNow: () -> String,
) {
    private val key: Flow<String> = seriesKey.distinctUntilChanged()

    val terms: StateFlow<List<GlossaryEntry>> = key
        .flatMapLatest { k -> if (k.isEmpty()) flowOf(emptyList()) else glossary.observe(k) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<String> = key
        .flatMapLatest { k -> if (k.isEmpty()) flowOf(null) else glossary.observeNotes(k) }
        .map { it.orEmpty() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), "")

    private val writes = ArrayList<Job>()

    fun saveTerm(entry: GlossaryEntry) {
        val k = keyNow().takeIf { it.isNotEmpty() } ?: return
        val source = entry.source.trim(); val target = entry.target.trim()
        if (source.isEmpty() || target.isEmpty()) return
        writes += scope.launch { glossary.upsert(entry.copy(seriesKey = k, source = source, target = target)) }
    }

    fun deleteTerm(entry: GlossaryEntry) {
        writes += scope.launch { glossary.delete(entry) }
    }

    fun saveNotes(text: String) {
        val k = keyNow().takeIf { it.isNotEmpty() } ?: return
        if (text.trim() == notes.value.trim()) return
        writes += scope.launch { glossary.setNotes(k, text) }
    }

    /** The writes made since the last call; empty when nothing the engine is told changed. */
    fun takeWrites(): List<Job> = writes.toList().also { writes.clear() }
}
