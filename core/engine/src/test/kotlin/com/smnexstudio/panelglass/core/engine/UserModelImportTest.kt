package com.smnexstudio.panelglass.core.engine

import android.net.Uri
import com.smnexstudio.panelglass.core.engine.local.HfCatalog
import com.smnexstudio.panelglass.core.engine.local.UserModelLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.BaseCursor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Importing a `.litertlm` file the user picked in the file manager.
 *
 * Robolectric (JUnit 4 via vintage): the library needs a Context and a content resolver.
 */
@RunWith(RobolectricTestRunner::class)
class UserModelImportTest {
    private val ds = MemoryDataStore()
    private val settings = testSettings(ds)

    private fun library(ctx: android.content.Context) = UserModelLibrary(
        ctx, settings, testModelStores(ctx), testLocalEngines(ctx), testDownloads(ctx),
        HfCatalog(testSupport()),
    )

    /** A content Uri that hands back [bytes] under [name], and reports [size] as its size. */
    private fun picked(
        ctx: android.content.Context,
        name: String,
        bytes: ByteArray,
        size: Long = bytes.size.toLong(),
        stream: () -> InputStream = { ByteArrayInputStream(bytes) },
    ): Uri {
        val uri = Uri.parse("content://com.android.providers.downloads.document/document/1")
        val cursor = object : BaseCursor() {
            private var position = -1
            override fun getCount() = 1
            override fun getPosition() = position
            override fun moveToFirst() = moveToPosition(0)
            override fun moveToPosition(p: Int): Boolean {
                position = p
                return p in 0 until getCount()
            }
            override fun getColumnNames() = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            override fun getColumnName(i: Int) =
                if (i == 0) android.provider.OpenableColumns.DISPLAY_NAME else android.provider.OpenableColumns.SIZE
            override fun getColumnIndex(name: String) = when (name) {
                android.provider.OpenableColumns.DISPLAY_NAME -> 0
                android.provider.OpenableColumns.SIZE -> 1
                else -> -1
            }
            override fun getString(i: Int) = if (i == 0) name else null
            override fun getLong(i: Int) = if (i == 1) size else 0L
            override fun isNull(i: Int) = false
            override fun close() = Unit
        }
        shadowOf(ctx.contentResolver).setCursor(uri, cursor)
        shadowOf(ctx.contentResolver).registerInputStreamSupplier(uri) { stream() }
        return uri
    }

    /** [n] bytes of arbitrary content. */
    private fun payload(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun aFileWithSpacesAndBracketsIsImported() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = picked(ctx, "my model (1).litertlm", payload(2 * 1024 * 1024))
        val r = lib.importFile(uri, "", 4096) { _, _ -> }
        assertTrue(r.isSuccess)
        val m = r.getOrThrow()
        // The label is the file's own name without the extension.
        assertEquals("my model (1)", m.label)
        // The file is stored under a generated name, never the picked one.
        assertTrue(m.fileName.startsWith("user-"))
        assertTrue(m.fileName.endsWith(".litertlm"))
        assertFalse(m.fileName.contains(" "))
        // It is in the library.
        assertEquals(listOf(m.id), settings.userModels.first().map { it.id })
        assertEquals("file", m.source)
    }

    @Test
    fun theExtensionIsMatchedWithoutRegardToCase() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = picked(ctx, "MODEL.LITERTLM", payload(2 * 1024 * 1024))
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isSuccess)
        assertEquals("MODEL", r.getOrThrow().label)
    }

    @Test
    fun aNameWithoutTheExtensionIsRefused() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = picked(ctx, "model.txt", payload(2 * 1024 * 1024))
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isFailure)
        assertEquals(UserModelLibrary.NOT_LITERTLM, r.exceptionOrNull()?.message)
        assertTrue(settings.userModels.first().isEmpty())
    }

    @Test
    fun aTinyFileIsRefused() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = picked(ctx, "tiny.litertlm", payload(16))
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isFailure)
        assertEquals(UserModelLibrary.TOO_SMALL, r.exceptionOrNull()?.message)
        assertTrue(settings.userModels.first().isEmpty())
        // The partial file is not left behind.
        assertTrue(File(ctx.getExternalFilesDir(null), "models").listFiles()?.isEmpty() != false)
    }

    @Test
    fun aStreamThatFailsHalfwayLeavesNothingBehind() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val full = payload(2 * 1024 * 1024)
        val uri = picked(ctx, "broken.litertlm", full) {
            object : InputStream() {
                private var served = 0
                override fun read(): Int = throw IOException("boom")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= full.size / 2) throw IOException("boom")
                    val n = minOf(len, full.size / 2 - served)
                    System.arraycopy(full, served, b, off, n)
                    served += n
                    return n
                }
            }
        }
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(settings.userModels.first().isEmpty())
        // The half-written copy is deleted.
        val models = File(ctx.getExternalFilesDir(null), "models")
        assertTrue(models.listFiles()?.none { it.name.endsWith(".tmp") } != false)
    }

    @Test
    fun theSameBytesTwiceDoNotMakeTwoModels() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val bytes = payload(2 * 1024 * 1024)
        val first = lib.importFile(picked(ctx, "one.litertlm", bytes), "", 2048) { _, _ -> }
        assertTrue(first.isSuccess)
        val second = lib.importFile(picked(ctx, "two.litertlm", bytes), "", 2048) { _, _ -> }
        assertTrue(second.isFailure)
        assertEquals(UserModelLibrary.ALREADY_ADDED, second.exceptionOrNull()?.message)
        assertEquals(1, settings.userModels.first().size)
    }

    @Test
    fun aFileThatCannotBeOpenedIsRefused() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = Uri.parse("content://nothing/here")
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isFailure)
        assertTrue(settings.userModels.first().isEmpty())
    }

    @Test
    fun aFileWithNoSizeIsStillImported() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        // A provider that does not report a size: the free space check is skipped, not failed.
        val uri = picked(ctx, "unknown.litertlm", payload(2 * 1024 * 1024), size = 0L)
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isSuccess)
        assertNotNull(r.getOrThrow().sha256)
    }

    @Test
    fun theProgressIsReportedAndEndsAtTheWholeFile() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val total = 2 * 1024 * 1024
        val seen = mutableListOf<Pair<Long, Long>>()
        val r = lib.importFile(picked(ctx, "p.litertlm", payload(total)), "", 2048) { done, size ->
            seen += done to size
        }
        assertTrue(r.isSuccess)
        assertTrue(seen.isNotEmpty())
        assertEquals(total.toLong(), seen.last().first.toLong())
    }

    @Test
    fun theNameOfTheFileIsNeverUsedAsAPath() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val lib = library(ctx)
        val uri = picked(ctx, "../../evil.litertlm", payload(2 * 1024 * 1024))
        val r = lib.importFile(uri, "", 2048) { _, _ -> }
        assertTrue(r.isSuccess)
        val m = r.getOrThrow()
        // The stored name is generated; the picked name never becomes a path.
        assertFalse(m.fileName.contains("evil"))
        assertFalse(m.fileName.contains("/"))
        assertTrue(settings.userModels.first().none { it.fileName.contains("..") })
    }
}
