package com.smnexstudio.panelglass.core.model

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * The address of the user's own OpenAI-compatible server (the "Custom AI" engine), and whether it may be reached.
 *
 * The user types what their server's documentation shows: `http://192.168.0.15:8888/v1`, `http://127.0.0.1:8910/v1`,
 * `https://api.example.com/v1`, or the complete completions address. All mean the same server, so the stored form is
 * the base and the engine appends the paths itself.
 *
 * **Plain `http` is allowed only for an address on the user's own network.** A public host over `http` would send the
 * conversation in the clear across the internet; a server on the home network does not leave the house. The decision is
 * made from the address or name as typed — no DNS lookup, so a name that resolves to a public address is still
 * refused (the user can use https or a VPN).
 */
object CustomEndpoint {
    /** What is wrong with an address. */
    enum class Problem {
        /** Nothing was typed. */
        EMPTY,
        /** It is not an address at all. */
        NOT_A_URL,
        /** The scheme is neither http nor https. */
        SCHEME,
        /** Plain http to a host that is not on the local network. */
        HTTP_NOT_LOCAL,
        /** A username or a password was put into the address. */
        CREDENTIALS,
    }

    /** The outcome of reading an address. */
    sealed interface Parsed {
        /** A usable address. */
        data class Ok(
            /** The Chat Completions URL to call. */
            val chatUrl: String,
            /** The model-list URL, for the "Refresh" action. */
            val modelsUrl: String,
            /** The host, with its port when the address names one. */
            val host: String,
            /** What the user sees: the host and the port. */
            val label: String,
            /** Whether the address is https. */
            val https: Boolean,
            /** Whether the host is on the user's own network. */
            val local: Boolean,
        ) : Parsed

        /** An address that cannot be used, and why. */
        data class Bad(val problem: Problem, val host: String = "") : Parsed
    }

    /** The path the OpenAI-compatible request goes to. */
    const val COMPLETIONS_PATH = "/chat/completions"

    /** The path the model list is at. */
    const val MODELS_PATH = "/models"

    /** Host names that always mean the local network. */
    private val LOCAL_SUFFIXES = listOf(
        ".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".localhost",
    )

    /**
     * Reads the address the user typed.
     *
     * An address without a scheme is taken as `https`, unless the host is on the local network, in which case it is
     * taken as `http` — so `192.168.0.15:8888/v1` works without typing a scheme, and `api.example.com/v1` stays
     * https.
     */
    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Parsed.Bad(Problem.EMPTY)

        // No scheme: try as https, and as http when the host is local.
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return Parsed.Bad(Problem.NOT_A_URL)

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Parsed.Bad(Problem.NOT_A_URL)
        if (scheme != "http" && scheme != "https") return Parsed.Bad(Problem.SCHEME)
        // Credentials in the URL: the key has its own field, and a URL with a password leaks into logs.
        if (uri.userInfo != null) return Parsed.Bad(Problem.CREDENTIALS)
        // A host that looks like an address but is not one (192.168.0.256) is refused as a public address, not as a
        // broken one: it is an address the user typed, and the rule that matters is that plain http is local only.
        val host = uri.host?.takeIf { it.isNotEmpty() }
            ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':')?.takeIf { it.isNotEmpty() }
            ?: return Parsed.Bad(Problem.NOT_A_URL)

        val local = isLocalHost(host)
        // Plain http is only for the local network.
        if (scheme == "http" && !local) return Parsed.Bad(Problem.HTTP_NOT_LOCAL, host)

        // A scheme-less address to a local host is plain http; everything else is https.
        val effective = if (!trimmed.contains("://") && local) "http" else scheme

        val authority = uri.rawAuthority.orEmpty()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        // The completions address the user pasted is the base plus the path the engine adds itself.
        val base = if (path.endsWith(COMPLETIONS_PATH, ignoreCase = true)) {
            path.dropLast(COMPLETIONS_PATH.length).trimEnd('/')
        } else {
            path
        }
        val origin = effective + "://" + authority
        return Parsed.Ok(
            chatUrl = origin + base + COMPLETIONS_PATH,
            modelsUrl = origin + base + MODELS_PATH,
            host = host,
            label = authority,
            https = effective == "https",
            local = local,
        )
    }

    /**
     * Whether [host] is on the user's own network: loopback, a private range, a link-local or unique-local address, a
     * `.local`-style name, or a single-label name such as `mypc`.
     *
     * The decision is made from the name or address alone — no DNS lookup. A name that resolves to a public address is
     * therefore not local; the user needs https or a VPN for that.
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase(Locale.ROOT).trimEnd('.')
        if (h.isEmpty()) return false
        // A literal IPv6 address.
        if (':' in h) return ipv6Local(h)
        // A literal IPv4 address.
        ipv4(h)?.let { return ipv4Local(it[0], it[1], it[2], it[3]) }
        // Shaped like an IPv4 address but not one: not a local address.
        if (h.all { it.isDigit() || it == '.' }) return false
        // A single-label name: a machine on the local network.
        if ('.' !in h) return true
        return LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /** The four parts of an IPv4 address, or null when [h] is not one. */
    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /** Whether an IPv4 address is on the local network. */
    private fun ipv4Local(a: Int, b: Int, c: Int, d: Int): Boolean =
        a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)

    /**
     * Whether an IPv6 literal is on the local network. A literal is never resolved, so no DNS happens here; an
     * IPv4-mapped address is judged by its IPv4 bytes.
     */
    private fun ipv6Local(h: String): Boolean {
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return when (addr) {
            is Inet4Address -> ipv4Local(
                addr.address[0].toInt() and 0xFF,
                addr.address[1].toInt() and 0xFF,
                addr.address[2].toInt() and 0xFF,
                addr.address[3].toInt() and 0xFF,
            )
            is Inet6Address -> {
                val bytes = addr.address
                if (bytes.size == 16) {
                    // ::1 and the other IPv4-mapped addresses (::ffff:a.b.c.d) come back as an IPv6 address whose
                    // last four bytes are the IPv4 address.
                    val mapped = bytes.takeLast(4)
                    val isMapped = mapped.all { it == 0.toByte() } || bytes[10].toInt() == 0xFF.toInt() && bytes[11].toInt() == 0xFF.toInt()
                    if (isMapped && mapped.any { it != 0.toByte() }) {
                        return ipv4Local(
                            mapped[0].toInt() and 0xFF,
                            mapped[1].toInt() and 0xFF,
                            mapped[2].toInt() and 0xFF,
                            mapped[3].toInt() and 0xFF,
                        )
                    }
                    // Loopback: ::1.
                    if (bytes.dropLast(1).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true
                    // Link-local (fe80::/10).
                    if ((bytes[0].toInt() and 0xFE) == 0xFE) return true
                    // Site-local (fec0::/10).
                    if ((bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0xC0) return true
                    // Unique local (fc00::/7).
                    if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
                    false
                } else {
                    addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
                }
            }
            else -> addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        }
    }
}
