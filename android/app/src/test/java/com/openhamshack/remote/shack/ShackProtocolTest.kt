package com.openhamshack.remote.shack

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The controller and NB/Radio wire parsing, against real answers recorded
 * on 9 October 2026 from `python3 -m ohsnb --server` and `python3 -m ohsradio
 * --server` (openhamshack-nb main ebe6a94, simulator) behind tools/fakeshack.py:
 * the well-known document, both apps' status, NB's events (hello, a state
 * change, a wide and a zoom frame) and the receive audio's headers.
 */
class ShackProtocolTest {
    private fun text(name: String) = javaClass.classLoader!!.getResourceAsStream("shack/$name")!!.bufferedReader().readText()
    private fun json(name: String) = JSONObject(text(name))

    // --- kind selection -------------------------------------------------

    @Test fun kindIsChosenByTheExactWireName() {
        assertEquals(Kind.QO100NB, Kind.of("qo100nb"))
        assertEquals(Kind.RADIO, Kind.of("radio"))
        assertEquals(Kind.QO100WB, Kind.of("qo100wb"))
        assertEquals(Kind.DATV, Kind.of("datv"))
        assertEquals(Kind.ICOM, Kind.of("icom"))
        assertEquals(Kind.FLEX, Kind.of("flex"))
        // Not guessed from anything close: an unknown kind opens nothing.
        for (k in listOf("yaesu", "FLEX", "QO100NB", "qo100", "DATV", "", null)) assertEquals(k, Kind.UNSUPPORTED, Kind.of(k))
        assertTrue(Kind.QO100NB.isEngine && Kind.RADIO.isEngine)
        assertFalse(Kind.QO100WB.isEngine || Kind.DATV.isEngine || Kind.UNSUPPORTED.isEngine)
        // What this build opens: every kind it knows, Icom and Flex included; never an unknown one.
        assertEquals(setOf(Kind.QO100NB, Kind.RADIO, Kind.QO100WB, Kind.DATV, Kind.ICOM, Kind.FLEX), Kind.entries.filter { it.opens }.toSet())
    }

    @Test fun theFiveAppControllerListsEveryAppAsOpenable() {
        // Recorded from tools/fakeshack.py with QO-100 WB, DATV and Icom
        // behind it (NB and Radio installed but stopped).
        val wk = WellKnown.parse(text("shack-wellknown-m4.json"))!!
        assertEquals(listOf("shack-datv", "shack-icom", "shack-qo100nb", "shack-qo100wb", "shack-radio"), wk.apps.map { it.id })
        assertEquals(wk.apps, wk.openable)
        val wb = wk.apps.single { it.kind == Kind.QO100WB }
        assertEquals("shack-qo100wb/", wb.base)
        // D3: QO-100 WB may be watched anonymously, DATV not.
        assertTrue(wb.anonymousView)
        assertFalse(wk.apps.single { it.kind == Kind.DATV }.anonymousView)
        assertFalse(wk.apps.single { it.kind == Kind.QO100NB }.running)
    }

    @Test fun icomAndFlexAreListedAndOpenable() {
        // Recorded from tools/fakeshack.py with fakeflow (Icom) and the real
        // Flex module on fakeflex behind it, the others stopped.
        val wk = WellKnown.parse(text("shack-wellknown-icom-flex.json"))!!
        val icom = wk.apps.single { it.id == "shack-icom" }
        val flex = wk.apps.single { it.id == "shack-flex" }
        assertEquals(Kind.ICOM to "shack-icom/", icom.kind to icom.base)
        assertEquals(Kind.FLEX to "shack-flex/", flex.kind to flex.base)
        assertTrue(icom.running && flex.running && flex.txCapable)
        assertTrue(icom in wk.openable && flex in wk.openable)
    }

    @Test fun wellKnownListsBothAppsWithTheirKinds() {
        val wk = WellKnown.parse(text("shack-wellknown.json"))!!
        assertEquals("FAKESHACK", wk.name)
        assertEquals(listOf("shack-qo100nb" to Kind.QO100NB, "shack-radio" to Kind.RADIO), wk.apps.map { it.id to it.kind })
        assertEquals(listOf("shack-qo100nb/", "shack-radio/"), wk.apps.map { it.base })
        assertEquals(wk.apps, wk.openable)
    }

