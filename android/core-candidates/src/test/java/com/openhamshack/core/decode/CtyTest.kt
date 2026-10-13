package com.openhamshack.core.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The country lookup on the cty.dat the app ships (src/main/assets, read
 * from the module folder Gradle runs the tests in), with the answers
 * openhamshack-nb's ohscore/dxcc.py gives on the same file - the desktop's
 * Country column and the server's "country" must agree with this one.
 */
class CtyTest {
    private val cty = Cty.parse(String(File("src/main/assets/cty.dat").readBytes(), Charsets.ISO_8859_1))

    private fun name(call: String) = cty.lookup(call)?.name

    @Test fun `the bundled file parses`() {
        assertFalse(cty.isEmpty)
    }

    @Test fun `a home call goes by its longest known prefix`() {
        assertEquals("England", name("G4XYZ"))
        assertEquals("Scotland", name("GM4BRI"))
        assertEquals("Spain", name("EA4QRP"))
        assertEquals("Canary Islands", name("EA8BFK"))
        assertEquals("Fed. Rep. of Germany", name("DL1ABC"))
        assertEquals("United States", name("K1ABC"))
        assertEquals(Cty.Entity("Canary Islands", "AF", "EA8"), cty.lookup("ea8bfk"))
    }

    @Test fun `a portable prefix is where the station is, either side`() {
        assertEquals("Canary Islands", name("EA8/G4ABC"))
        assertEquals("Canary Islands", name("G4ABC/EA8"))
        // A call area: EA1ABC signing /8 is in EA8.
        assertEquals("Canary Islands", name("EA1ABC/8"))
    }

    @Test fun `p, m and qrp say how, not where`() {
        assertEquals("England", name("G4ABC/P"))
        assertEquals("England", name("G4ABC/M"))
        assertEquals("Scotland", name("GM4BRI/QRP"))
        assertEquals("Canary Islands", name("EA8/G4ABC/P"))
    }

    @Test fun `unknowns, hashed calls and the sea have no country`() {
        assertNull(name(""))
        assertNull(name("<...>"))
        assertNull(name("G4ABC/MM"))
        assertNull(name("G4ABC/AM"))
        assertNull(name("QQ"))
    }

    @Test fun `a hashed call in brackets is looked up without them`() {
        assertEquals("Scotland", name("<GM4BRI>"))
    }

    @Test fun `an exact entry beats the prefix`() {
        val t = Cty.parse(
            "England:  14:  27:  EU:   52.77:     1.47:     0.0:  G:\n    G,=GB0SPECIAL;\n" +
                "Antarctica:  13:  67:  SA:  -90.00:     0.00:     0.0:  CE9:\n    =GB0SPECIAL(13)[67];\n",
        )
        assertEquals("Antarctica", t.lookup("GB0SPECIAL")?.name)
        assertEquals("England", t.lookup("GB0OTHER")?.name)
    }

    @Test fun `the location part, as dxcc py splits it`() {
        assertEquals("EA8", Cty.locationPart("EA8/G4ABC"))
        assertEquals("G4ABC", Cty.locationPart("G4ABC/P"))
        assertEquals("EA8", Cty.locationPart("EA1ABC/8"))
        assertNull(Cty.locationPart("G4ABC/MM"))
    }
}
