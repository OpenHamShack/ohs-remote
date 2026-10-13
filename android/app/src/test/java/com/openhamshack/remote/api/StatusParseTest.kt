package com.openhamshack.remote.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against real answers: the fixtures are what openhamshack.py's own Handler sent
 * (tools/fakebox.py: OpenHamShack 0.9.16 with the lease and larix fields
 * of docs/REMOTE-API.md added as the box after 0.9.16 sends them), so a field renamed on the box side
 * fails here rather than as a blank on a phone. Refresh them with the
 * commands in docs/TESTING.md when the box's status changes shape.
 */
class StatusParseTest {
    private fun fixture(name: String) =
        JSONObject(javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText())

    @Test
    fun aViewOnlyBrowsersStatus() {
        val st = Status.parse(fixture("status-viewonly.json"))
        assertTrue(st.authReported)
        assertFalse(st.auth.authorised)
        assertTrue(st.auth.pinSet)
        assertTrue(st.auth.viewOnly)
        assertEquals("View only: enter the PIN to change this.", st.auth.viewOnlyReason)
        assertFalse(st.onAir)
        assertNull(st.txTimeout)
        assertEquals("M0TST", st.config.callsign)
        assertEquals(437.0, st.config.frequencyMHz!!, 0.0)
        assertEquals(9750.0, st.config.qo100LnbMHz, 0.0)
        assertEquals("srt", st.config.streamProtocol)
        assertEquals(true, st.rx.locked)
        assertNotNull(st.rx.mer)
        assertEquals("QPSK 2/3", st.rx.modcod)
        assertEquals(1280, st.rx.video!!.width)
        assertEquals(437.0, st.rx.frequencyMHz!!, 0.0)
        assertEquals(333.0, st.rx.symbolRateKs!!, 0.0)
        assertEquals(1, st.receivers.size)
        assertTrue(st.receivers[0].heard)
        assertEquals(19710, st.stream!!.port)
        assertEquals(200, st.stream.latencyMs)
        assertTrue(st.stream.secretSet)
        assertTrue(st.version.isNotBlank())
        assertTrue("tx.remote is present (null): the box has the lease", st.leaseSupported)
        assertNull(st.lease)
    }

    @Test
    fun aKeyUpsAnswerCarriesAuthTheLeaseAndATimeout() {
        val st = Status.parse(fixture("status-onair.json"))
        assertTrue("the box now sends auth in POST answers too", st.authReported)
        assertTrue(st.auth.authorised)
        assertTrue(st.onAir)
        assertEquals("", st.txError)
        assertEquals(10, st.txTimeout!!.minutes)
        assertFalse(st.txTimeout.warning)
        assertEquals("android-fixture", st.lease!!.id)
        assertEquals(5.0, st.lease.leaseSeconds!!, 0.0)
    }

    @Test
    fun anOlderBoxsPostAnswerWithoutAuthIsMarkedSo() {
        val o = fixture("status-onair.json")
        o.remove("auth")
        assertFalse(Status.parse(o).authReported)
    }

    @Test
    fun aTuneOnAirSaysWhatWaitsForTheKeyUpAndWhyTheBoxWillRefuse() {
        val st = Status.parse(fixture("status-txtune-onair.json"))
        assertEquals(listOf("frequencyMHz"), st.txTune!!.nextKeyUp)
        assertEquals(setOf("frequencyMHz", "gainDb"), st.txTune.changed.keys)
        assertEquals("146.95 MHz is outside the amateur bands", st.txBlocked)
        assertNull("a status poll has no txTune", Status.parse(fixture("status-viewonly.json")).txTune)
    }

