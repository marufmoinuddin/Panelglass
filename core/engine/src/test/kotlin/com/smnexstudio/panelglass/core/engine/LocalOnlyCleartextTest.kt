package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.data.di.LocalOnlyCleartext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The cleartext interceptor: plain HTTP is allowed to a host on the user's own network and refused to anything else.
 *
 * The server stands in for both: a request to `127.0.0.1` is local, and a name that resolves to `127.0.0.1` through a
 * custom DNS is still a public host as far as the rule is concerned.
 */
class LocalOnlyCleartextTest {
    private lateinit var server: MockWebServer

    @BeforeEach fun start() {
        server = MockWebServer()
        server.start()
    }

    /** A client that resolves every name to the loopback, so the host in the address is what decides. */
    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(LocalOnlyCleartext())
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
        })
        .build()

    @Test
    fun plainHttpToTheLocalNetworkIsAllowed() {
        server.enqueue(MockResponse().setBody("ok"))
        val c = client()
        val request = Request.Builder().url("http://127.0.0.1:${server.port}/").build()
        c.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun plainHttpToAnyOtherHostIsRefused() {
        server.enqueue(MockResponse().setBody("should not be reached"))
        val c = client()
        val request = Request.Builder().url("http://blocked.example:${server.port}/").build()
        assertThrows(IOException::class.java) {
            c.newCall(request).execute().use { it.body?.string() }
        }
        // The request never left the phone.
        assertEquals(0, server.requestCount)
    }
}