    @Test fun unsupportedAndUnsafeAppsAreNotOpenable() {
        val doc = JSONObject().put("openhamshack", 1).put("controllerId", "c").put("apps", org.json.JSONArray()
            .put(JSONObject().put("id", "shack-yaesu").put("kind", "yaesu").put("base", "shack-yaesu/"))
            .put(JSONObject().put("id", "evil").put("kind", "radio").put("base", "http://elsewhere/"))
            .put(JSONObject().put("id", "up").put("kind", "radio").put("base", "../x/"))
            .put(JSONObject().put("id", "shack-radio").put("kind", "radio").put("base", "shack-radio")))
        val wk = WellKnown.parse(doc.toString())!!
        // An absolute or climbing base is dropped; a missing slash is added.
        assertEquals(listOf("shack-yaesu", "shack-radio"), wk.apps.map { it.id })
        assertEquals("shack-radio/", wk.apps[1].base)
        assertEquals(listOf("shack-radio"), wk.openable.map { it.id })
    }

    @Test fun aClassicBoxOrAnyOtherDocumentIsNotAController() {
        assertNull(WellKnown.parse("""{"config": {}, "receivers": []}"""))
        assertNull(WellKnown.parse("<html>"))
    }

    @Test fun aNewerContractIsRefusedNotGuessed() {
        try {
            WellKnown.parse("""{"openhamshack": 2, "apps": []}""")
            fail("contract 2 accepted")
        } catch (e: NewerControllerException) {
            assertEquals(2, e.major)
        }
    }

    // --- status ---------------------------------------------------------

    @Test fun nbStatusHasTheLease() {
        val st = EngineStatus.parse(json("nb-status.json"))
        assertEquals(Kind.QO100NB, st.kind)
        assertTrue(st.running)
        assertFalse(st.onAir)
        assertTrue("tx.remote present", st.leaseSupported)
        assertNull(st.leaseHolder)
        assertTrue(st.simulator)
    }

    @Test fun radioStatusSaysWhyTransmitIsLocked() {
        val st = EngineStatus.parse(json("radio-status.json"))
        assertEquals(Kind.RADIO, st.kind)
        assertTrue(st.txBlocked.isNotBlank())
        assertTrue(st.leaseSupported)
    }

    @Test fun aServerWithoutTxRemoteHasNoLease() {
        val o = json("nb-status.json")
        o.getJSONObject("tx").remove("remote")
        assertFalse(EngineStatus.parse(o).leaseSupported)
        o.remove("tx")
        assertFalse(EngineStatus.parse(o).leaseSupported)
        // A holder named while keyed.
        val keyed = json("nb-status.json").put("onAir", true)
        keyed.getJSONObject("tx").put("remote", "android-1")
        assertEquals("android-1", EngineStatus.parse(keyed).leaseHolder)
    }

    // --- events and spectra ---------------------------------------------

    @Test fun wideFrameDecodesToTheTransponderWithTheBeaconStrongest() {
        val e = EngineEvent.parse(text("nb-event-wide.json")) as EngineEvent.Spectrum
        val f = e.frame
        assertEquals("wide", f.kind)
        assertEquals(2064, f.db.size)
        assertEquals(-88.76f, f.db[0], 0.001f)
        assertEquals(-82.95f, f.db.last(), 0.001f)
        // The simulator's lower beacon, 10 489.500 MHz, is the strongest bin.
        val peak = f.db.indices.maxByOrNull { f.db[it] }!!
        assertEquals(10_489_500_000.0, f.startHz + peak * f.binHz, f.binHz)
        assertEquals(10_490_025_000.0, f.endHz, 1_000.0)
    }

    @Test fun zoomFrameIsAroundTheDial() {
        val f = (EngineEvent.parse(text("nb-event-zoom.json")) as EngineEvent.Spectrum).frame
        assertEquals("zoom", f.kind)
        assertEquals(1024, f.db.size)
        assertTrue(10_489_700_000.0 in f.startHz..f.endHz)
    }

    @Test fun centiDbAreSignedLittleEndian() {
        val bytes = byteArrayOf(0x30, 0xF8.toByte(), 0x64, 0x00)   // -2000, 100
        val o = JSONObject().put("kind", "zoom").put("startHz", 1000.0).put("binHz", 10.0).put("n", 2)
            .put("cdb", Base64.getEncoder().encodeToString(bytes))
        val f = SpectrumFrame.parse(o)!!
        assertEquals(-20f, f.db[0], 0f)
        assertEquals(1f, f.db[1], 0f)
    }

    @Test fun aFrameWhoseCountDisagreesIsDropped() {
        val o = json("nb-event-zoom.json").put("n", 1000)
        assertNull(SpectrumFrame.parse(o))
        assertNull(SpectrumFrame.parse(json("nb-event-zoom.json").put("binHz", 0)))
        assertNull(SpectrumFrame.parse(json("nb-event-zoom.json").put("cdb", "not base64!")))
    }

