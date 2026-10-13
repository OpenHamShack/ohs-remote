package com.openhamshack.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.net.AuthState
import com.openhamshack.core.net.SessionCookie

class AuthTest {
    @Test fun `levels read leniently, unknown is view`() {
        assertEquals(AuthLevel.Control, AuthLevel.parse("control"))
        assertEquals(AuthLevel.Transmit, AuthLevel.parse("TRANSMIT"))
        assertEquals(AuthLevel.View, AuthLevel.parse("view"))
        assertEquals(AuthLevel.View, AuthLevel.parse("admin"))
        assertEquals(AuthLevel.View, AuthLevel.parse(null))
    }

    @Test fun `what a session may do`() {
        fun eff(json: String) = AuthState.parse(JSONObject(json)).effective
        assertEquals(AuthLevel.View, eff("""{"passwordSet":true,"authorised":false,"level":null}"""))
        assertEquals(AuthLevel.Control, eff("""{"passwordSet":true,"authorised":true,"level":"control"}"""))
        assertEquals(AuthLevel.View, eff("""{"passwordSet":true,"authorised":true,"level":"view"}"""))
        // No password: the box serves everything - but never hands out transmit.
        assertEquals(AuthLevel.Control, eff("""{"passwordSet":false,"authorised":true,"level":"transmit"}"""))
        // OpenHamShack names it a PIN.
        assertEquals(AuthLevel.View, eff("""{"pinSet":true,"authorised":false}"""))
    }

    @Test fun `the session is the named cookie from Set-Cookie`() {
        val h = listOf(
            "other=1; Path=/",
            "icom9700=v1.c.1823056590.9f2c; Path=/icom/; Max-Age=31536000; HttpOnly; SameSite=Lax",
        )
        assertEquals(SessionCookie("icom9700", "v1.c.1823056590.9f2c"), SessionCookie.fromSetCookie(h, setOf("icom9700")))
        assertEquals(SessionCookie("other", "1"), SessionCookie.fromSetCookie(h))
        assertNull(SessionCookie.fromSetCookie(listOf("icom9700=; Max-Age=0; Path=/icom/"), setOf("icom9700")))
        assertEquals("icom9700=abc", SessionCookie.parse("icom9700=abc")!!.header)
        assertNull(SessionCookie.parse("nonsense"))
    }
}
