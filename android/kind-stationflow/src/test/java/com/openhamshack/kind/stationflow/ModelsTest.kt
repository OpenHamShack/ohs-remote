package com.openhamshack.kind.stationflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openhamshack.kind.stationflow.api.ExtPreampNote
import com.openhamshack.kind.stationflow.api.Heard
import com.openhamshack.kind.stationflow.api.PreampRequest
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.api.Spectrum
import com.openhamshack.kind.stationflow.api.SstvPicture
import com.openhamshack.kind.stationflow.api.objects

class ModelsTest {
    @Test fun `the contract's own example state`() {
        val s = RadioState.parse(fixture("tvapi-state-example.json"))
        assertEquals(144_174_000L, s.freqHz)
        assertEquals("USB-D", s.shownMode)
        assertEquals("FIL1", s.filter)
        // Raw 64 of the radio's 0-255, over 241 (S9+60) for the bar.
        assertEquals(64 / 241.0, s.smeter!!, 1e-9)
        assertEquals(-25.2, s.smeterDb!!, 1e-9)
        // The final contract has no auth block: the host's level applies.
        assertNull(s.auth)
        assertEquals(listOf("2m", "70cm", "23cm"), s.bands.map { it.name })
        assertTrue(s.connected)
        assertFalse(s.transmitting)
        assertEquals(12000, s.audioRate)
        // The receive front end (Icom 1.23.0-beta.5): what the radio says, and what its model has.
        assertEquals(1, s.preamp)
        assertEquals(0, s.att)
        assertEquals(listOf("P.AMP"), s.preamps)
        assertEquals(listOf(10), s.attenuators)
        assertEquals("P.AMP", s.preampName)
        assertEquals("OFF", s.attName)
        // The 9700's external preamp (Icom TV-API.md section 5, preamp_modes): the whole setting.
        assertEquals(listOf("OFF", "P.AMP", "EXT", "P.AMP+EXT"), s.preampModes)
        assertEquals(1, s.preampMode)
        assertEquals(false, s.preampExt)
        assertEquals(true, s.extPreampHere)
        assertTrue(s.hasExtPreampMenu && s.preampModesRefused.isEmpty())
    }

    private fun nine(mode: Int, refused: String = "{}", here: String = "true") = RadioState.parse(JSONObject(
        """{"selected":"r","radios":{"r":{"id":"r","preamp":${mode and 1},"preamp_ext":${mode and 2 != 0},
           "preamp_mode":$mode,"preamp_modes":["OFF","P.AMP","EXT","P.AMP+EXT"],
           "preamp_modes_refused":$refused,"ext_preamp_menu":{"144":true,"430":false,"1200":null},
           "ext_preamp_here":$here,"model":{"key":"ic-9700","preamps":["P.AMP"],"attenuators":[10]}}}}"""))

    @Test fun `the 9700's external preamp reads as the whole setting, never as plain P_AMP or nothing`() {
        assertEquals("EXT", nine(2).preampName)
        assertTrue("EXT alone is on, though the internal level is 0", nine(2).preampOn)
        assertEquals("P.AMP+EXT", nine(3).preampName)
        assertEquals("OFF", nine(0).preampName)
        assertFalse(nine(0).preampOn)
    }

    @Test fun `the P_AMP key steps through all four settings where the radio's menu has EXT on`() {
        val steps = (0..3).map { nine(it).nextPreamp() }
        assertEquals(listOf(1, 2, 3, 0), steps.map { it?.mode })
        assertTrue("never the old level form to a module with modes", steps.all { it?.level == null })
        assertTrue(steps.none { it!!.skippedExt })
    }

    @Test fun `with the menu off EXT is passed over, and the reason is there to say`() {
        val refused = """{"2":"EXT P.AMP is off in the radio's menu for 430 MHz","3":"same"}"""
        assertEquals(setOf(2, 3), nine(0, refused, "false").preampModesRefused)
        assertEquals(1, nine(0, refused, "false").nextPreamp()?.mode)
        val fromPamp = nine(1, refused, "false").nextPreamp()!!
        assertEquals(0, fromPamp.mode)
        assertTrue("the step went past EXT", fromPamp.skippedExt)
        assertEquals(ExtPreampNote.MenuOff, nine(1, refused, "false").extPreampNote)
        assertEquals(ExtPreampNote.MenuUnread, nine(1, refused, "null").extPreampNote)
        assertEquals(ExtPreampNote.None, nine(1).extPreampNote)
    }

