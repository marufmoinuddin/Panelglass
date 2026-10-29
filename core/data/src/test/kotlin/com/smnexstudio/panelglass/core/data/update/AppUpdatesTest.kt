package com.smnexstudio.panelglass.core.data.update

import android.content.Context
import com.smnexstudio.panelglass.core.data.MemoryDataStore
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.download.Downloads
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The update flow against a local server standing in for GitHub: every request the app makes to api.github.com or
 * github.com is routed to it, so nothing here reaches the internet. Downloads go through a fake [Downloads].
 * Robolectric (JUnit 4 via vintage) for android.util.Log and the settings file.
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var settings: SettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var downloads: FakeDownloads
    private val routes = HashMap<String, MockResponse>()
    /** Hosts the app asked for, before they were routed to [server]. */
    private val hosts = mutableListOf<String>()
    private var clock = 1_000_000_000_000L

    private val apkBytes = "an invented APK body, long enough to stand in".toByteArray()
    private val apkSha = sha(apkBytes)

    @Before fun setUp() {
        root = File(context.filesDir, "updates-test").apply { deleteRecursively(); mkdirs() }
        settings = SettingsRepository(MemoryDataStore())
        downloads = FakeDownloads(File(root, "external"), apkBytes)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = routes[request.path] ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private fun client(readTimeoutMs: Long = 5_000) = OkHttpClient.Builder()
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            synchronized(hosts) { hosts += req.url.host }
            val local = req.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(req.newBuilder().url(local).build())
        }
        .build()

    private fun updates(
        installed: String = "0.2.0", debuggable: Boolean = false, http: OkHttpClient = client(),
    ) = AppUpdates(downloads, settings, { http }, { installed }, debuggable, now = { clock })

    private val latestPath = "/repos/${ReleaseInfo.REPO}/releases/latest"
    private fun assetPath(version: String, name: String) = "/${ReleaseInfo.REPO}/releases/download/v$version/$name"

    /** GitHub's `releases/latest` reply for [version], with its APK and `.sha256` assets. */
    private fun release(version: String, prerelease: Boolean = false, withSha: Boolean = true): String {
        val apk = "panelglass-$version.apk"
        val base = "${ReleaseInfo.PREFIX}v$version/"
        val sha = if (withSha) """,{"name":"$apk.sha256","size":87,"browser_download_url":"$base$apk.sha256"}""" else ""
        return """{"tag_name":"v$version","draft":false,"prerelease":$prerelease,"assets":[""" +
            """{"name":"$apk","size":${apkBytes.size},"browser_download_url":"$base$apk"}$sha]}"""
    }

    private fun serveLatest(body: String, code: Int = 200) { routes[latestPath] = MockResponse().setResponseCode(code).setBody(body) }
    private fun serveSha(version: String, hash: String = apkSha) {
        routes[assetPath(version, "panelglass-$version.apk.sha256")] = MockResponse().setBody("$hash  panelglass-$version.apk\n")
    }

    private suspend fun checked(u: AppUpdates): UpdateState { u.checkNow(); u.idle(); return u.state.value }

    // ---- Settings › Check for updates ------------------------------------------------------------------------

    @Test fun aNewerReleaseIsOfferedAndTheCheckIsRemembered() = runBlocking<Unit> {
        serveLatest(release("0.3.0"))
        val s = checked(updates(installed = "0.2.0"))
        assertTrue("got $s", s is UpdateState.Available)
        s as UpdateState.Available
        assertEquals("0.3.0", s.release.version)
        assertEquals("0.2.0", s.current)
        assertEquals(clock, settings.updateCheckedAt())
        // Only GitHub is asked, with the API's media type and a user agent; nothing about the user is sent.
        assertEquals(listOf("api.github.com"), hosts)
        val req = server.takeRequest()
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertEquals("Panelglass", req.getHeader("User-Agent"))
    }

    @Test fun theSameOrAnOlderReleaseIsUpToDate() = runBlocking<Unit> {
        serveLatest(release("0.3.0"))
        assertEquals(UpdateState.UpToDate("0.3.0"), checked(updates(installed = "0.3.0")))
        assertEquals(UpdateState.UpToDate("0.4.1"), checked(updates(installed = "0.4.1")))
        assertEquals(UpdateState.UpToDate("0.3.0.0"), checked(updates(installed = "0.3.0.0")))
    }

    @Test fun aReleaseTheAppDoesNotTakeIsNoUpdate() = runBlocking<Unit> {
        serveLatest(release("0.9.0", prerelease = true))
        assertEquals(UpdateState.UpToDate("0.2.0"), checked(updates()))
        serveLatest(release("0.9.0", withSha = false))
        assertEquals(UpdateState.UpToDate("0.2.0"), checked(updates()))
        serveLatest("""{"tag_name":"v0.9.0-beta","draft":false,"prerelease":false,"assets":[]}""")
        assertEquals(UpdateState.UpToDate("0.2.0"), checked(updates()))
    }

    /** No false update: an installed version that cannot be read is never older than a release. */
    @Test fun anUnreadableInstalledVersionIsNeverOfferedAnUpdate() = runBlocking<Unit> {
        serveLatest(release("9.0.0"))
        assertEquals(UpdateState.UpToDate(""), checked(updates(installed = "")))
        assertEquals(UpdateState.UpToDate("0.2.0-dev"), checked(updates(installed = "0.2.0-dev")))
    }

    /** 403 and 429 are GitHub's rate limits; 404 a repository without releases; 5xx GitHub itself. */
    @Test fun everyHttpErrorIsAFailedCheckNeverUpToDate() = runBlocking<Unit> {
        for (code in listOf(403, 404, 429, 500, 502, 503)) {
            serveLatest("""{"message":"invented failure"}""", code)
            assertEquals("HTTP $code", UpdateState.Failed(null), checked(updates()))
        }
        assertEquals("a failed check is not remembered as done", 0L, settings.updateCheckedAt())
    }

    @Test fun aReplyThatIsNoReleaseIsAFailedCheck() = runBlocking<Unit> {
        for (body in listOf("", "<html>Service unavailable</html>", """{"message":"Not Found"}""", """{"tag_name":""", "[]")) {
            serveLatest(body)
            assertEquals("body '$body'", UpdateState.Failed(null), checked(updates()))
        }
    }

    @Test fun noConnectionIsAFailedCheck() = runBlocking<Unit> {
        val offline = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("api.github.com") }.build()
        assertEquals(UpdateState.Failed(null), checked(updates(http = offline)))
        // A refused connection: a port nothing listens on any more.
        val gone = MockWebServer().apply { start() }
        val port = gone.port
        gone.shutdown()
        val refused = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().scheme("http").host(server.hostName).port(port).build()).build())
        }.build()
        assertEquals(UpdateState.Failed(null), checked(updates(http = refused)))
    }

    @Test fun aServerThatNeverAnswersTimesOutAsAFailedCheck() = runBlocking<Unit> {
        routes[latestPath] = MockResponse().setBody(release("0.3.0")).setHeadersDelay(3, TimeUnit.SECONDS)
        assertEquals(UpdateState.Failed(null), checked(updates(http = client(readTimeoutMs = 200))))
    }

    @Test fun repeatedChecksEndWithOneAnswer() = runBlocking<Unit> {
        serveLatest(release("0.3.0"))
        val u = updates()
        repeat(5) { u.checkNow() }
        u.idle()
        assertTrue(u.state.value is UpdateState.Available)
        u.dismiss()
        assertEquals(UpdateState.Idle, u.state.value)
    }

    // ---- at app start ----------------------------------------------------------------------------------------

    @Test fun theStartupCheckIsSkippedInDebugBuildsWhenTurnedOffAndWithinADay() = runBlocking<Unit> {
        serveLatest(release("0.3.0"))
        updates(debuggable = true).also { it.checkOnStart(); it.idle(); assertEquals(UpdateState.Idle, it.state.value) }
        settings.setAutoUpdateCheck(false)
        updates().also { it.checkOnStart(); it.idle(); assertEquals(UpdateState.Idle, it.state.value) }
        settings.setAutoUpdateCheck(true)
        settings.setUpdateCheckedAt(clock - AppUpdates.CHECK_EVERY_MS + 60_000)
        updates().also { it.checkOnStart(); it.idle(); assertEquals(UpdateState.Idle, it.state.value) }
        assertEquals("GitHub was never asked", 0, server.requestCount)
    }

    @Test fun theStartupCheckAsksAgainAfterADayAndShowsOnlyANewerRelease() = runBlocking<Unit> {
        settings.setUpdateCheckedAt(clock - AppUpdates.CHECK_EVERY_MS - 1)
        serveLatest(release("0.2.0"))
        updates(installed = "0.2.0").also { it.checkOnStart(); it.idle(); assertEquals("no 'up to date' at start", UpdateState.Idle, it.state.value) }
        assertEquals(1, server.requestCount)
        assertEquals(clock, settings.updateCheckedAt())
        clock += AppUpdates.CHECK_EVERY_MS + 1
        serveLatest(release("0.3.0"))
        updates(installed = "0.2.0").also { it.checkOnStart(); it.idle(); assertTrue(it.state.value is UpdateState.Available) }
    }

    /** Offline at start: nothing is shown, nothing throws, and the next start asks again. */
    @Test fun aFailedStartupCheckShowsNothingAndIsRetriedNextStart() = runBlocking<Unit> {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        updates(http = offline).also { it.checkOnStart(); it.idle(); assertEquals(UpdateState.Idle, it.state.value) }
        assertEquals(0L, settings.updateCheckedAt())
        serveLatest("<html>")
        updates().also { it.checkOnStart(); it.idle(); assertEquals(UpdateState.Idle, it.state.value) }
        assertEquals(0L, settings.updateCheckedAt())
    }

    /** ~280 MB each: installed and older APKs and interrupted downloads go at start; a newer finished APK stays. */
    @Test fun spentDownloadsAreDeletedAtStart() = runBlocking<Unit> {
        val dir = File(downloads.root, AppUpdates.DIR).apply { mkdirs() }
        val names = listOf("panelglass-0.1.0.apk", "panelglass-0.2.0.apk", "panelglass-0.3.0.apk.part", "panelglass-0.3.0.apk")
        names.forEach { File(dir, it).writeText("x") }
        updates(installed = "0.2.0", debuggable = true).also { it.checkOnStart(); it.idle() }
        assertEquals(listOf("panelglass-0.3.0.apk"), dir.list()!!.sorted())
    }

    // ---- download ----------------------------------------------------------------------------------------------

    private suspend fun offered(u: AppUpdates, version: String = "0.3.0"): AppUpdates {
        serveLatest(release(version))
        u.checkNow(); u.idle()
        assertTrue(u.state.value is UpdateState.Available)
        return u
    }

    @Test fun aVerifiedDownloadIsReadyForTheInstaller() = runBlocking<Unit> {
        serveSha("0.3.0")
        val u = offered(updates())
        u.download(); u.idle()
        val s = u.state.value
        assertTrue("got $s", s is UpdateState.Ready)
        s as UpdateState.Ready
        assertTrue(s.apk.readBytes().contentEquals(apkBytes))
        assertEquals("updates/panelglass-0.3.0.apk", s.apk.relativeTo(downloads.root).invariantSeparatorsPath)
        assertEquals(listOf("${ReleaseInfo.PREFIX}v0.3.0/panelglass-0.3.0.apk"), downloads.started)
    }

    @Test fun aChecksumMismatchFailsAndNothingIsHandedToTheInstaller() = runBlocking<Unit> {
        serveSha("0.3.0", hash = "0".repeat(64))
        val u = offered(updates())
        u.download(); u.idle()
        val s = u.state.value
        assertTrue("got $s", s is UpdateState.Failed)
        assertEquals("Retry offers the same release", "0.3.0", (s as UpdateState.Failed).release?.version)
        assertFalse(File(downloads.root, "updates/panelglass-0.3.0.apk").exists())
        assertEquals(listOf(KEY), downloads.cancelled)
    }

    @Test fun aMissingChecksumFailsBeforeAnythingIsDownloaded() = runBlocking<Unit> {
        val u = offered(updates())
        u.download(); u.idle()
        assertTrue(u.state.value is UpdateState.Failed)
        assertTrue(downloads.started.isEmpty())
    }

    @Test fun anEarlierFinishedDownloadOfTheSameReleaseIsUsedAsItIs() = runBlocking<Unit> {
        serveSha("0.3.0")
        File(downloads.root, "updates/panelglass-0.3.0.apk").apply { parentFile!!.mkdirs(); writeBytes(apkBytes) }
        val u = offered(updates())
        u.download(); u.idle()
        assertTrue(u.state.value is UpdateState.Ready)
        assertTrue("nothing downloaded again", downloads.started.isEmpty())
    }

    @Test fun cancelDuringTheDownloadRemovesTheSystemDownload() = runBlocking<Unit> {
        serveSha("0.3.0")
        downloads.gate = CompletableDeferred()
        val u = offered(updates())
        u.download()
        withTimeout(5_000) { u.state.first { it is UpdateState.Downloading && it.bytes > 0 } }
        u.cancel()
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(listOf(KEY), downloads.cancelled)
    }

    /** A check asked for mid-download replaces the download; it must not run on unseen. */
    @Test fun checkingDuringADownloadCancelsTheSystemDownload() = runBlocking<Unit> {
        serveSha("0.3.0")
        downloads.gate = CompletableDeferred()
        val u = offered(updates())
        u.download()
        withTimeout(5_000) { u.state.first { it is UpdateState.Downloading && it.bytes > 0 } }
        u.checkNow(); u.idle()
        assertEquals(listOf(KEY), downloads.cancelled)
        assertTrue(u.state.value is UpdateState.Available)
    }

    @Test fun laterIsIgnoredWhileDownloading() = runBlocking<Unit> {
        serveSha("0.3.0")
        downloads.gate = CompletableDeferred()
        val u = offered(updates())
        u.download()
        withTimeout(5_000) { u.state.first { it is UpdateState.Downloading } }
        u.dismiss()
        assertTrue(u.state.value is UpdateState.Downloading)
        downloads.gate!!.complete(Unit); u.idle()
        assertTrue(u.state.value is UpdateState.Ready)
    }

    // ---- fakes -------------------------------------------------------------------------------------------------

    /** Stands in for DownloadManager: "downloads" [body] into [root], checked against the hash like the real one. */
    private class FakeDownloads(val root: File, private val body: ByteArray) : Downloads {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        /** When set, a download reports progress and waits here before it finishes. */
        var gate: CompletableDeferred<Unit>? = null
        private var active = false

        override fun isActive(key: String) = active
        override fun start(key: String, url: String, relativePath: String, title: String, description: String) {
            started += url; active = true
        }
        override fun cancel(key: String) { cancelled += key; active = false }
        override suspend fun await(key: String, relativePath: String, minBytes: Long, sha256: String, onProgress: (SystemDownloads.Progress) -> Unit): File {
            onProgress(SystemDownloads.Progress(body.size / 2L, body.size.toLong()))
            gate?.await()
            active = false
            val f = File(root, relativePath).apply { parentFile!!.mkdirs(); writeBytes(body) }
            if (!sha(body).equals(sha256, ignoreCase = true)) { f.delete(); throw IOException(SystemDownloads.CORRUPT) }
            return f
        }
        override fun finalFile(relativePath: String) = File(root, relativePath)
        override fun adopt(file: File, sha256: String): File? =
            if (file.isFile && sha(file.readBytes()).equals(sha256, ignoreCase = true)) file else { file.delete(); null }
    }

    private companion object {
        const val KEY = "app-update"
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