    @Test fun helloCarriesStatusAndStateCarriesChanges() {
        val hello = EngineEvent.parse(text("nb-event-hello.json")) as EngineEvent.Hello
        assertEquals(Kind.QO100NB, hello.status.kind)
        val st = EngineEvent.parse(text("nb-event-state.json")) as EngineEvent.State
        assertTrue(st.changes.has("cpu"))
        assertEquals(EngineEvent.Other, EngineEvent.parse("""{"kind": "skim", "x": 1}"""))
        assertEquals(EngineEvent.Other, EngineEvent.parse("not json"))
        val m = EngineEvent.parse("""{"kind": "meter", "rx": -66.6, "beacon": null}""") as EngineEvent.Meter
        assertEquals(-66.6, m.rxDb!!, 1e-9)
        assertNull(m.beaconDb)
    }

    @Test fun stateMergesChangesOverTheFullState() {
        val s0 = EngineState(JSONObject().put("vfo", 10_489_700_000.0).put("mode", "usb").put("rxLock", "searching"))
        val s1 = s0.merge(JSONObject().put("rxLock", "locked").put("rxSnr", 31.8))
        assertEquals(BeaconLock.SEARCHING, s0.beaconLock)
        assertEquals(BeaconLock.LOCKED, s1.beaconLock)
        assertEquals(10_489_700_000.0, s1.dialHz!!, 0.0)
        assertEquals("usb", s1.mode)
        assertEquals(31.8, s1.rxSnr!!, 1e-9)
        // Merging never changes the state it came from.
        assertNull(s0.rxSnr)
    }

    @Test fun beaconLockStates() {
        assertEquals(BeaconLock.LOCKED, BeaconLock.of("locked"))
        assertEquals(BeaconLock.HELD, BeaconLock.of("hold"))
        assertEquals(BeaconLock.HELD, BeaconLock.of("manual"))
        assertEquals(BeaconLock.SEARCHING, BeaconLock.of("searching"))
        assertEquals(BeaconLock.SEARCHING, BeaconLock.of("lost"))
        assertEquals(BeaconLock.UNKNOWN, BeaconLock.of(null))
        // Radio has no beacon: no rxLock key, nothing shown.
        assertEquals(BeaconLock.UNKNOWN, EngineState(JSONObject().put("band", "2m")).beaconLock)
    }

    // --- receive audio ----------------------------------------------------

    @Test fun pcmFormatFromTheRecordedHeaders() {
        val h = text("nb-audio-headers.txt").lines().mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
        assertEquals(PcmFormat(12000, 1), PcmFormat.parse(h["Content-Type"], h["X-Audio-Format"]))
    }

    @Test fun pcmFormatFallsBackToContentTypeAndRefusesOthers() {
        assertEquals(PcmFormat(8000, 1), PcmFormat.parse("audio/L16; rate=8000; channels=1", null))
        assertEquals(PcmFormat(48000, 2), PcmFormat.parse("audio/L16;rate=48000;channels=2", "not json"))
        assertNull(PcmFormat.parse("audio/L16; rate=12000", """{"encoding": "f32le", "rate": 12000}"""))
        assertNull(PcmFormat.parse("audio/mpeg", null))
        assertNull(PcmFormat.parse("audio/L16; rate=0", null))
        assertNull(PcmFormat.parse(null, null))
    }

    @Test fun radiosFortyEightKilohertzHeadersAreBelievedAsSent() {
        // openhamshack-nb 78ec61f get_audio_rx, mono and with ?channels=2.
        assertEquals(PcmFormat(48000, 1), PcmFormat.parse("audio/L16; rate=48000; channels=1",
            """{"encoding": "s16le", "rate": 48000, "channels": 1}"""))
        assertEquals(PcmFormat(48000, 2), PcmFormat.parse("audio/L16; rate=48000; channels=2",
            """{"encoding": "s16le", "rate": 48000, "channels": 2}"""))
        // X-Audio-Format wins over a Content-Type that disagrees with it.
        assertEquals(PcmFormat(48000, 2), PcmFormat.parse("audio/L16; rate=12000; channels=1",
            """{"encoding": "s16le", "rate": 48000, "channels": 2}"""))
        // More channels than a track can take is refused, not played as noise.
        assertNull(PcmFormat.parse("audio/L16; rate=48000; channels=6", null))
    }

