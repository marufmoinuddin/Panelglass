package com.smnexstudio.panelglass.core.data.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseInfoTest {
    private val base = "https://github.com/smnexstudio/Panelglass/releases/download/v0.3.0/"

    /** The shape of GitHub's `releases/latest` reply, as release.yml publishes it (APK + .sha256). */
    private fun reply(
        tag: String = "v0.3.0", draft: Boolean = false, pre: Boolean = false,
        apkUrl: String = base + "panelglass-0.3.0.apk", withSha: Boolean = true,
    ) = """
        {"tag_name": "$tag", "draft": $draft, "prerelease": $pre, "assets": [
          {"name": "panelglass-0.3.0.apk", "size": 283526870, "browser_download_url": "$apkUrl"}
          ${if (withSha) """,{"name": "panelglass-0.3.0.apk.sha256", "size": 87, "browser_download_url": "${base}panelglass-0.3.0.apk.sha256"}""" else ""}
        ]}
    """.trimIndent()

    @Test
    fun aPublishedReleaseIsRead() {
        val r = ReleaseInfo.parse(reply())!!
        assertEquals("0.3.0", r.version)
        assertEquals("panelglass-0.3.0.apk", r.apkName)
        assertEquals(base + "panelglass-0.3.0.apk", r.apkUrl)
        assertEquals(283526870L, r.apkBytes)
        assertEquals(base + "panelglass-0.3.0.apk.sha256", r.shaUrl)
    }

    @Test
    fun draftsPreReleasesAndAReleaseWithoutChecksumAreNotOffered() {
        assertNull(ReleaseInfo.parse(reply(draft = true)))
        assertNull(ReleaseInfo.parse(reply(pre = true)))
        assertNull(ReleaseInfo.parse(reply(withSha = false)))
        assertNull(ReleaseInfo.parse("not json"))
    }

    /** The APK is only ever fetched from this repository's releases, over https. */
    @Test
    fun anApkFromAnywhereElseIsRefused() {
        assertNull(ReleaseInfo.parse(reply(apkUrl = "https://example.com/panelglass-0.3.0.apk")))
        assertNull(ReleaseInfo.parse(reply(apkUrl = "http://github.com/smnexstudio/Panelglass/releases/download/v0.3.0/panelglass-0.3.0.apk")))
        assertNull(ReleaseInfo.parse(reply(apkUrl = "https://github.com/someone/Panelglass/releases/download/v0.3.0/panelglass-0.3.0.apk")))
    }

    /**
     * A URL that starts with this repository's prefix but leaves it once its path is resolved (`..`, encoded or not),
     * or that carries a query, a fragment, a user or a port, is refused: the prefix is checked on the resolved URL.
     */
    @Test
    fun aUrlThatLeavesTheReleasesOnceResolvedIsRefused() {
        val escapes = listOf(
            base + "../../../../someone/Panelglass/releases/download/v0.3.0/panelglass-0.3.0.apk",
            base + "..%2F..%2F..%2F..%2Fsomeone%2Fx%2Fpanelglass-0.3.0.apk",
            base + "%2e%2e/%2e%2e/%2e%2e/%2e%2e/someone/x/panelglass-0.3.0.apk",
            base + "./../../../../someone/x/panelglass-0.3.0.apk",
            base + "panelglass-0.3.0.apk?redirect=https://example.com/a.apk",
            base + "panelglass-0.3.0.apk#x",
            base.replace("https://github.com/", "https://user@github.com/") + "panelglass-0.3.0.apk",
            base.replace("https://github.com/", "https://github.com:8443/") + "panelglass-0.3.0.apk",
            base.replace("/releases/", "/releases\\") + "panelglass-0.3.0.apk",
        )
        for (url in escapes) assertNull(ReleaseInfo.parse(reply(apkUrl = url)), url)
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(ReleaseInfo.isNewer("0.10.0", "0.9.9"))
        assertTrue(ReleaseInfo.isNewer("1.0", "0.9.9"))
        assertTrue(ReleaseInfo.isNewer("v0.2.1", "0.2.0"))
        assertFalse(ReleaseInfo.isNewer("0.2.0", "0.2.0"))
        assertFalse(ReleaseInfo.isNewer("0.2.0", "0.3.0"))
        assertFalse(ReleaseInfo.isNewer("0.2.0.0", "0.2"))
        assertFalse(ReleaseInfo.isNewer("garbage", "0.1.0"))
    }

    /** Only digits and dots: the version also names the downloaded file. */
    @Test
    fun aTagWithASuffixIsNotARelease() {
        assertNull(ReleaseInfo.parse(reply(tag = "v0.3.0-test")))
        assertNull(ReleaseInfo.parseVersion("0.3.0-rc1"))
        assertNull(ReleaseInfo.parseVersion("1.2.3.4.5"))
        assertNull(ReleaseInfo.parseVersion("1..2"))
        assertFalse(ReleaseInfo.isNewer("0.9.0-beta", "0.2.0"))
        assertEquals(listOf(0, 3, 0), ReleaseInfo.parseVersion("v0.3.0"))
    }

    /** An installed or older APK (~280 MB) and an interrupted download go; a newer, finished APK stays. */
    @Test
    fun spentDownloadsAreRecognised() {
        assertTrue(ReleaseInfo.isSpent("panelglass-0.2.0.apk", "0.2.0"))
        assertTrue(ReleaseInfo.isSpent("panelglass-0.1.0.apk", "0.2.0"))
        assertTrue(ReleaseInfo.isSpent("panelglass-0.3.0.apk.part", "0.2.0"))
        assertTrue(ReleaseInfo.isSpent("something-else.bin", "0.2.0"))
        assertFalse(ReleaseInfo.isSpent("panelglass-0.3.0.apk", "0.2.0"))
    }

    /** Optional fields may be missing or odd; the release is still read, or refused, without throwing. */
    @Test
    fun missingOrOddFieldsAreReadOrRefusedWithoutThrowing() {
        val noSize = reply().replace(""""size": 283526870, """, "")
        assertEquals(0L, ReleaseInfo.parse(noSize)!!.apkBytes)
        val textSize = reply().replace("283526870", "\"big\"")
        assertEquals(0L, ReleaseInfo.parse(textSize)!!.apkBytes)
        assertNull(ReleaseInfo.parse("""{"tag_name": "v0.3.0", "assets": "none"}"""))
        assertNull(ReleaseInfo.parse("""{"tag_name": "v0.3.0"}"""))
        assertNull(ReleaseInfo.parse("""{"assets": []}"""))
        assertNull(ReleaseInfo.parse("[]"))
        assertNull(ReleaseInfo.parse(""))
        // No draft/prerelease flags at all: a plain release.
        assertEquals("0.3.0", ReleaseInfo.parse(reply().replace(""""draft": false, "prerelease": false, """, ""))!!.version)
    }

    /** A reply that is no release at all is a failed check, never "up to date". */
    @Test
    fun onlyAReleaseShapedReplyCountsAsAnAnswer() {
        assertTrue(ReleaseInfo.isReleaseReply(reply()))
        assertTrue(ReleaseInfo.isReleaseReply(reply(pre = true)), "a pre-release is an answer, just not an update")
        assertFalse(ReleaseInfo.isReleaseReply("<html>rate limited</html>"))
        assertFalse(ReleaseInfo.isReleaseReply("""{"message": "API rate limit exceeded"}"""))
        assertFalse(ReleaseInfo.isReleaseReply(""))
        assertFalse(ReleaseInfo.isReleaseReply("""{"tag_name": """))
    }

    /** No false update: nothing is newer than a version that cannot be read. */
    @Test
    fun nothingIsNewerThanAnUnreadableInstalledVersion() {
        assertFalse(ReleaseInfo.isNewer("9.9.9", ""))
        assertFalse(ReleaseInfo.isNewer("9.9.9", "0.2.0-debug"))
        assertFalse(ReleaseInfo.isNewer("", ""))
        assertTrue(ReleaseInfo.isNewer("0.2.1", " v0.2.0 "))
        assertTrue(ReleaseInfo.isNewer("0.2.0.1", "0.2"))
    }

    @Test
    fun theChecksumLineIsMatchedByFileName() {
        val hex = "87a1c4b5e8d0b56f4f1f6b3d2a8f0e7c9d6b5a4f3e2d1c0b9a8f7e6d5c4b3a29"
        assertEquals(hex, ReleaseInfo.sha256For("$hex  panelglass-0.3.0.apk\n", "panelglass-0.3.0.apk"))
        assertEquals(hex, ReleaseInfo.sha256For("$hex *panelglass-0.3.0.apk", "panelglass-0.3.0.apk"))
        assertNull(ReleaseInfo.sha256For("$hex  other.apk", "panelglass-0.3.0.apk"))
        assertNull(ReleaseInfo.sha256For("short  panelglass-0.3.0.apk", "panelglass-0.3.0.apk"))
    }
}
