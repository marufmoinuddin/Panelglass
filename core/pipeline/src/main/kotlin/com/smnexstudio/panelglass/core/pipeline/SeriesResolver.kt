package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.TranslationNotes
import java.security.MessageDigest

/** What the engine is told about a series beyond the page: its glossary and the composed notes. */
class SeriesResolver(private val glossary: GlossaryRepository) {
    /**
     * [cfg] with its series' glossary merged in and its notes composed ([TranslationNotes]: the characters' pronouns,
     * the series' notes, then Settings' notes already in [cfg]). Read once per image, before the cache lookup, whose
     * key carries them ([digest]).
     */
    suspend fun resolve(cfg: TranslateConfig): TranslateConfig {
        if (cfg.seriesKey.isEmpty()) return cfg.copy(notes = TranslationNotes.compose(emptyList(), "", cfg.notes))
        val entries = glossary.entries(cfg.seriesKey)
        return cfg.copy(
            glossary = cfg.glossary + entries.filter { it.source.isNotBlank() }.associate { it.source to it.target },
            notes = TranslationNotes.compose(entries, glossary.notes(cfg.seriesKey), cfg.notes),
        )
    }

    companion object {
        /** A short digest of what the engine is told beyond the page, for the patch-cache key; "" when nothing. */
        fun digest(cfg: TranslateConfig): String {
            if (cfg.glossary.isEmpty() && cfg.notes.isEmpty() && cfg.prompt.isEmpty()) return ""
            val text = cfg.glossary.entries.sortedBy { it.key }.joinToString("\n") { it.key + "\u0000" + it.value } +
                "\u0001" + cfg.notes + "\u0002" + cfg.prompt
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
