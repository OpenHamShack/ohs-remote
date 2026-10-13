package com.openhamshack.remote.api

/**
 * Where a OpenHamShack is: the host and port the operator typed, and nothing else.
 *
 * Every URL the app opens - the console API, the receive stream, the SRT
 * stream to the box's Stream source - is built from this one value. The box
 * reports its own addresses (status.stream.urls, the Larix links), but those
 * are the LAN addresses it sees on its interfaces, and the phone may be
 * reaching it over the tailnet, through a NAT, or by a name; the address
 * that works is the one the operator used. So nothing the box says is ever
 * turned into a URL here.
 *
 * Plain HTTP only: the box has no certificate. A pasted "http://" and a
 * trailing slash are forgiven; "https://" is refused rather than silently
 * downgraded.
 */
data class BoxAddress(val host: String, val port: Int = DEFAULT_PORT) {

    /** The host as it goes into a URL: an IPv6 literal in brackets. */
    val urlHost: String get() = if (host.contains(':')) "[$host]" else host

    /** http://host:port with no trailing slash; API paths are appended to it. */
    val baseUrl: String get() = "http://$urlHost:$port"

    /** What the operator would type to get this back: host, or host:port. */
    val display: String get() = if (port == DEFAULT_PORT) urlHost else "$urlHost:$port"

    override fun toString() = display

    companion object {
        const val DEFAULT_PORT = 8080

        private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?$")

        /** Parse what the operator typed, or throw [IllegalArgumentException] saying why not. */
        fun parse(input: String): BoxAddress {
            var s = input.trim()
            require(s.isNotEmpty()) { "Type the box's address, for example 198.51.100.40 or openhamshack.local" }
            if (s.startsWith("https://", ignoreCase = true)) {
                throw IllegalArgumentException("OpenHamShack serves plain HTTP: use http:// or just the address")
            }
            if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
            s = s.trimEnd('/')
            require(s.none { it.isWhitespace() || it in "/@?#" }) { "An address is just host or host:port" }

            val host: String
            var port = DEFAULT_PORT
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                require(end > 1) { "An IPv6 address needs its closing ]" }
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                if (rest.isNotEmpty()) {
                    require(rest.startsWith(":")) { "Expected :port after ]" }
                    port = parsePort(rest.substring(1))
                }
                require(IPV6.matches(host)) { "Not an IPv6 address: $host" }
            } else if (s.count { it == ':' } > 1) {
                // Bare IPv6 without brackets: no way to tell a port from the
                // last group, so it is all address.
                require(IPV6.matches(s)) { "Not an IPv6 address: $s" }
                host = s
            } else {
                val colon = s.indexOf(':')
                host = if (colon >= 0) s.substring(0, colon) else s
                if (colon >= 0) port = parsePort(s.substring(colon + 1))
                require(HOSTNAME.matches(host)) { "Not a host name or address: $host" }
            }
            return BoxAddress(host, port)
        }

        private fun parsePort(p: String): Int {
            val n = p.toIntOrNull()
            require(n != null && n in 1..65535) { "The port must be a number from 1 to 65535" }
            return n
        }
    }
}
