package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.data.di.LocalOnlyCleartext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
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

    @Test
    fun plainHttpToAPublicIpv6AddressIsRefused() {
        server.enqueue(MockResponse().setBody("should not be reached"))
        val c = client()
        // 2606:4700::ffff:c0a8:1 is a public address whose last four bytes look like 192.168.0.1. It used to pass
        // the local check, so plain http to it was allowed.
        val request = Request.Builder().url("http://[2606:4700::ffff:c0a8:1]:${server.port}/").build()
        assertThrows(IOException::class.java) {
            c.newCall(request).execute().use { it.body?.string() }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun plainHttpToALocalIpv6AddressIsAllowed() {
        // The server listens on IPv4 only, so an IPv6 address has nothing to connect to. What is being checked is the
        // guard's decision, not the socket: the interceptor lets a local IPv6 address through.
        val request = Request.Builder().url("http://[::1]:${server.port}/").build()
        val chain = FakeChain(request)
        LocalOnlyCleartext().intercept(chain)
        assertEquals(1, chain.proceeded)
    }

    @Test
    fun plainHttpToAPublicIpv6AddressIsRefusedByTheGuard() {
        val request = Request.Builder().url("http://[2606:4700::ffff:c0a8:1]:${server.port}/").build()
        val chain = FakeChain(request)
        assertThrows(IOException::class.java) { LocalOnlyCleartext().intercept(chain) }
        assertEquals(0, chain.proceeded)
    }

    /** A chain that records whether the request was let through, without needing a server. */
    private class FakeChain(private val request: Request) : okhttp3.Interceptor.Chain {
        var proceeded = 0
        override fun request(): Request = request
        override fun proceed(request: Request): okhttp3.Response {
            proceeded++
            return okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body("ok".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        override fun connection(): okhttp3.Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): okhttp3.Interceptor.Chain = this
    }
}
