package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslateConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionLanguagesTest {
    private val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.GOOGLE)
    private val url = "https://comics.example/series/1"

    @Test
    fun thePagesLanguageAppliesOnItsHostOnly() {
        val l = SessionLanguages()
        l.pageIs(url, Lang.KO)
        assertEquals(Lang.KO, l.apply(cfg, null, "https://comics.example/series/2").src)
        assertEquals(Lang.JA, l.apply(cfg, null, "https://other.example/a").src)
    }

    @Test
    fun aSitesSavedSourceWinsOverThePages() {
        val l = SessionLanguages()
        l.pageIs(url, Lang.KO)
        val site = Site(name = "S", url = "https://comics.example", sourceLang = Lang.JA)
        assertEquals(Lang.JA, l.apply(cfg, site, url).src)
    }

    @Test
    fun theLaunchIntentWinsOverEverything() {
        val l = SessionLanguages()
        assertFalse(l.sourceOverridden)
        l.pageIs(url, Lang.KO)
        l.override(Lang.ZH, Lang.HI)
        assertTrue(l.sourceOverridden)
        val out = l.apply(cfg, null, url)
        assertEquals(Lang.ZH, out.src)
        assertEquals(Lang.HI, out.tgt)
        // Only the target from the intent: the source still comes from the page.
        l.override(null, Lang.HI)
        assertEquals(Lang.KO, l.apply(cfg, null, url).src)
    }

    @Test
    fun mlKitTagsMapToComicSources() {
        assertEquals(Lang.JA, SessionLanguages.sourceFor("ja", Lang.KO))
        assertEquals(Lang.KO, SessionLanguages.sourceFor("ko", Lang.JA))
        assertEquals(Lang.ZH, SessionLanguages.sourceFor("zh-Latn", Lang.JA))
        // ML Kit cannot tell the Chinese scripts apart: a chosen one is kept.
        assertEquals(Lang.ZH_TW, SessionLanguages.sourceFor("zh", Lang.ZH_TW))
        assertNull(SessionLanguages.sourceFor("en", Lang.JA))
    }
}