    @Test fun pcmSamplesAreLittleEndianAndStereoStaysInterleaved() {
        assertEquals(listOf<Short>(1, -1), pcm16le(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())).toList())
        // L=100, R=-2, L=3, R=4: the player gets both sides, in order, not an average.
        val lr = byteArrayOf(0x64, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x03, 0x00, 0x04, 0x00)
        assertEquals(listOf<Short>(100, -2, 3, 4), pcm16le(lr, 0, 8).toList())
        // Only the bytes asked for: a torn frame's tail waits for the next read.
        assertEquals(listOf<Short>(100, -2), pcm16le(lr, 0, 4).toList())
    }

    // --- stereo or mono ---------------------------------------------------------

    private val newRadioAudio = JSONObject("""{"encoding": "s16le", "rate": 48000, "channels": 1, "stereo": true,
        "listeners": 1, "tx": {"encoding": "s16le", "rate": 12000, "channels": 1}}""")

    @Test fun statusAudioOfTheFortyEightKilohertzServer() {
        val a = EngineStatus.parse(json("radio-status.json").put("audio", newRadioAudio)).audio!!
        assertEquals(EngineAudio(rate = 48000, channels = 1, stereo = true, channelsKnown = true), a)
        val off = EngineStatus.parse(json("radio-status.json").put("audio", JSONObject(newRadioAudio.toString()).put("stereo", false)))
        assertFalse(off.audio!!.stereo)
        assertTrue(off.audio!!.channelsKnown)
    }

    @Test fun statusAudioOfAnOlderServerIsNotTheNewShape() {
        // Recorded 09/10/2026: `channels` was already there, `stereo` was not.
        val old = EngineStatus.parse(json("radio-status.json")).audio!!
        assertEquals(12000, old.rate)
        assertFalse(old.channelsKnown)
        assertFalse(old.stereo)
        assertNull(EngineStatus.parse(json("radio-status.json").apply { remove("audio") }).audio)
    }

    @Test fun stereoIsAskedOnlyOfARadioThatUnderstandsIt() {
        val new = EngineAudio.parse(newRadioAudio)
        val old = EngineStatus.parse(json("radio-status.json")).audio
        assertEquals(2, audioChannelsToRequest(Kind.RADIO, new, refused = false))
        // Asked for whether or not the pilot is locked now: the format holds for the whole response.
        assertEquals(2, audioChannelsToRequest(Kind.RADIO, new.copy(stereo = false), refused = false))
        assertNull(audioChannelsToRequest(Kind.RADIO, old, refused = false))
        assertNull(audioChannelsToRequest(Kind.RADIO, null, refused = false))
        assertNull("refused once, mono for the session", audioChannelsToRequest(Kind.RADIO, new, refused = true))
        // NB runs the same server but has no stereo to give.
        assertNull(audioChannelsToRequest(Kind.QO100NB, new, refused = false))
    }

    // --- pairing ------------------------------------------------------------

    @Test fun pairPollAnswers() {
        assertEquals(PairPoll.Pending, PairPoll.parse(JSONObject().put("state", "pending")))
        assertEquals(PairPoll.Denied, PairPoll.parse(JSONObject().put("state", "denied")))
        assertEquals(PairPoll.Approved("shackdev_abc", "control"),
            PairPoll.parse(JSONObject().put("state", "approved").put("token", "shackdev_abc").put("maxLevel", "control")))
        // Approved without a device token is not a sign-in.
        assertEquals(PairPoll.Denied, PairPoll.parse(JSONObject().put("state", "approved").put("token", "x")))
    }

    // --- logbook --------------------------------------------------------------

    @Test fun logReadsEachAppsFrequencyKeyNewestFirst() {
        // As answered by POST api/log on each simulator server (dl on NB, rf on Radio).
        val o = JSONObject("""{"qsos": [
            {"call":"G0TST","t":1791572322.788,"dl":10489600300.0,"mode":"USB","rstSent":"59","rstRcvd":"57","grid":"IO91"},
            {"call":"M0ABC","t":1791572399.0,"rf":433500000.0,"mode":"FM","rstSent":"59","rstRcvd":"59"}]}""")
        val log = Qso.parseLog(o)
        assertEquals(listOf("M0ABC", "G0TST"), log.map { it.call })
        assertEquals(433_500_000.0, log[0].freqHz!!, 0.0)
        assertEquals(10_489_600_300.0, log[1].freqHz!!, 0.0)
        assertEquals("IO91", log[1].grid)
        assertEquals(emptyList<Qso>(), Qso.parseLog(JSONObject()))
    }
}
