package com.smnexstudio.panelglass.feature.studio.fonts

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.repo.UserFontRepository
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.feature.studio.TestViewModels
import com.smnexstudio.panelglass.feature.studio.importer.StudioImporterTest.PickedFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * My fonts' view model over the real font store and database: a picked font is added under its family name, a second
 * style joins it, a copy or a file that is no font is refused, and rename, role and delete are kept. The font files are
 * the bundled Comic Neue (OFL, see NOTICE), served by a fake documents provider. Robolectric with native graphics: the
 * store loads each font to see which scripts it draws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var store: UserFontStore
    private lateinit var picked: File
    private val models = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        store = UserFontStore(context, UserFontRepository(db))
        store.dir.deleteRecursively()
        picked = File(context.filesDir, "picked-fonts").apply { deleteRecursively(); mkdirs() }
        PickedFiles.files.clear()
        Robolectric.setupContentProvider(PickedFiles::class.java, PickedFiles.AUTHORITY)
    }

    @After fun tearDown() {
        models.clear()
        db.close()
        store.dir.deleteRecursively()
        picked.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** A bundled font file of core:render (the tests run from the module's directory). */
    private fun bundled(name: String): ByteArray {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(dir, "core/render/src/main/assets/fonts/comic_neue/$name").readBytes()
    }

    private fun serve(name: String, bytes: ByteArray): Uri {
        PickedFiles.files[name] = File(picked, name).apply { writeBytes(bytes) }
        return Uri.parse("content://${PickedFiles.AUTHORITY}/$name")
    }

    private suspend fun imported(vm: FontsViewModel, vararg uris: Uri): FontImportResult {
        vm.import(uris.toList())
        // Loading each font natively is slow on a busy machine: a wide window, still a condition.
        val result = withTimeout(30_000) { vm.result.first { it != null } }!!
        withTimeout(5_000) { vm.importing.first { !it } }
        return result
    }

    @Test fun aPickedFontIsAddedUnderItsFamilyName() = runBlocking<Unit> {
        val vm = models.keep(FontsViewModel(store))
        val result = imported(vm, serve("ComicNeue-Bold.ttf", bundled("ComicNeue-Bold.ttf")))
        val font = result.added.single()
        assertEquals("Comic Neue", font.family)
        assertTrue("it draws Latin", "latin" in font.scripts)
        assertTrue(font.files.values.all { File(store.dir, it).isFile })
        assertEquals(listOf(font.id), withTimeout(5_000) { vm.fonts.first { it.isNotEmpty() } }.map { it.id })
    }

    @Test fun aSecondStyleJoinsItsFamily() = runBlocking<Unit> {
        val vm = models.keep(FontsViewModel(store))
        imported(vm, serve("ComicNeue-Bold.ttf", bundled("ComicNeue-Bold.ttf")), serve("ComicNeue-Italic.ttf", bundled("ComicNeue-Italic.ttf")))
        // Each file is saved as it is read: wait for the second.
        val fonts = withTimeout(5_000) { vm.fonts.first { list -> list.sumOf { it.files.size } == 2 } }
        assertEquals("one family", 1, fonts.size)
        assertEquals(setOf("bold", "italic"), fonts.single().files.keys)
    }

    @Test fun theSameFileTwiceAndAFileThatIsNoFontAreRefused() = runBlocking<Unit> {
        val vm = models.keep(FontsViewModel(store))
        imported(vm, serve("ComicNeue-Bold.ttf", bundled("ComicNeue-Bold.ttf")))
        vm.result.value = null
        val again = imported(vm, serve("copy.ttf", bundled("ComicNeue-Bold.ttf")), serve("notes.ttf", "not a font at all".toByteArray()))
        assertTrue(again.added.isEmpty())
        assertEquals(listOf("copy.ttf" to FontRefusal.ALREADY_ADDED, "notes.ttf" to FontRefusal.NOT_A_FONT), again.refused)
        assertEquals("nothing left behind but the first font", 1, store.dir.listFiles()!!.size)
    }

    @Test fun anEmptyPickDoesNothing() {
        val vm = models.keep(FontsViewModel(store))
        vm.import(emptyList())
        assertFalse(vm.importing.value)
        assertEquals(null, vm.result.value)
    }

    @Test fun aFontIsRenamedGivenARoleAndDeletedWithItsFiles() = runBlocking<Unit> {
        val vm = models.keep(FontsViewModel(store))
        val font = imported(vm, serve("ComicNeue-Bold.ttf", bundled("ComicNeue-Bold.ttf"))).added.single()
        assertEquals(0, vm.usage(font))
        vm.rename(font, "  My lettering  ").join()
        vm.setRole(withTimeout(5_000) { vm.fonts.first { it.single().displayName == "My lettering" } }.single(), FontRole.SFX).join()
        val renamed = withTimeout(5_000) { vm.fonts.first { it.single().role == FontRole.SFX } }.single()
        assertEquals("My lettering", renamed.displayName)
        vm.delete(renamed).join()
        withTimeout(5_000) { vm.fonts.first { it.isEmpty() } }
        // Windows cannot delete a file the process has loaded as a font (the store loads each to probe it); Android can.
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) assertTrue(store.dir.listFiles().orEmpty().isEmpty())
    }

    /** A blank name keeps the old one. */
    @Test fun aBlankNameIsIgnored() = runBlocking<Unit> {
        val vm = models.keep(FontsViewModel(store))
        val font = imported(vm, serve("ComicNeue-Bold.ttf", bundled("ComicNeue-Bold.ttf"))).added.single()
        vm.rename(font, "   ").join()
        assertEquals(font.displayName, store.fonts.first().single().displayName)
    }
}