    @Test fun `an older module gets the level toggle, and nothing before the radio has said`() {
        val old = RadioState.parse(JSONObject("""{"selected":"r","radios":{"r":{"id":"r","preamp":1,"preamp_ext":true,
            "model":{"key":"ic-9700","preamps":["P.AMP"],"attenuators":[10]}}}}"""))
        assertFalse(old.hasPreampModes)
        assertEquals(PreampRequest(level = 0), old.nextPreamp())
        assertEquals(PreampRequest(level = 1), old.copy(preamp = 0).nextPreamp())
        assertNull(old.copy(preamp = null).nextPreamp())
        assertNull(nine(0).copy(preampMode = null).nextPreamp())
        // A 7610's modes are its levels, so the key goes through them by mode.
        val seven = RadioState.parse(JSONObject("""{"selected":"r","radios":{"r":{"id":"r","preamp":2,"preamp_mode":2,
            "preamp_modes":["OFF","P.AMP1","P.AMP2"],"preamp_modes_refused":{},"ext_preamp_menu":null,
            "model":{"key":"ic-7610","preamps":["P.AMP1","P.AMP2"]}}}}"""))
        assertEquals("P.AMP2", seven.preampName)
        assertEquals(PreampRequest(mode = 0), seven.nextPreamp())
        assertFalse(seven.hasExtPreampMenu)
        assertEquals(ExtPreampNote.None, seven.extPreampNote)
    }

    @Test fun `a module that does not read the preamp says nothing about it`() {
        // 1.23.0 and the fakes before beta.5: no fields at all, which is "not reported", never OFF.
        val s = RadioState.parse(fixture("stationflow-1.23.0-state.json"))
        assertNull(s.preamp)
        assertNull(s.att)
        assertNull(s.preampName)
        assertNull(s.attName)
        assertTrue(s.preamps.isEmpty() && s.attenuators.isEmpty())
        val seven = RadioState.parse(JSONObject("""{"selected":"r","radios":{"r":{"id":"r","preamp":2,"att":6,
            "model":{"key":"ic-7610","preamps":["P.AMP1","P.AMP2"],"attenuators":[3,6,9]}}}}"""))
        assertEquals("P.AMP2", seven.preampName)
        assertEquals("6dB", seven.attName)
        assertEquals(listOf(3, 6, 9), seven.attenuators)
    }

    @Test fun `StationFlow 1_23_0, before the TV API`() {
        val s = RadioState.parse(fixture("stationflow-1.23.0-state.json"))
        assertEquals("Shack 9700", s.name)
        assertEquals(432_200_000L, s.freqHz)
        assertEquals("VFOB", s.vfo)
        assertTrue(s.split)
        assertEquals(181 / 241.0, s.smeter!!, 1e-9)
        assertNull(s.auth)
    }

    @Test fun `a raw meter at S9+60 or beyond fills the bar, never more`() {
        assertEquals(1.0, RadioState.smeterFraction(241.0), 1e-9)
        assertEquals(1.0, RadioState.smeterFraction(255.0), 1e-9)
        assertEquals(0.0, RadioState.smeterFraction(0.0), 1e-9)
        assertEquals(120 / 241.0, RadioState.smeterFraction(120.0), 1e-9)   // S9
    }

    @Test fun `a radio switch is the top-level id, not a boolean`() {
        assertTrue(RadioState.parse(JSONObject("""{"selected":"r","switching":"r2","radios":{"r":{"id":"r"}}}""")).switching)
        assertFalse(RadioState.parse(JSONObject("""{"selected":"r","switching":null,"radios":{"r":{"id":"r"}}}""")).switching)
        assertEquals(1, Spectrum.parse(JSONObject("""{"start":1,"end":2,"data":[1],"receiver":1}"""))!!.receiver)
    }

    @Test fun `unknown stays unknown, never zero`() {
        val s = RadioState.parse(JSONObject("""{"selected":"r","radios":{"r":{"id":"r","freq":null,"smeter":null}}}"""))
        assertNull(s.freqHz)
        assertNull(s.smeter)
        assertNull(s.shownMode)
    }

    @Test fun `a decode tunes to the dial that heard it, in USB-D`() {
        val rows = fixture("fakeflow-digi-history.json").optJSONArray("decodes").objects().map { Heard.fromDecode(it) }
        assertTrue(rows.isNotEmpty())
        for (h in rows) {
            assertEquals("USB-D", h.tuneMode)
            assertEquals(h.rfHz!! - h.audioHz!!, h.tuneHz)
            assertTrue(h.call!!.isNotBlank())
        }
    }

    @Test fun `a placeholder JS8 caller is not a callsign`() {
        val h = Heard.fromDecode(JSONObject("""{"mode":"JS8","message":"x","from":"<....>","freq_hz":1000}"""))
        assertNull(h.call)
        assertNull(h.tuneHz)   // no dial_hz: nothing to tune to
    }

    @Test fun `a spot's dial is its RF less its audio offset`() {
        val spots = fixture("fakeflow-log-spots.json").optJSONArray("spots").objects().mapNotNull { Heard.fromSpot(it) }
        assertTrue(spots.isNotEmpty())
        spots.forEach { assertEquals(it.rfHz!! - it.audioHz!!, it.tuneHz) }
    }

    @Test fun `APRS stations tune to the channel in FM`() {
        val st = fixture("fakeflow-aprs.json").optJSONArray("stations").objects().mapNotNull { Heard.fromAprs(it, 144_800_000) }
        assertEquals(3, st.size)
        st.forEach { assertEquals(144_800_000L, it.tuneHz); assertEquals("FM", it.tuneMode) }
        assertTrue(st.any { it.text.contains("Digipeater") })
    }

