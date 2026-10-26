package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The address of the user's own server: what is accepted, what is refused, and which hosts count as the local
 * network (the only ones plain `http` is allowed to).
 */
class CustomEndpointTest {
    private fun ok(raw: String): CustomEndpoint.Parsed.Ok {
        val p = CustomEndpoint.parse(raw)
        assertTrue(p is CustomEndpoint.Parsed.Ok, "expected $raw to parse, got $p")
        return p as CustomEndpoint.Parsed.Ok
    }

    private fun bad(raw: String): CustomEndpoint.Parsed.Bad {
        val p = CustomEndpoint.parse(raw)
        assertTrue(p is CustomEndpoint.Parsed.Bad, "expected $raw to be refused, got $p")
        return p as CustomEndpoint.Parsed.Bad
    }

    @Test
    fun aLoopbackServerOnHttpIsUsable() {
        val p = ok("http://127.0.0.1:8910/v1/")
        assertEquals("http://127.0.0.1:8910/v1/chat/completions", p.chatUrl)
        assertEquals("http://127.0.0.1:8910/v1/models", p.modelsUrl)
        assertFalse(p.https)
        assertTrue(p.local)
        assertEquals("127.0.0.1:8910", p.label)
    }

    @Test
    fun aPrivateAddressOnHttpIsUsable() {
        val p = ok("http://192.168.0.15:8888/v1")
        assertEquals("http://192.168.0.15:8888/v1/chat/completions", p.chatUrl)
        assertEquals("http://192.168.0.15:8888/v1/models", p.modelsUrl)
        assertTrue(p.local)
        assertFalse(p.https)
    }

    @Test
    fun anAddressWithoutASchemeToALocalHostIsPlainHttp() {
        val p = ok("192.168.0.15:8888/v1")
        assertEquals("http://192.168.0.15:8888/v1/chat/completions", p.chatUrl)
        assertTrue(p.local)
        assertFalse(p.https)
    }

    @Test
    fun anAddressWithoutASchemeToAPublicHostIsHttps() {
        val p = ok("api.example.com/v1")
        assertEquals("https://api.example.com/v1/chat/completions", p.chatUrl)
        assertEquals("https://api.example.com/v1/models", p.modelsUrl)
        assertFalse(p.local)
        assertTrue(p.https)
    }

    @Test
    fun aPastedCompletionsAddressKeepsItsChatUrl() {
        val p = ok("https://api.example.com/v1/chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", p.chatUrl)
        assertEquals("https://api.example.com/v1/models", p.modelsUrl)
    }

    @Test
    fun aQueryAndAFragmentAreIgnored() {
        val p = ok("https://api.example.com/v1?x=1#frag")
        assertEquals("https://api.example.com/v1/chat/completions", p.chatUrl)
    }

    @Test
    fun plainHttpToAPublicHostIsRefused() {
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://182.168.0.15:8888/v1").problem)
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://example.com").problem)
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://8.8.8.8").problem)
        // 172.16/12 is private; 172.15 and 172.32 are not.
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://172.15.0.1").problem)
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://172.32.0.1").problem)
        // Shaped like an address but not one.
        assertEquals(CustomEndpoint.Problem.HTTP_NOT_LOCAL, bad("http://192.168.0.256").problem)
    }

    @Test
    fun theLocalNetworkIsRecognised() {
        for (h in listOf(
            "localhost", "[::1]", "mypc", "nas.local", "host.docker.internal", "100.64.0.5",
            "172.16.0.1", "172.31.255.255", "10.0.2.2", "169.254.1.1", "fd00::1", "fe80::1",
        )) {
            assertTrue(CustomEndpoint.isLocalHost(h), "$h should be local")
        }
    }

    @Test
    fun theInternetIsNotTheLocalNetwork() {
        for (h in listOf("example.com", "8.8.8.8", "182.168.0.15", "172.15.0.1", "172.32.0.1", "192.168.0.256")) {
            assertFalse(CustomEndpoint.isLocalHost(h), "$h should not be local")
        }
    }

    @Test
    fun credentialsInTheAddressAreRefused() {
        assertEquals(CustomEndpoint.Problem.CREDENTIALS, bad("http://user:pw@192.168.0.2/v1").problem)
    }

    @Test
    fun otherSchemesAreRefused() {
        assertEquals(CustomEndpoint.Problem.SCHEME, bad("ftp://x").problem)
    }

    @Test
    fun anEmptyOrBrokenAddressIsRefused() {
        assertEquals(CustomEndpoint.Problem.NOT_A_URL, bad("http://").problem)
        assertEquals(CustomEndpoint.Problem.EMPTY, bad("   ").problem)
    }

    @Test
    fun anyHttpsAddressIsUsable() {
        val p = ok("https://anything.example.com")
        assertTrue(p.https)
        assertFalse(p.local)
        assertEquals("https://anything.example.com/chat/completions", p.chatUrl)
    }
}
