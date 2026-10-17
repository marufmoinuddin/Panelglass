package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslateConfig

/**
 * The reader session's languages on top of the resolved config: the launch intent's (`src`/`tgt` extras; this session
 * only, never saved to the site or Settings) first, else the language the page itself is written in, kept for the
 * host it was found on so the next chapter starts with it. A site's saved source always wins over the page's. A Korean
 * webtoon read with the default Japanese recognizer came out as nonsense.
 */
class SessionLanguages {
    private var override: Pair<Lang?, Lang?> = null to null
    private var pageSrc: Pair<String, Lang>? = null

    /** The launch intent set the source: the page's language is not looked for. */
    val sourceOverridden: Boolean get() = override.first != null

    fun override(src: Lang?, tgt: Lang?) { override = src to tgt }

    /** [cfg] for a page at [url] on [site] with this session's languages. */
    fun apply(cfg: TranslateConfig, site: Site?, url: String): TranslateConfig {
        val detected = pageSrc?.takeIf { site?.sourceLang == null && it.first == Site.hostOf(url) }?.second
        return cfg.copy(src = override.first ?: detected ?: cfg.src, tgt = override.second ?: cfg.tgt)
    }

    /** The page at [url] is written in [lang]; [apply] uses it for that host from now on. */
    fun pageIs(url: String, lang: Lang) { pageSrc = Site.hostOf(url) to lang }

    companion object {
        /**
         * The comic source language for an ML Kit language [tag], or null when it is none of them. ML Kit does not
         * tell the two Chinese scripts apart: a Chinese [current] source is kept.
         */
        fun sourceFor(tag: String, current: Lang): Lang? = when (tag.substringBefore('-')) {
            "ja" -> Lang.JA
            "ko" -> Lang.KO
            "zh" -> if (current == Lang.ZH || current == Lang.ZH_TW) current else Lang.ZH
            else -> null
        }
    }
}