    @Test fun `the CW decoder's line, as the module's state carries it`() {
        // The contract's example: the radio in USB-D, the decoder idle.
        val idle = RadioState.parse(fixture("tvapi-state-example.json")).cw!!
        assertFalse(idle.active)
        assertTrue(idle.line.isEmpty)
        assertEquals(600, idle.pitch)
        // In CW, part way through an over: the NB kind's line, mode cw.
        val o = JSONObject(
            """{"selected":"r1","radios":{"r1":{"id":"r1","mode":"CW","cw_pitch":500,"cw":
               {"active":true,"available":true,"why":null,"pitch":500,"pitch_source":"radio",
                "wpm":18.24,"text":"CQ CQ DE GM4BRI"}}}}""")
        val cw = RadioState.parse(o).cw!!
        assertTrue(cw.active)
        assertEquals(500, cw.pitch)
        assertEquals("cw", cw.line.mode)
        assertEquals("CQ CQ DE GM4BRI", cw.line.text)
        assertEquals(18.24, cw.line.wpm!!, 1e-9)
        // Listening, nothing read yet: the speed unknown, the text empty.
        val listening = RadioState.parse(JSONObject(
            """{"radios":{"r1":{"cw":{"active":true,"wpm":null,"text":null}}}}""")).cw!!
        assertTrue(listening.active && listening.line.isEmpty)
        assertNull(listening.wpm)
        assertEquals("", listening.text)
        // A module from before the decoder: no cw at all, so no strip.
        assertNull(RadioState.parse(JSONObject("""{"radios":{"r1":{"mode":"CW"}}}""")).cw)
    }

    @Test fun `SSTV pictures and their paths`() {
        val p = SstvPicture.parseList(fixture("fakeflow-sstv.json").optJSONArray("pictures"))
        assertEquals(3, p.size)
        assertEquals("api/sstv/picture/${p[0].id}.png", p[0].path)
        assertEquals("Robot36", p[0].mode)
    }

    @Test fun `a scope frame, and the socket's opening stats are not one`() {
        val f = Spectrum.parse(JSONObject("""{"start":144149000,"end":144199000,"max":160,"data":[1,2,3],"mode":"center"}"""))!!
        assertEquals(50_000L, f.spanHz)
        assertEquals(3, f.data.size)
        assertNull(Spectrum.parse(JSONObject("""{"enabled":true,"points":475,"start":1,"end":2}""")))
    }


    @Test fun `scope settings, NR2 and D-STAR read from the contract's shapes`() {
        val s = RadioState.parse(fixture("icom-scope-nr2-dv-state.json"))
        val caps = s.scopeCaps!!
        assertEquals(5000L, caps.spansHz.first())
        assertEquals(1_000_000L, caps.spansHz.last())
        assertEquals(-20.0, caps.refMin, 0.0)
        assertEquals(0.5, caps.refStep, 0.0)
        assertEquals(listOf("fast", "mid", "slow"), caps.speeds)
        assertEquals(listOf(0, 2, 3, 4), caps.averaging)
        val now = s.scopeSettings!!
        assertEquals(100_000L, now.spanHz)
        assertEquals(-2.5, now.refDb!!, 0.0)
        assertEquals("mid", now.speed)
        val nr2 = s.nr2!!
        assertTrue(nr2.available && nr2.on)
        assertEquals(70, nr2.level)
        assertEquals(600, s.cwPitch)
        val dv = s.dv!!
        assertEquals("M0XYZ /M", dv.last!!.callLine)
        assertEquals("GB7DV B", dv.last!!.via)
        assertEquals("mobile on the A1", dv.last!!.message)
        assertTrue(dv.last!!.receiving)
        assertEquals(listOf("M0XYZ", "GM4BRI"), dv.heard.map { it.caller })
        // Simplex: no repeater to name; the list's own time field is "at".
        assertNull(dv.heard[1].via)
        assertEquals(1791520500.0, dv.heard[1].heardAt!!, 0.0)
    }

    @Test fun `a module without them has no scope, NR2 or D-STAR controls, and NR2 it cannot run is unavailable`() {
        val old = RadioState.parse(fixture("tvapi-state-example.json"))
        assertNull(old.scopeCaps)
        assertNull(old.scopeSettings)
        assertNull(old.nr2)
        assertNull(old.dv)
        val off = RadioState.parse(JSONObject("""{"radios": {"r": {"nr2": {"available": false, "why": "no numpy", "on": false, "level": 50}}}}"""))
        assertFalse(off.nr2!!.available)
        assertEquals("no numpy", off.nr2!!.why)
        // Settings not read yet are nulls, not zeros.
        val unread = RadioState.parse(JSONObject("""{"radios": {"r": {"scope_settings": {"span_hz": null, "ref_db": null}}}}"""))
        assertNull(unread.scopeSettings!!.spanHz)
        assertNull(unread.scopeSettings!!.refDb)
    }
}
