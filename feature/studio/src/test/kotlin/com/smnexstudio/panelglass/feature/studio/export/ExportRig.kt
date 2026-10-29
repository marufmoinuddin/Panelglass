package com.smnexstudio.panelglass.feature.studio.export

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.feature.studio.MemoryDataStore
import com.smnexstudio.panelglass.feature.studio.TestViewModels
import java.io.File

/**
 * The export flow's real parts over plain directories: an in-memory database, the [StudioExporter] with [DirTargets]
 * (Downloads is [downloads]) and a [FakeComposer], and a manga "Stardust" built by [manga].
 */
internal class ExportRig(context: Context) {
    val root = File(context.filesDir, "export-rig").apply { deleteRecursively(); mkdirs() }
    val downloads = File(root, "Download/Panelglass").apply { mkdirs() }
    val db: PanelglassDb = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
    val repo = StudioRepository(db, StudioFiles(File(root, "studio")))
    val settings = SettingsRepository(MemoryDataStore())
    val targets = DirTargets().apply { dirs[null] = downloads }
    val composer = FakeComposer { repo.files.file(it.file).readText() }
    val exporter = StudioExporter(repo, targets, composer, settings)
    private val models = TestViewModels()

    /**
     * A manga with one chapter per entry of [translated] (numbered from 1, two pages each); a chapter's pages are marked
     * translated when its entry is true. Returns the manga's id and the chapters' ids in order.
     */
    suspend fun manga(vararg translated: Boolean): Pair<Long, List<Long>> {
        var mangaId = 0L
        val ids = translated.mapIndexed { i, done ->
            val pages = (1..2).map { n ->
                val (rel, dir) = repo.files.newPageDir()
                File(dir, "original.png").writeText("c${i + 1}p$n")
                StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 100, height = 150)
            }
            val chapter = Chapter(mangaId = 0, number = (i + 1).toFloat())
            val id = if (i == 0) {
                repo.import(ImportTarget.NewManga(Manga(title = "Stardust", srcLang = Lang.JA, tgtLang = Lang.EN), chapter), pages)
                    .also { mangaId = repo.getChapter(it)!!.mangaId }
            } else repo.import(ImportTarget.NewChapter(mangaId, chapter), pages)
            if (done) repo.pageList(id).forEach { repo.updatePage(it.copy(stages = setOf(StudioStage.TRANSLATED))) }
            id
        }
        return mangaId to ids
    }

    fun vm(mangaId: Long, chapter: Long? = null) = models.keep(ExportViewModel(
        repo, exporter, SavedStateHandle(listOfNotNull("id" to mangaId.toString(), chapter?.let { "chapter" to it.toString() }).toMap()),
    ))

    /** Clears the view models first: their scopes must not outlive the database. */
    fun close() {
        models.clear()
        db.close()
        root.deleteRecursively()
    }

    companion object {
        const val PICKED = "content://tree/picked"
    }
}
