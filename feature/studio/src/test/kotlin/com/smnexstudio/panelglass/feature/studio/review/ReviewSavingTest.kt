package com.smnexstudio.panelglass.feature.studio.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.smnexstudio.panelglass.feature.studio.MemoryDataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.repo.UserFontRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.engine.EngineResolution
import com.smnexstudio.panelglass.core.engine.EngineResolver
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import com.smnexstudio.panelglass.core.ocr.LamaStore
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import com.smnexstudio.panelglass.core.render.StudioRenderer
import com.smnexstudio.panelglass.feature.studio.fonts.UserFontStore
import com.smnexstudio.panelglass.feature.studio.translate.LoadedPage
import com.smnexstudio.panelglass.feature.studio.translate.PageLoader
import com.smnexstudio.panelglass.feature.studio.translate.StudioStages
import com.smnexstudio.panelglass.feature.studio.translate.StudioTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The review editor never loses typing: a translation is saved once typing pauses ([ReviewViewModel.SAVE_DELAY_MS]),
 * at once on a page turn, and when the screen is left (its own scope outlives the view model). Each pause is one undo
 * step. The main dispatcher runs on virtual time, so the pause is exact; the database is real (Room in memory).
 * Robolectric with native graphics (JUnit 4 via vintage): the editor opens the page's pixels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewSavingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: PanelglassDb
    private lateinit var root: File
    private lateinit var repo: StudioRepository
    private lateinit var settings: SettingsRepository
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).build()
        root = File(context.filesDir, "review-test").apply { deleteRecursively(); mkdirs() }
        repo = StudioRepository(db, StudioFiles(File(root, "studio")))
        settings = SettingsRepository(MemoryDataStore())
    }

    @After fun tearDown() {
        store.clear()
        db.close()
        scope.cancel()
        root.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** A chapter of [pages] pages, each a real PNG with one bubble whose translation is "old <n>". */
    private suspend fun chapter(pages: Int): Long {
        val list = (1..pages).map {
            val (rel, dir) = repo.files.newPageDir()
            File(dir, "original.png").outputStream().use { o ->
                Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, o)
            }
            StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 120, height = 180)
        }
        val ch = repo.import(ImportTarget.NewManga(Manga(title = "Invented", srcLang = Lang.JA, tgtLang = Lang.EN), Chapter(mangaId = 0)), list)
        repo.pageList(ch).forEachIndexed { i, p ->
            repo.addBubble(Bubble(pageId = p.id, order = 0, kind = RegionKind.ENCLOSED,
                polygon = listOf(Pt(10f, 10f), Pt(100f, 10f), Pt(100f, 60f), Pt(10f, 60f)), sourceText = "src ${i + 1}", translatedText = "old ${i + 1}"))
        }
        return ch
    }

    private fun vm(chapterId: Long): ReviewViewModel {
        val downloads = SystemDownloads(context)
        val translator = StudioTranslator(repo, NoStages, NoLoader, NoEngines, settings)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewViewModel(
                context, repo, translator, StudioRenderer(), ReviewMemory(), settings, GlossaryRepository(db),
                LamaInpainter(LamaStore(context, downloads)), UserFontStore(context, UserFontRepository(db)),
                SavedStateHandle(mapOf("chapterId" to chapterId.toString())),
            ) as T
        }
        return ViewModelProvider(store, factory)[ReviewViewModel::class.java]
    }

    private suspend fun bubbleOf(vm: ReviewViewModel): Bubble = withTimeout(10_000) { vm.bubbles.first { it.isNotEmpty() } }.single()

    /** Bubble [b]'s translation as stored. */
    private suspend fun stored(b: Bubble): Bubble = repo.bubbleList(b.pageId).single { it.id == b.id }

    /** Waits for the write to land (it runs on Room's thread): a condition, not a sleep. */
    private suspend fun storedBecomes(b: Bubble, text: String) = withTimeout(10_000) {
        while (stored(b).translatedText != text) delay(10)
    }

    @Test fun typingIsSavedWhenItPausesNotBefore() = runBlocking<Unit> {
        val vm = vm(chapter(1))
        val b = bubbleOf(vm)
        vm.editTranslation(b.id, "new 1")
        assertEquals("the field shows the draft at once", "new 1", vm.draftTranslation.value[b.id])
        scheduler.advanceTimeBy(ReviewViewModel.SAVE_DELAY_MS - 1); scheduler.runCurrent()
        assertEquals("not saved while typing", "old 1", stored(b).translatedText)
        scheduler.advanceTimeBy(2); scheduler.runCurrent()
        storedBecomes(b, "new 1")
    }

    /** Fast typing is one write and one undo step, with the last text. */
    @Test fun fastTypingIsOneSaveAndOneUndoStep() = runBlocking<Unit> {
        val vm = vm(chapter(1))
        val b = bubbleOf(vm)
        for (t in listOf("n", "ne", "new")) { vm.editTranslation(b.id, t); scheduler.advanceTimeBy(100); scheduler.runCurrent() }
        scheduler.advanceTimeBy(ReviewViewModel.SAVE_DELAY_MS); scheduler.runCurrent()
        storedBecomes(b, "new")
        withTimeout(5_000) { vm.canUndo.first { it } }
        vm.undo().join()
        storedBecomes(b, "old 1")
        withTimeout(5_000) { vm.canRedo.first { it } }
        vm.redo().join()
        storedBecomes(b, "new")
    }

    /** Turning the page does not wait for the pause: the edit is written at once. */
    @Test fun aPageTurnSavesPendingTypingAtOnce() = runBlocking<Unit> {
        val vm = vm(chapter(2))
        val b = bubbleOf(vm)
        vm.editTranslation(b.id, "typed then turned")
        vm.goToPage(1)
        storedBecomes(b, "typed then turned")
    }

    /** Leaving the screen (the view model cleared) still writes what was typed in the last 400 ms. */
    @Test fun leavingTheScreenSavesPendingTyping() = runBlocking<Unit> {
        val vm = vm(chapter(1))
        val b = bubbleOf(vm)
        vm.editSource(b.id, "edited source")
        vm.editTranslation(b.id, "typed then left")
        store.clear()
        storedBecomes(b, "typed then left")
        assertEquals("edited source", stored(b).sourceText)
    }

    @Test fun anEditThatChangesNothingIsNoUndoStep() = runBlocking<Unit> {
        val vm = vm(chapter(1))
        val b = bubbleOf(vm)
        vm.editTranslation(b.id, "old 1")
        scheduler.advanceTimeBy(ReviewViewModel.SAVE_DELAY_MS + 1); scheduler.runCurrent()
        assertTrue(!vm.canUndo.value)
        assertEquals("old 1", stored(b).translatedText)
    }

    // ---- fakes: nothing here translates or cleans ------------------------------------------------------------

    private object NoStages : StudioStages {
        override suspend fun detect(bitmap: Bitmap, cfg: TranslateConfig): List<TextRegion> = error("not used")
        override suspend fun translate(bitmap: Bitmap, regions: List<TextRegion>, cfg: TranslateConfig, prior: List<ContextPair>): TranslationPipeline.StudioTranslation = error("not used")
        override suspend fun read(bitmap: Bitmap, rect: IntRect, cfg: TranslateConfig, kind: RegionKind): TextRegion = error("not used")
        override fun clean(bitmap: Bitmap, bubbles: List<Bubble>, strokes: List<BrushStroke>): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    private object NoLoader : PageLoader {
        override fun load(file: File) = LoadedPage(Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888), 1f)
    }

    private object NoEngines : EngineResolver {
        override suspend fun resolveSelected() = EngineResolution.Failed(EngineFailure.Unavailable(EngineId.GOOGLE))
        override suspend fun resolve(id: EngineId) = EngineResolution.Failed(EngineFailure.Unavailable(id))
    }
}
