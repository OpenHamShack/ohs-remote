package com.openhamshack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.openhamshack.core.net.BoxAddress

class BoxAddressTest {
    @Test fun `a Dock360 path-prefixed https address keeps its path`() {
        val a = BoxAddress.parse("https://198.51.100.208/icom/")
        assertEquals(BoxAddress("198.51.100.208", 443, "/icom", https = true), a)
        assertEquals("https://198.51.100.208/icom/api/state", a.url("api/state"))
        assertEquals("wss://198.51.100.208/icom/api/ws", a.wsUrl("api/ws"))
        assertEquals("https://198.51.100.208/icom", a.display)
    }

    @Test fun `a bare address is plain http on the kind's default port`() {
        assertEquals(BoxAddress("198.51.100.40", 8080), BoxAddress.parse("198.51.100.40", defaultPort = 8080))
        val a = BoxAddress.parse("icom.shack")
        assertEquals("http://icom.shack/api/state", a.url("/api/state"))
    }

    @Test fun `ports, paths and pasted page addresses`() {
        val a = BoxAddress.parse("http://10.0.2.2:18300/icom/login?bad=1")
        assertEquals(BoxAddress("10.0.2.2", 18300, "/icom"), a)
        assertEquals("ws://10.0.2.2:18300/icom/api/audio/rx", a.wsUrl("api/audio/rx"))
        assertEquals("10.0.2.2:18300/icom", a.display)
        assertEquals(BoxAddress("shack.tail547521.ts.net", 443, "/app/icom", true),
            BoxAddress.parse("https://shack.tail547521.ts.net/app/icom/"))
    }

    @Test fun `the display form parses back to the same address`() {
        for (s in listOf("https://198.51.100.208/icom", "10.0.2.2:18343/icom", "https://h:8443/a/b", "[fd7a::1]:80/x")) {
            val a = BoxAddress.parse(s)
            assertEquals(a, BoxAddress.parse(a.display))
        }
    }

    @Test fun `IPv6 is bracketed in URLs`() {
        val a = BoxAddress.parse("[fd7a:115c::5]:18300/icom")
        assertEquals("http://[fd7a:115c::5]:18300/icom/api/state", a.url("api/state"))
    }

    @Test fun `nonsense is refused with a reason`() {
        for (bad in listOf("", "ftp://x", "a b", "host:99999", "host/../etc", "user@host")) {
            assertThrows(bad, IllegalArgumentException::class.java) { BoxAddress.parse(bad) }
        }
    }
}
