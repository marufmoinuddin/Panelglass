package com.smnexstudio.panelglass.core.data.download

import java.io.File

/**
 * What a caller of [SystemDownloads] needs: queue, follow, cancel and verify one keyed download. The seam lets the
 * update flow ([com.smnexstudio.panelglass.core.data.update.AppUpdates]) be tested without DownloadManager.
 */
interface Downloads {
    /** Whether a download for [key] is queued or running (in this or an earlier app session). */
    fun isActive(key: String): Boolean

    /** Queues [url] into [relativePath], written as `…part` until [await] places it. Starting a queued key is a no-op. */
    fun start(key: String, url: String, relativePath: String, title: String, description: String = "Panelglass on-device model")

    fun cancel(key: String)

    /** Follows [key] until it ends; the file placed at [relativePath] once its SHA-256 is [sha256], else an IOException. */
    suspend fun await(key: String, relativePath: String, minBytes: Long, sha256: String, onProgress: (SystemDownloads.Progress) -> Unit): File

    /** Where a verified file for [relativePath] ends up, or null when storage is unavailable. */
    fun finalFile(relativePath: String): File?

    /** [file] when its SHA-256 is [sha256] (checked once, then remembered), else null and the file deleted. */
    fun adopt(file: File, sha256: String): File?
}
