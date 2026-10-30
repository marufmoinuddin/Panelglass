package com.smnexstudio.panelglass.feature.studio.export

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The export screen end to end over the real exporter ([ExportRig]: plain directories, a fake page composer): tick
 * chapters, pick format and quality, confirm, and see what was written. Compose on Robolectric (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportScreenTest {
    private val compose = createComposeRule()
    /**
     * Closes the database only after the compose rule has disposed the screen (rules wrap `@After`): a screen still
     * collecting from a closed database would fail a later test.
     */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() { override fun after() = rig.close() }).around(compose)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rig: ExportRig
    private var backs = 0

    @Before fun setUp() { rig = ExportRig(context) }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun p(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)

    /** The node's own click: a dialog still animating in under Robolectric would miss an injected touch. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    /** Waits for [done], letting real time pass between checks (Room and the exporter work on their own threads). */
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

    private fun screen(vararg translated: Boolean): List<Long> {
        val (m, ids) = runBlocking { rig.manga(*translated) }
        val vm = rig.vm(m)
        compose.setContent { ExportScreen(onBack = { backs++ }, vm = vm) }
        eventually { shown(s(UiR.string.studio_export_which)) && shown("Stardust") }
        return ids
    }

    /** NEXT on the list (and, while a step is open, in that step's pop-up: the last one). */
    private fun next() = compose.onAllNodesWithText(s(UiR.string.studio_next).uppercase()).onLast()

    /** From the chapter list through "what to export" ([clean] or translated) to the format step. */
    private fun toFormat(clean: Boolean = false) {
        next().tap()
        eventually { shown(s(UiR.string.studio_export_what)) }
        if (clean) compose.onNodeWithText(s(UiR.string.studio_export_clean)).tap()
        next().tap()
        eventually { shown(s(UiR.string.studio_export_as)) }
    }

    @Test fun anUntranslatedChapterIsMarkedAndNotTicked() {
        screen(false)
        compose.onNodeWithText(s(UiR.string.studio_export_untranslated)).assertExists()
        next().assertIsNotEnabled()
    }

    @Test fun theWholeFlowWritesAZipOfSmallerPages() {
        screen(true)
        toFormat()
        compose.onNodeWithText(s(UiR.string.studio_export_format_zip)).tap()
        compose.onNodeWithText(s(UiR.string.studio_export_smaller)).tap()
        next().tap()
        eventually { shown(s(UiR.string.studio_export_save_to)) && shown("Ch 001.zip") }
        compose.onNodeWithText(s(UiR.string.studio_export).uppercase()).tap()
        eventually { shown(p(UiR.plurals.studio_export_done_title, 1)) }
        assertTrue(File(rig.downloads, "Stardust/Ch 001.zip").isFile)
        compose.onNodeWithText(s(UiR.string.studio_done)).tap()
        assertEquals(1, backs)
    }

    /** Back goes one step at a time: format, then what to export, then the list. */
    @Test fun backInAStepReturnsStepByStepToTheList() {
        screen(true)
        toFormat()
        compose.onNodeWithText(s(UiR.string.studio_back)).tap()
        eventually { !shown(s(UiR.string.studio_export_as)) && shown(s(UiR.string.studio_export_what)) }
        compose.onNodeWithText(s(UiR.string.studio_back)).tap()
        eventually { !shown(s(UiR.string.studio_export_what)) }
        assertEquals(0, backs)
    }

    /** Export clean: the confirm step says so and names the clean files; the pages are written without lettering. */
    @Test fun aCleanExportWritesTextFreePages() {
        screen(true)
        toFormat(clean = true)
        compose.onNodeWithText(s(UiR.string.studio_export_format_cbz)).tap()
        next().tap()
        eventually { shown(s(UiR.string.studio_export_save_to)) && shown("Ch 001 (Clean).cbz") }
        compose.onNodeWithText(s(UiR.string.studio_export_row_content)).assertExists()
        compose.onNodeWithText(s(UiR.string.studio_export).uppercase()).tap()
        eventually { shown(p(UiR.plurals.studio_export_done_title, 1)) }
        java.util.zip.ZipFile(File(rig.downloads, "Stardust/Ch 001 (Clean).cbz")).use { z ->
            assertEquals("clean:c1p1", z.getInputStream(z.getEntry("001.png")).readBytes().decodeToString())
        }
    }

    /** The confirm step names what will be replaced. */
    @Test fun theConfirmStepWarnsAboutFilesItReplaces() {
        screen(true)
        File(rig.downloads, "Stardust").mkdirs()
        File(rig.downloads, "Stardust/Ch 001.cbz").writeText("old")
        toFormat()
        compose.onNodeWithText(s(UiR.string.studio_export_format_cbz)).tap()
        next().tap()
        eventually { shown(p(UiR.plurals.studio_export_overwrite, 1)) }
    }

    @Test fun selectAllTicksEveryChapterWithPages() {
        screen(true, false)
        compose.onNodeWithText(s(UiR.string.studio_select_all)).tap()
        eventually { shown(s(UiR.string.studio_select_none)) }
        eventually { shown(p(UiR.plurals.studio_chapters, 2)) }
    }
}
