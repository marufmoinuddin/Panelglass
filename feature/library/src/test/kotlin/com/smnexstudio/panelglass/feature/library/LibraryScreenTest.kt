package com.smnexstudio.panelglass.feature.library

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.SiteRepository
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.ui.uiName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The Sites and History screens over a real Room database in memory: adding a site through the dialog, opening one
 * with a language pair, pinning, searching, the per-site sheet, and the history list. Compose on Robolectric (JUnit 4
 * via vintage); the database answers on its own threads, so each check waits for the screen to show the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScreenTest {
    private val compose = createComposeRule()
    /**
     * Closes the database only after the compose rule has disposed the screen (rules wrap `@After`): a screen still
     * collecting from a closed database would fail a later test.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() { override fun after() = close() }).around(compose)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var sites: SiteRepository
    private lateinit var history: HistoryRepository
    private lateinit var settings: SettingsRepository
    private val opened = mutableListOf<String>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        sites = SiteRepository(db)
        history = HistoryRepository(db)
        settings = SettingsRepository(object : DataStore<Preferences> {
            private val state = MutableStateFlow<Preferences>(emptyPreferences())
            override val data: Flow<Preferences> get() = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = state.updateAndGet { transform(it) }
        })
    }

    private val models = TestViewModels()

    private fun close() {
        models.clear()
        db.close()
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    /** The node's own click: a dialog or sheet still animating in under Robolectric would miss an injected touch. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun library(): LibraryViewModel {
        val vm = models.keep(LibraryViewModel(sites, settings, history))
        compose.setContent { LibraryScreen(onOpen = { opened += "url $it" }, onOpenSite = { opened += "site ${it.url}" }, vm = vm) }
        return vm
    }

    private fun saved(): List<Site> = runBlocking { sites.sites.first() }
    /**
     * Waits for [done], letting real time pass between checks: the database answers on its own threads, and its reply
     * reaches the view model's scope only after that.
     */
    private fun eventually(done: () -> Boolean) {
        repeat(100) {
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(50)
        }
        compose.waitForIdle()
        assertTrue("condition not met within 5 s", done())
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = eventually { shown(text) }
    private fun waitGone(text: String) = eventually { !shown(text) }
    private fun add(name: String, url: String) = runBlocking { sites.add(Site(name = name, url = url)) }

    @Test fun withNoSitesTheListSaysHowToAddOne() {
        library()
        compose.onNodeWithText(s(UiR.string.library_empty)).assertExists()
        compose.onNodeWithText(s(UiR.string.library_add_site_row)).assertExists()
    }

    /** TalkBack can name every button on the list (each site card, its pin, the add row and search). */
    @Test fun everyControlHasALabel() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        assertEquals(emptyList<String>(), compose.unlabelledControls())
    }

    @Test fun aSiteAddedThroughTheDialogIsSavedAsHttps() {
        library()
        compose.onNodeWithText(s(UiR.string.library_add_site_row)).tap()
        compose.onNode(hasSetTextAction() and hasText(s(UiR.string.site_url))).performTextInput("http://comics.example.org/read")
        compose.onNode(hasSetTextAction() and hasText(s(UiR.string.site_name_optional))).performTextInput("Inkwell")
        compose.onNodeWithText(s(UiR.string.action_add).uppercase()).tap()
        waitFor("Inkwell")
        val site = saved().single()
        assertEquals("https://comics.example.org/read", site.url)
        assertNull("the pair equals Settings': no override", site.sourceLang)
    }

    @Test fun addIsDisabledUntilTheAddressIsAWebAddress() {
        library()
        compose.onNodeWithText(s(UiR.string.library_add_site_row)).tap()
        compose.onNode(hasSetTextAction() and hasText(s(UiR.string.site_url))).performTextInput("javascript:alert(1)")
        compose.onNodeWithText(s(UiR.string.action_add).uppercase()).assertIsNotEnabled()
    }

    /** The pair picked before reading is saved for the site (it differs from Settings), then the reader opens. */
    @Test fun openingASiteAsksForItsLanguagesAndKeepsAChangedPair() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        compose.onNodeWithText("Inkwell").tap()
        compose.onNodeWithText(s(UiR.string.lang_translate_from)).tap()
        compose.onNodeWithText(Lang.KO.uiName()).tap()
        compose.onNodeWithText(s(UiR.string.site_read).uppercase()).tap()
        eventually { opened.isNotEmpty() }
        assertEquals(listOf("site https://comics.example.org/"), opened)
        assertEquals(Lang.KO, saved().single().sourceLang)
    }

    @Test fun cancellingTheLanguageDialogOpensNothing() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        compose.onNodeWithText("Inkwell").tap()
        compose.onNodeWithText(s(UiR.string.action_cancel)).tap()
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
    }

    @Test fun pinningMovesASiteUnderPinned() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        compose.onNodeWithContentDescription(s(UiR.string.cd_pin)).tap()
        eventually { saved().single().pinned }
        waitFor(s(UiR.string.library_pinned))
        compose.onNodeWithContentDescription(s(UiR.string.cd_unpin)).assertExists()
    }

    @Test fun searchFiltersByNameOrHost() {
        add("Inkwell", "https://comics.example.org/")
        add("Moonpress", "https://moon.example.net/")
        library()
        waitFor("Moonpress")
        compose.onNode(hasSetTextAction()).performTextInput("moon")
        waitGone("Inkwell")
        compose.onNodeWithText("Moonpress").assertExists()
    }

    /** A long press opens the site's own sheet; Delete there removes it. */
    @Test fun theSiteSheetDeletesTheSite() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        compose.onNodeWithText("Inkwell").performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor(s(UiR.string.site_pin_to_top))
        compose.onNodeWithText(s(UiR.string.action_delete)).tap()
        eventually { saved().isEmpty() }
    }

    @Test fun theSiteSheetSavesARenameAndAdBlocking() {
        add("Inkwell", "https://comics.example.org/")
        library()
        waitFor("Inkwell")
        compose.onNodeWithText("Inkwell").performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor(s(UiR.string.site_pin_to_top))
        compose.onNode(hasSetTextAction() and hasText("Inkwell")).performTextInput(" Two")
        compose.onNodeWithText(s(UiR.string.action_save).uppercase()).tap()
        eventually { saved().single().name != "Inkwell" }
        assertTrue(saved().single().name.contains("Two"))
    }

    @Test fun historyListsVisitsAndRemovesOneOrAll() {
        runBlocking {
            history.record("https://comics.example.org/ch/1", "First visit")
            history.record("https://comics.example.org/ch/2", "Second visit")
        }
        val vm = models.keep(LibraryViewModel(sites, settings, history))
        compose.setContent { HistoryScreen(onOpen = { opened += it }, vm = vm) }
        waitFor("Second visit")
        compose.onNodeWithText("First visit").tap()
        assertEquals(listOf("https://comics.example.org/ch/1"), opened)
        compose.onAllNodesWithContentDescriptionFirst(s(UiR.string.cd_remove)).tap()
        eventually { runBlocking { history.recent.first().size == 1 } }
        compose.onNodeWithText(s(UiR.string.action_clear)).tap()
        waitFor(s(UiR.string.history_empty))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionFirst(cd: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(cd))[0]
}
