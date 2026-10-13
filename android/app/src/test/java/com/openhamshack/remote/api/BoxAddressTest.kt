package com.openhamshack.remote.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoxAddressTest {
    @Test
    fun aBareHostGetsTheConsolePort() {
        val a = BoxAddress.parse("198.51.100.40")
        assertEquals("198.51.100.40", a.host)
        assertEquals(8080, a.port)
        assertEquals("http://198.51.100.40:8080", a.baseUrl)
        assertEquals("198.51.100.40", a.display)
    }

    @Test
    fun hostPortNamesAndPastedUrlsAreForgiven() {
        assertEquals(BoxAddress("openhamshack.tail547521.ts.net", 18080), BoxAddress.parse(" openhamshack.tail547521.ts.net:18080 "))
        assertEquals(BoxAddress("workshop", 8080), BoxAddress.parse("http://workshop/"))
        assertEquals(BoxAddress("10.0.2.2", 18081), BoxAddress.parse("HTTP://10.0.2.2:18081"))
    }

    @Test
    fun ipv6LiteralsGoInBracketsInTheUrl() {
        val a = BoxAddress.parse("[fd7a:115c:a1e0::1]:8081")
        assertEquals("fd7a:115c:a1e0::1", a.host)
        assertEquals("http://[fd7a:115c:a1e0::1]:8081", a.baseUrl)
        assertEquals(BoxAddress("fd7a:115c:a1e0::1", 8080), BoxAddress.parse("fd7a:115c:a1e0::1"))
    }

    @Test
    fun anythingThatIsNotJustAnAddressIsRefusedWithAReason() {
        for (bad in listOf("", "https://box", "box/api/status", "user@box", "box:0", "box:99999", "box:http", "bo x",
                           "box?x=1", "[::1", "-box")) {
            val e = assertThrows(IllegalArgumentException::class.java) { BoxAddress.parse(bad) }
            assert(!e.message.isNullOrBlank()) { "no reason for '$bad'" }
        }
    }
}
