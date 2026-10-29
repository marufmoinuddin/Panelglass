package com.smnexstudio.panelglass.feature.library

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Sites list: an address is saved https only, named from its host when no name is given, with Settings' ad-block
 * default; a language pair equal to Settings' is stored as "no override". Real Room in memory; unconfined main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var sites: SiteRepository
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).build()
        sites = SiteRepository(db)
        settings = SettingsRepository(object : DataStore<Preferences> {
            private val state = MutableStateFlow<Preferences>(emptyPreferences())
            override val data: Flow<Preferences> get() = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = state.updateAndGet { transform(it) }
        })
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm() = LibraryViewModel(sites, settings, HistoryRepository(db))
    private suspend fun saved() = withTimeout(5_000) { sites.sites.first { it.isNotEmpty() } }

    @Test fun anHttpAddressIsSavedAsHttpsAndNamedFromItsHost() = runBlocking<Unit> {
        vm().add("  ", "http://comics.example.org/series/7").join()
        val s = saved().single()
        assertEquals("https://comics.example.org/series/7", s.url)
        assertEquals("Comics", s.name)
    }

    /** `javascript:`, `file:` and the like are never saved as a site. */
    @Test fun anAddressThatIsNotWebIsNotSaved() = runBlocking<Unit> {
        val vm = vm()
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x.html", "intent://x", "not a url at all")) vm.add("x", bad).join()
        assertTrue(sites.sites.first().isEmpty())
    }

    @Test fun aNewSiteTakesTheAdBlockDefault() = runBlocking<Unit> {
        settings.setAdBlockDefault(false)
        val vm = vm()
        withTimeout(5_000) { vm.settings.first { !it.adBlockDefault } }
        vm.add("Invented", "https://comics.example.org/").join()
        assertEquals(false, saved().single().adBlockEnabled)
    }

    /** A pair equal to Settings' is no override, so the site follows later changes to Settings. */
    @Test fun openingWithTheDefaultPairStoresNoOverride() = runBlocking<Unit> {
        val vm = vm()
        vm.add("Invented", "https://comics.example.org/", sourceLang = Lang.KO).join()
        val site = saved().single()
        var opened = 0
        vm.openWith(site, Lang.JA, Lang.EN) { opened++ }.join()
        val after = sites.sites.first().single()
        assertNull(after.sourceLang)
        assertNull(after.targetLang)
        assertEquals(1, opened)
        vm.openWith(after, Lang.KO, Lang.FR) { opened++ }.join()
        val pinned = sites.sites.first().single()
        assertEquals(Lang.KO, pinned.sourceLang)
        assertEquals(Lang.FR, pinned.targetLang)
    }

    @Test fun pinningTogglesAndDeletingRemoves() = runBlocking<Unit> {
        val vm = vm()
        vm.add("Invented", "https://comics.example.org/").join()
        vm.togglePin(saved().single()).join()
        assertTrue(sites.sites.first().single().pinned)
        vm.delete(sites.sites.first().single()).join()
        assertTrue(sites.sites.first().isEmpty())
    }
}
