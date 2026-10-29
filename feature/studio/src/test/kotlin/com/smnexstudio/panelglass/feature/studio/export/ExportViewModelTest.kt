package com.smnexstudio.panelglass.feature.studio.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The export screen's view model over the real exporter ([ExportRig]: plain directories, no MediaStore): which
 * chapters start ticked, the three steps, and an export with the format and quality chosen. Robolectric (JUnit 4 via
 * vintage); the main dispatcher is unconfined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rig: ExportRig
    /** Keeps the view model's chapter list subscribed, as the screen does. */
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rig = ExportRig(context)
    }

    @After fun tearDown() {
        watchers.cancel()
        rig.close()
        Dispatchers.resetMain()
    }

    private suspend fun loaded(vm: ExportViewModel): ExportViewModel {
        watchers.launch { vm.chapters.collect {} }
        withTimeout(5_000) { vm.chapters.first { it != null } }
        withTimeout(5_000) { vm.selected.first { it != null } }
        return vm
    }

    @Test fun chaptersWithTranslatedPagesStartTicked() = runBlocking<Unit> {
        val (m, ids) = rig.manga(true, false, true)
        val vm = loaded(rig.vm(m))
        assertEquals(setOf(ids[0], ids[2]), vm.selected.value)
        assertEquals(listOf(2, 0, 2), vm.chapters.value!!.map { it.translated })
    }

    @Test fun theChapterTheScreenWasOpenedForIsTheOnlyOneTicked() = runBlocking<Unit> {
        val (m, ids) = rig.manga(true, true)
        val vm = loaded(rig.vm(m, chapter = ids[1]))
        assertEquals(setOf(ids[1]), vm.selected.value)
    }

    @Test fun selectAllThenNoneAndSingleToggles() = runBlocking<Unit> {
        val (m, ids) = rig.manga(true, false)
        val vm = loaded(rig.vm(m))
        vm.toggleAll()
        assertEquals(ids.toSet(), vm.selected.value)
        vm.toggleAll()
        assertEquals(emptySet<Long>(), vm.selected.value)
        vm.toggle(ids[1])
        assertEquals(setOf(ids[1]), vm.selected.value)
    }

    @Test fun nextNeedsATickedChapterAndBackStepsOneAtATime() = runBlocking<Unit> {
        val (m, _) = rig.manga(false)
        val vm = loaded(rig.vm(m))
        vm.next()
        assertEquals("nothing ticked: stays on the list", ExportStep.CHOOSE, vm.step.value)
        vm.toggleAll()
        vm.next()
        assertEquals(ExportStep.FORMAT, vm.step.value)
        vm.toConfirm()
        assertEquals(ExportStep.CONFIRM, vm.step.value)
        val plan = withTimeout(5_000) { vm.plan.first { it != null } }!!
        assertEquals("Images by default: a folder per chapter", listOf("Ch 001/"), plan.files)
        assertEquals(2, plan.pages)
        vm.back()
        assertEquals(ExportStep.FORMAT, vm.step.value)
        vm.back()
        assertEquals(ExportStep.CHOOSE, vm.step.value)
    }

    /** The format and quality picked are written and remembered for the next export. */
    @Test fun anExportWritesTheChosenFormatAndRemembersIt() = runBlocking<Unit> {
        val (m, _) = rig.manga(true)
        val vm = loaded(rig.vm(m))
        vm.next()
        vm.setFormat(ExportFormat.ZIP)
        vm.setQuality(PageQuality.JPEG)
        vm.toConfirm()
        vm.export().join()
        val run = withTimeout(5_000) { vm.run.first { it != null && !it.active } }!!
        assertEquals(ExportPhase.DONE, run.phase)
        assertTrue(File(rig.downloads, "Stardust/Ch 001.zip").isFile)
        assertEquals(ExportStep.CHOOSE, vm.step.value)
        assertEquals(ExportFormat.ZIP, rig.exporter.format())
        assertEquals(PageQuality.JPEG, rig.exporter.quality())
    }

    /** A finished export is shown until dismissed; a later visit starts fresh. */
    @Test fun aFinishedExportIsNotShownOnTheNextVisit() = runBlocking<Unit> {
        val (m, _) = rig.manga(true)
        val vm = loaded(rig.vm(m))
        vm.export().join()
        withTimeout(5_000) { vm.run.first { it != null && !it.active } }
        assertNull(loaded(rig.vm(m)).run.value)
    }

    @Test fun exportAgainGoesBackToTheList() = runBlocking<Unit> {
        val (m, _) = rig.manga(true)
        val vm = loaded(rig.vm(m))
        vm.export().join()
        withTimeout(5_000) { vm.run.first { it != null && !it.active } }
        vm.again()
        withTimeout(5_000) { vm.run.first { it == null } }
        assertEquals(ExportStep.CHOOSE, vm.step.value)
    }

    /** A folder whose permission cannot be kept is refused and says so; Downloads puts things back. */
    @Test fun aFolderThatCannotBeKeptIsRefusedAndDownloadsRestores() = runBlocking<Unit> {
        val (m, _) = rig.manga(true)
        val vm = loaded(rig.vm(m))
        vm.picked(Uri.parse("content://tree/elsewhere"))
        withTimeout(5_000) { vm.refused.first { it } }
        assertFalse(vm.custom.value)
        vm.useDownloads().join()
        assertFalse(vm.refused.value)
        assertFalse(vm.custom.value)
    }

    @Test fun aPickedFolderIsUsedAndNamed() = runBlocking<Unit> {
        val picked = File(rig.root, "Comics").apply { mkdirs() }
        rig.targets.dirs[ExportRig.PICKED] = picked
        val (m, _) = rig.manga(true)
        val vm = loaded(rig.vm(m))
        vm.picked(Uri.parse(ExportRig.PICKED))
        withTimeout(5_000) { vm.custom.first { it } }
        assertEquals("Comics", vm.folder.value)
        vm.setFormat(ExportFormat.CBZ)
        vm.export().join()
        withTimeout(5_000) { vm.run.first { it != null && !it.active } }
        assertTrue(File(picked, "Stardust/Ch 001.cbz").isFile)
        assertNotNull("the folder is kept for this manga", rig.exporter.folderFor(m))
    }
}
