package com.openhamshack.remote.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QO-100 WB as the store app on a controller: the same status as a Classic
 * box, with `auth` saying the platform's level instead of a PIN. Fixtures
 * recorded on 9 October 2026 through tools/fakeshack.py from fakebox.py in
 * platform mode (OpenHamShack main 39657e8, OPENHAMSHACK_PLATFORM=dock360): nobody
 * signed in (anonymous view), a TV's token (capped at Control) and a
 * phone's (Transmit).
 */
class PlatformStatusTest {
    private fun fixture(name: String) =
        Status.parse(JSONObject(javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()))

    @Test fun anonymousViewingIsViewOnlyAndCannotTransmit() {
        val a = fixture("wb-platform-anon.json").auth
        assertTrue(a.platform)
        assertEquals("view", a.level)
        assertEquals("-", a.user)
        assertTrue(a.viewOnly)
        assertTrue(a.txLocked)
        assertFalse(a.pinSet)
    }

    @Test fun aTvTokenTunesButIsLockedOutOfTransmit() {
        val st = fixture("wb-platform-tv.json")
        val a = st.auth
        assertEquals("control", a.level)
        assertEquals("brian", a.user)
        assertFalse("Control tunes", a.viewOnly)
        assertFalse(a.canTransmit)
        assertTrue("transmit settings need the Transmit level", a.txLocked)
        // The lease is there all the same: a phone at Transmit could key.
        assertTrue(st.leaseSupported)
    }

    @Test fun aPhoneAtTransmitMayKeyUnderTheLease() {
        val st = fixture("wb-platform-transmit.json")
        assertEquals("transmit", st.auth.level)
        assertTrue(st.auth.canTransmit)
        assertFalse(st.auth.txLocked)
        assertTrue(st.leaseSupported)
        assertFalse(st.onAir)
        // Receivers and tuning read exactly as on a Classic box.
        assertEquals(437.0, st.receivers.first().frequencyMHz!!, 0.0)
    }

    @Test fun aClassicBoxIsNeverPlatform() {
        val st = Status.parse(JSONObject(javaClass.classLoader!!.getResourceAsStream("status-viewonly.json")!!.bufferedReader().readText()))
        assertFalse(st.auth.platform)
        assertEquals("", st.auth.level)
        // On Classic the PIN decides transmit settings, as before.
        assertEquals(st.auth.viewOnly, st.auth.txLocked)
    }
}
