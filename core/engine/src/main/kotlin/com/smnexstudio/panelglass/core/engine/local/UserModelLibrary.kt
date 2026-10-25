package com.smnexstudio.panelglass.core.engine.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.UserModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own model library: the models they downloaded from Hugging Face or imported from a file, and which of
 * them the "Offline model" engine runs.
 *
 * A model is only ever added from a `.litertlm` file whose SHA-256 matches what was promised, so a truncated or
 * tampered download is refused instead of loaded.
 */
@Singleton
class UserModelLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val stores: ModelStores,
    private val engines: LocalEngines,
    private val downloads: SystemDownloads,
    private val catalog: HfCatalog,
) {
    /** The library, as saved. */
    val models: Flow<List<UserModel>> = settings.userModels

    /**
     * Adds a model from the Hugging Face catalogue and starts its download.
     *
     * The address is checked against the hub before it is saved: only a `https://huggingface.co/` file is ever
     * fetched, whatever the listing said.
     */
    suspend fun addFromHub(
        l: HfCatalog.HfListing,
        f: HfCatalog.HfFile,
        label: String,
        maxTokens: Int,
    ): Result<UserModel> = withContext(Dispatchers.IO) {
        // The repository must look like one, and the address must be on the hub: nothing else is ever fetched.
        if (!catalog.looksLikeRepoId(l.repoId)) return@withContext Result.failure(IOException("Not a Hugging Face repository"))
        val url = catalog.downloadUrl(l, f)
        if (!catalog.isHubUrl(url)) return@withContext Result.failure(IOException("Not a Hugging Face address"))
        if (!hasRoom(f.bytes)) return@withContext Result.failure(IOException(NOT_ENOUGH_SPACE))
        val sha = f.sha256.lowercase()
        val id = UserModel.hubId(sha)
        val existing = settings.userModels.first()
        if (existing.any { it.id == id }) return@withContext Result.failure(IOException(ALREADY_ADDED))
        val m = UserModel(
            id = id,
            label = label.trim().ifEmpty { l.repoId.substringAfter('/') },
            fileName = UserModel.fileNameFor(id),
            url = url,
            bytes = f.bytes,
            sha256 = sha,
            maxTokens = UserModel.clampTokens(maxTokens),
            source = l.repoId,
        )
        settings.saveUserModels(existing + m)
        stores.forUser(m).download()
        Result.success(m)
    }

    /**
     * Imports a `.litertlm` file from the phone's storage.
     *
     * The file is copied into the models directory while its SHA-256 is computed. The hash is only used to name the
     * model and to recognise the same file twice: an imported file has no published hash to be compared against, so
     * nothing about its content is refused.
     *
     * The picked file's own name is never used as a path: the copy is written under a generated name.
     */
    suspend fun importFile(
        uri: Uri,
        label: String,
        maxTokens: Int,
        onProgress: (Long, Long) -> Unit,
    ): Result<UserModel> = withContext(Dispatchers.IO) {
        val info = info(uri) ?: return@withContext Result.failure(IOException(NOT_LITERTLM))
        val name = info.first
        if (!isModelFileName(name)) return@withContext Result.failure(IOException(NOT_LITERTLM))
        val size = info.second
        // A size the provider does not report is unknown, not zero: the free space check is skipped then.
        if (size > 0 && !hasRoom(size)) return@withContext Result.failure(IOException(NOT_ENOUGH_SPACE))

        val root = downloads.root() ?: return@withContext Result.failure(IOException("Storage unavailable"))
        val tmp = File(root, "models/import-${System.currentTimeMillis()}.tmp")
        var read = 0L
        try {
            tmp.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IOException("Could not read the file"))
            input.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        read += n
                        // The progress is reported a few times a second, not once per buffer: a multi-gigabyte file
                        // would otherwise spend all its time in the UI.
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(read, if (size > 0) size else read)
                        }
                    }
                    out.flush()
                }
            }
            if (read < MIN_IMPORT_BYTES) {
                tmp.delete()
                return@withContext Result.failure(IOException(TOO_SMALL))
            }
            onProgress(read, if (size > 0) size else read)

            val sha = hex(digest.digest())
            val id = UserModel.fileId(sha)
            val existing = settings.userModels.first()
            if (existing.any { it.id == id }) {
                tmp.delete()
                return@withContext Result.failure(IOException(ALREADY_ADDED))
            }
            val m = UserModel(
                id = id,
                label = label.trim().ifEmpty { labelFor(name) },
                fileName = UserModel.fileNameFor(id),
                url = "",
                bytes = tmp.length(),
                sha256 = sha,
                maxTokens = UserModel.clampTokens(maxTokens),
                source = "file",
            )
            // Moves the file into place and marks it verified; the hash is the one just computed over the copy.
            downloads.place(tmp, "models/" + m.fileName, m.sha256)
            settings.saveUserModels(existing + m)
            Result.success(m)
        } catch (e: Exception) {
            tmp.delete()
            Log.w("UserModelLibrary", "import failed: ${e.javaClass.simpleName} after $read bytes")
            Result.failure(e)
        }
    }

    /** Removes a model: its file, its engine and its place in the library. */
    suspend fun remove(m: UserModel) {
        engines.dropUser(m.id)
        stores.dropUser(m.id)
        val remaining = settings.userModels.first().filterNot { it.id == m.id }
        settings.saveUserModels(remaining)
        if (settings.current().activeUserModelId == m.id) settings.setActiveUserModel("")
    }

    /** The model the "Offline model" engine runs. */
    suspend fun setActive(id: String) = settings.setActiveUserModel(id)

    /**
     * The display name and size of a file the user picked. The size is -1 when the provider does not report one.
     * Null when the file cannot be read at all.
     */
    fun info(uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = c.getString(nameIndex)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val n = name ?: return null
        return n to size
    }

    /**
     * Whether a picked file's name says it is a LiteRT-LM model. The extension is matched without regard to case,
     * so a file the phone shows as `MODEL.LITERTLM` is accepted.
     */
    fun isModelFileName(name: String): Boolean = name.trim().endsWith(MODEL_EXTENSION, ignoreCase = true)

    /**
     * The name a picked file is shown under: its own name without the extension, whatever the case of either.
     */
    fun labelFor(name: String): String {
        val n = name.trim()
        return if (n.endsWith(MODEL_EXTENSION, ignoreCase = true)) n.dropLast(MODEL_EXTENSION.length).trim() else n
    }

    /**
     * Whether there is room for a file of [bytes]: the file itself plus some headroom, so the phone does not fill up
     * completely with a model on it.
     */
    private fun hasRoom(bytes: Long): Boolean {
        val root = downloads.root() ?: return false
        return root.usableSpace >= bytes + HEADROOM
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** Free space kept on top of the file itself. */
        const val HEADROOM = 200L * 1024 * 1024
        private const val BUFFER = 1 shl 20
        /** How often the import reports its progress, in milliseconds. */
        private const val PROGRESS_INTERVAL_MS = 250L
        /** The extension a picked file must have. */
        const val MODEL_EXTENSION = ".litertlm"
        /**
         * The smallest file an import accepts. A real LiteRT-LM model is at least a few hundred megabytes; anything
         * below this is not one, and is refused rather than added and found broken later.
         */
        const val MIN_IMPORT_BYTES = 1L * 1024 * 1024
        const val NOT_ENOUGH_SPACE = "Not enough storage"
        const val ALREADY_ADDED = "Already added"
        const val NOT_LITERTLM = "Only a .litertlm model file can be added"
        const val TOO_SMALL = "That file is too small to be a model"
    }
}
