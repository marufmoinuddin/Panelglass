package com.smnexstudio.panelglass.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A user-added comic site. Per-site overrides are nullable columns; null inherits the global default. */
@Serializable
@Entity(tableName = "sites")
data class Site(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val iconUrl: String? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val sourceLang: Lang? = null,
    val targetLang: Lang? = null,
    val engineId: EngineId? = null,
    val adBlockEnabled: Boolean = true,
    val autoTranslate: Boolean = false,
) {
    val host: String get() = hostOf(url)

    companion object {
        fun hostOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
                .removePrefix("www.")
        }

        /**
         * The saved site that owns [url]. Several sites can share a host (two series on one reader), so the host alone
         * does not decide: the site whose saved path is the longest prefix of the page's path wins (`/manga/a/` owns
         * `/manga/a/chapter-39/`). A page under none of them goes to the most general site on that host.
         */
        fun bestMatch(sites: List<Site>, url: String): Site? {
            val host = hostOf(url)
            if (host.isEmpty()) return null
            val onHost = sites.filter { host == it.host || host.endsWith("." + it.host) }
            if (onHost.isEmpty()) return null
            val path = dirOf(url)
            val owners = onHost.filter { path.startsWith(dirOf(it.url)) }
            return if (owners.isNotEmpty()) {
                owners.maxWith(compareBy<Site>({ dirOf(it.url).length }, { it.host.length }, { it.pinned }, { -it.id }))
            } else {
                onHost.maxWith(compareBy<Site>({ it.host.length }, { -dirOf(it.url).length }, { it.pinned }, { -it.id }))
            }
        }

        /** The URL's path without query or fragment, always ending in `/`, so prefixes match whole segments. */
        internal fun dirOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            val p = "/" + noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
            return if (p.endsWith("/")) p else "$p/"
        }

        /**
         * The glossary and notes scope for a reader page: the saved [site] that owns it, by its own host and path (a
         * site saved as one series, `/manga/a/`, keeps its names apart from `/manga/b/`), else [seriesKeyOf].
         */
        fun seriesKeyFor(site: Site?, url: String): String {
            if (site == null) return seriesKeyOf(url)
            return site.host + dirOf(site.url).trimEnd('/')
        }

        /** host + first path segment; used to scope glossaries and reading context. */
        fun seriesKeyOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            val host = hostOf(url)
            val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
            val first = path.split('/').firstOrNull { it.isNotBlank() } ?: ""
            return if (first.isEmpty()) host else "$host/$first"
        }
    }
}

@Entity(tableName = "sfx_cache")
data class SfxCacheEntry(
    @PrimaryKey val key: String,
    val translated: String,
) {
    companion object {
        fun keyOf(normalized: String, src: Lang, tgt: Lang) = "$normalized|${src.code}|${tgt.code}"
    }
}

/**
 * A name or term always rendered the same way in one series. A character's entry may carry the [pronoun] the source
 * language leaves out (Japanese and Korean drop the subject), which the AI engines are told.
 */
@Entity(tableName = "glossary")
data class GlossaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesKey: String,
    val source: String,
    val target: String,
    val pronoun: Pronoun? = null,
) {
    /** This entry with both sides trimmed and cut to [MAX_CHARS]. */
    fun capped(): GlossaryEntry = copy(source = source.trim().take(MAX_CHARS), target = target.trim().take(MAX_CHARS))

    companion object {
        /**
         * Longest source or rendering, in characters. Every term goes into every prompt of its series: a name or a
         * phrase is well under this, and a pasted paragraph would crowd out the on-device models' 4096-token budget
         * and raise a cloud engine's bill on every call.
         */
        const val MAX_CHARS = 60
    }
}

/** How a character is referred to. [prompt] is the engines' wording (English, as every prompt is). */
enum class Pronoun(val prompt: String) {
    HE("male, he/him"),
    SHE("female, she/her"),
    THEY("they/them, no gendered forms"),
}

/** The user's free-text notes for one series (who is who, tone), sent to the AI engines with the glossary. */
@Entity(tableName = "series_notes")
data class SeriesNotes(
    @PrimaryKey val seriesKey: String,
    val notes: String,
)

/**
 * What the AI engines are told beyond the glossary: the characters' pronouns, then the series' notes, then the
 * notes from Settings that apply everywhere. Each part is capped at [MAX_CHARS]: the on-device models share a
 * 4096-token cache between prompt and reply.
 */
object TranslationNotes {
    const val MAX_CHARS = 500

    /**
     * Settings' notes until the user writes their own (docs/TRANSLATION_QUALITY.md › Translation notes). Notes the user
     * cleared stay empty: only a value never saved reads as this.
     */
    const val DEFAULT = "Natural, casual English dialogue. Keep honorifics (-san, -sama, senpai).\n" +
        "Never add a pronoun or object that isn't in the original; if the subject is unclear, rephrase without one.\n" +
        "Short lines that fit a speech bubble."

    fun compose(entries: List<GlossaryEntry>, series: String, global: String): String {
        val characters = entries.filter { it.pronoun != null && it.source.isNotBlank() }.joinToString("\n") { e ->
            val name = e.target.trim().ifEmpty { e.source.trim() }
            val alias = if (e.target.isNotBlank() && e.target.trim() != e.source.trim()) " (${e.source.trim()})" else ""
            "- $name$alias: ${e.pronoun!!.prompt}"
        }
        return listOf(
            if (characters.isEmpty()) "" else "Characters:\n$characters",
            series.trim().take(MAX_CHARS),
            global.trim().take(MAX_CHARS),
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }
}

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long,
)
