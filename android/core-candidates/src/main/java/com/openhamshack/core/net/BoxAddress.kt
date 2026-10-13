package com.openhamshack.core.net

/**
 * Where a box is: scheme, host, port and the path it is published under -
 * and nothing else. Every URL a kind opens is built from this one value; an
 * address a box reports about itself is never turned into a URL, because the
 * address that works is the one the operator used (LAN, tailnet, a name).
 *
 * OpenHamShack Remote's BoxAddress generalised. The path is the addition: a
 * Dock360 module such as StationFlow is served both at the root of a
 * subdomain (`http://icom.shack/`) and under a path prefix on a plain
 * address (`http://198.51.100.208/icom/`), and the module itself is
 * prefix-agnostic - so the address keeps whatever path was typed and API
 * paths go after it. A OpenHamShack has no path and loses nothing.
 *
 * HTTPS is accepted (a subdomain may have a certificate) but never assumed.
 * Without a port, [parse] uses the kind's default (8080 for a OpenHamShack, 80
 * for a Dock360 module).
 */
data class BoxAddress(
    val host: String,
    val port: Int = 80,
    val path: String = "",
    val https: Boolean = false,
) {
    val urlHost: String get() = if (host.contains(':')) "[$host]" else host

    private val schemePort: Int get() = if (https) 443 else 80

    /** scheme://host[:port][/path], no trailing slash; API paths are appended. */
    val baseUrl: String
        get() = (if (https) "https" else "http") + "://" + urlHost +
            (if (port == schemePort) "" else ":$port") + path

    /** websocket form of [baseUrl]. */
    val wsBaseUrl: String get() = "ws" + baseUrl.substringAfter("http")   // http:// -> ws://, https:// -> wss://

    /** What to show, and what typing back in gives the same address. */
    val display: String
        get() = (if (https) "https://" else "") + urlHost + (if (port == schemePort) "" else ":$port") + path

    fun url(apiPath: String): String = baseUrl + "/" + apiPath.trimStart('/')
    fun wsUrl(apiPath: String): String = wsBaseUrl + "/" + apiPath.trimStart('/')

    override fun toString() = display

    companion object {
        private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")
        private val SEGMENT = Regex("^[A-Za-z0-9._~-]+$")

        /** Parse what the operator typed, or throw [IllegalArgumentException] saying why not. */
        fun parse(input: String, defaultPort: Int = 80): BoxAddress {
            var s = input.trim()
            require(s.isNotEmpty()) { "Type the address, for example 198.51.100.40, 198.51.100.208/icom or icom.shack" }
            var https = false
            when {
                s.startsWith("https://", ignoreCase = true) -> { https = true; s = s.substring(8) }
                s.startsWith("http://", ignoreCase = true) -> s = s.substring(7)
                s.contains("://") -> throw IllegalArgumentException("Only http:// and https:// addresses")
            }
            val slash = s.indexOf('/')
            val hostPort = if (slash >= 0) s.substring(0, slash) else s
            var path = if (slash >= 0) s.substring(slash) else ""
            // A pasted page address: the app needs the module's root, not its login page.
            path = path.substringBefore('?').substringBefore('#').trimEnd('/')
            for (suffix in listOf("/login", "/index.html")) if (path.endsWith(suffix)) path = path.removeSuffix(suffix)
            require(hostPort.none { it.isWhitespace() || it == '@' }) { "An address is host, host:port or host/path" }
            val segments = path.split('/').filter { it.isNotEmpty() }
            require(segments.all { SEGMENT.matches(it) && it != "." && it != ".." }) { "That path has characters an address cannot" }
            path = segments.joinToString("") { "/$it" }

            val host: String
            var port = if (https && defaultPort == 80) 443 else defaultPort
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                require(end > 1) { "An IPv6 address needs its closing ]" }
                host = hostPort.substring(1, end)
                val rest = hostPort.substring(end + 1)
                if (rest.isNotEmpty()) {
                    require(rest.startsWith(":")) { "Expected :port after ]" }
                    port = parsePort(rest.substring(1))
                }
                require(IPV6.matches(host)) { "Not an IPv6 address: $host" }
            } else if (hostPort.count { it == ':' } > 1) {
                require(IPV6.matches(hostPort)) { "Not an IPv6 address: $hostPort" }
                host = hostPort
            } else {
                val colon = hostPort.indexOf(':')
                host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                if (colon >= 0) port = parsePort(hostPort.substring(colon + 1))
                require(HOSTNAME.matches(host)) { "Not a host name or address: $host" }
            }
            return BoxAddress(host, port, path, https)
        }

        private fun parsePort(p: String): Int {
            val n = p.toIntOrNull()
            require(n != null && n in 1..65535) { "The port must be a number from 1 to 65535" }
            return n
        }
    }
}