    @Test
    fun theTransmitChoicesAreTheBoxs() {
        val o = TxOptions.parse(fixture("tx-options.json"))
        assertEquals(listOf("dvbs2", "dvbs"), o.modes)
        assertEquals(listOf("qpsk"), o.constellations["dvbs"])
        assertEquals(listOf("3/5", "2/3", "3/4", "5/6", "8/9", "9/10"), o.fecsFor("dvbs2", "8psk"))
        assertEquals(listOf("1/2", "2/3", "3/4", "5/6", "7/8"), o.fecsFor("dvbs", "qpsk"))
        assertEquals(-70, o.gainMin)
        assertEquals(0, o.gainMax)
        assertTrue(o.isQo100Uplink(2405.25))
        assertFalse(o.isQo100Uplink(437.0))
        assertEquals(o.srQo100, o.symbolRatesFor(2405.25))
        assertEquals(o.srTerrestrial, o.symbolRatesFor(1255.0))
        assertTrue(o.qo100Channels[333]!!.isNotEmpty())
        assertTrue("preferred channels first", o.qo100Channels[333]!!.first().preferred || o.qo100Channels[333]!!.none { it.preferred })
        assertEquals("scotland", o.licenceRegion)
    }

    @Test
    fun theLicenceHintFollowsTheBoxsEdgeRule() {
        val o = TxOptions.parse(fixture("tx-options.json"))   // Scotland, with the NoV: 2 m to 146.9375
        assertFalse(o.outsideLicence(437.0, 333.0))
        assertTrue(o.outsideLicence(146.95, 333.0))
        assertFalse(o.outsideLicence(146.5, 333.0))
        // 2 MS at 145.0 is 2.7 MHz wide and runs off the bottom of 2 m.
        assertTrue(o.outsideLicence(145.0, 2000.0))
        val none = TxOptions.parse(fixture("tx-options.json").apply { getJSONObject("licence").put("bands", JSONObject.NULL) })
        assertFalse("no band check (region other)", none.outsideLicence(146.95, 333.0))
    }

    @Test
    fun whatTheStreamSourceReceivesIsDescribedInWords() {
        // As status.stream reported a phone sending from the emulator.
        val st = Status.parse(JSONObject("""{"stream":{"protocol":"srt","port":19710,"state":"receiving",
            "video":{"codec":"h264","profile":"Main","width":1280,"height":720,"fps":null},
            "audio":{"codec":"aac","rate":44100,"channels":"stereo"},"kbps":251}}"""))
        assertEquals("H264 1280×720", st.stream!!.video)
        assertEquals("AAC stereo", st.stream.audio)
        assertEquals(251.0, st.stream.kbps!!, 0.0)
    }

    @Test
    fun aBoxWithoutTheLeaseIsRecognised() {
        val o = fixture("status-viewonly.json")
        o.getJSONObject("tx").remove("remote")
        assertFalse(Status.parse(o).leaseSupported)
    }

    @Test
    fun nullsStayUnknownRatherThanZero() {
        val st = Status.parse(JSONObject("""{"onAir":false,"rx":{"locked":null,"label":"PicoTuner (no status yet)","mer":null},
            "config":{"callsign":"G0ABC","frequencyMHz":null}}"""))
        assertNull(st.rx.locked)
        assertNull(st.rx.mer)
        assertNull(st.config.frequencyMHz)
        assertEquals("PicoTuner (no status yet)", st.rx.label)
    }

    @Test
    fun theLarixLinkGivesUpItsPassphraseOnlyWhenTheBoxIncludedIt() {
        val withPin = LarixInfo.parse(fixture("larix-authorised.json"))
        assertTrue(withPin.secretIncluded)
        assertEquals("fakebox-passphrase", withPin.srtPassphrase)
        assertEquals(19710, withPin.port)
        assertEquals(200, withPin.latencyMs)
        // 0.9.16 had the passphrase only inside the Larix link.
        val old = LarixInfo.parse(fixture("larix-0.9.16-authorised.json"))
        assertNull(old.port)
        assertEquals("fakebox-passphrase", old.srtPassphrase)
        val without = LarixInfo.parse(fixture("larix-viewonly.json"))
        assertTrue(without.secretSet)
        assertFalse(without.secretIncluded)
        assertNull(without.srtPassphrase)
    }

    @Test
    fun theSessionCookieIsReadFromSetCookie() {
        assertEquals("abc123", OpenHamShackClient.sessionFromCookie("ohssession=abc123; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"))
        assertNull(OpenHamShackClient.sessionFromCookie("other=1; Path=/"))
        assertNull(OpenHamShackClient.sessionFromCookie("ohssession=; Path=/"))
    }
}
