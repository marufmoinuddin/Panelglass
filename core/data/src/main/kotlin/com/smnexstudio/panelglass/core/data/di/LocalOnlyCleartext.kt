package com.smnexstudio.panelglass.core.data.di

import com.smnexstudio.panelglass.core.model.CustomEndpoint
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Refuses cleartext HTTP to anything but a host on the user's own network.
 *
 * The platform allows cleartext so that Custom AI can reach a model server at home (see
 * `network_security_config.xml`); this is what keeps the rest of the app https-only. It runs as a **network**
 * interceptor, so it sees every hop — a redirect to an `http` host is refused too, before any request data is sent.
 *
 * The rule is the same as the address check in [CustomEndpoint]: loopback, the private ranges and the local names.
 */
class LocalOnlyCleartext : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !CustomEndpoint.isLocalHost(url.host)) {
            throw IOException("Cleartext HTTP is only allowed on the local network")
        }
        return chain.proceed(chain.request())
    }
}
