package com.smnexstudio.panelglass.feature.studio.export

import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.StudioPage
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Writes `page:<the page file's text>:<language>`; [failOn] names a page it cannot read. */
internal class FakeComposer(private val read: (StudioPage) -> String) : PageComposer {
    var failOn: String? = null
    var bytesPerPage = 10L
    override suspend fun write(page: StudioPage, tgt: Lang, out: OutputStream, quality: PageQuality): Boolean {
        val tag = read(page)
        if (tag == failOn) return false
        out.write("page:$tag:${tgt.name}".toByteArray())
        return true
    }
    override fun estimateBytes(page: StudioPage, quality: PageQuality) = bytesPerPage
}

/** Destinations by URI (null: Downloads), each a plain directory; "permission" = being in [dirs]. */
internal class DirTargets : ExportTargets {
    val dirs = HashMap<String?, File>()
    var failWrites: IOException? = null
    var free: Long? = null
    override fun adopt(uri: String) = dirs.containsKey(uri)
    override fun open(uri: String?): ExportTarget? = dirs[uri]?.let { DirTarget(it) }
    override fun label(uri: String?) = dirs[uri]?.name
    override fun freeBytes(uri: String?) = free

    inner class DirTarget(base: File) : ExportTarget {
        override val root: String = base.path
        override fun list(dir: String) = File(dir).listFiles().orEmpty().map { ExportEntry(it.path, it.name, it.isDirectory) }
        override fun folder(dir: String, name: String): String = File(dir, name).apply { mkdirs() }.path
        override suspend fun write(dir: String, name: String, mime: String, body: suspend (OutputStream) -> Unit) {
            failWrites?.let { throw it }
            val f = File(dir, name)
            val existed = f.exists()
            try {
                f.outputStream().use { body(it) }
            } catch (e: Throwable) {
                if (!existed) f.delete()
                throw e
            }
        }
        override fun delete(entry: ExportEntry) { File(entry.id).delete() }
    }
}
