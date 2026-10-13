package com.openhamshack.core

import org.junit.Assert.assertEquals
import org.junit.Test
import com.openhamshack.core.data.DiscoveredBox
import com.openhamshack.core.data.Discovery
import com.openhamshack.core.data.OpenHamShackDirectory
import com.openhamshack.core.data.SavedBox
import com.openhamshack.core.data.SavedBoxes
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.core.net.SessionCookie

class DiscoveryTxtTest {
    @Test fun `StationFlow's TXT makes an https base under its path`() {
        val txt = mapOf("name" to "StationFlow", "version" to "1.24.0", "port" to "443",
            "kind" to "stationflow", "scheme" to "https", "path" to "/icom/")
        val d = Discovery.fromTxt("StationFlow", "198.51.100.208", 443, txt)!!
        assertEquals("stationflow", d.kind)
        assertEquals("https://198.51.100.208/icom/api/state", d.address.url("api/state"))
        assertEquals("StationFlow", d.name)
    }

    @Test fun `a box from before kinds is a OpenHamShack DATV box at http root`() {
        val d = Discovery.fromTxt("GM4BRI", "198.51.100.40", 8080, mapOf("name" to "GM4BRI", "version" to "0.9.17"))!!
        assertEquals(DiscoveredBox.LEGACY_KIND, d.kind)
        assertEquals("http://198.51.100.40:8080/api/status", d.address.url("api/status"))
    }

    @Test fun `saved boxes round-trip, session and pinned CA included`() {
        val boxes = listOf(
            SavedBox("1", "Shack", "stationflow", BoxAddress("198.51.100.208", 443, "/icom", true),
                SessionCookie("icom9700", "v1.c.1.ab"), "-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n"),
            SavedBox("2", "", "datv", BoxAddress("10.0.0.5", 8080)),
        )
        assertEquals(boxes, SavedBoxes.decode(SavedBoxes.encode(boxes)))
    }

    @Test fun `a platform advert lists kinds and points at the app directory`() {
        val txt = mapOf("name" to "GM4BRI", "version" to "0.1.0", "platform" to "1",
            "kinds" to "datv, stationflow", "path" to "/.well-known/openhamshack")
        val d = Discovery.fromTxt("GM4BRI", "198.51.100.208", 80, txt)!!
        assertEquals(true, d.platform)
        assertEquals(setOf("datv", "stationflow"), d.kinds)
        assertEquals("http://198.51.100.208/.well-known/openhamshack", d.address.url(d.directory!!))
    }

    @Test fun `the directory gives each app its base on the server`() {
        val server = BoxAddress.parse("http://198.51.100.208")
        val apps = OpenHamShackDirectory.parse(server, org.json.JSONObject("""
            {"name":"GM4BRI","apps":[
              {"id":"icom","kind":"stationflow","name":"StationFlow","version":"1.24.0","base":"/icom/","running":true},
              {"id":"sub","kind":"stationflow","base":"https://icom.shack.example/","running":false},
              {"id":"bad","kind":"","base":"/x/"},
              {"id":"datv","kind":"datv","base":"/app/openhamshack-datv/","txCapable":true}]}"""))
        assertEquals(listOf("icom", "sub", "datv"), apps.map { it.id })
        assertEquals("http://198.51.100.208/icom/api/state", apps[0].base.url("api/state"))
        assertEquals("https://icom.shack.example/api/state", apps[1].base.url("api/state"))
        assertEquals(false, apps[1].running)
        assertEquals("http://198.51.100.208/app/openhamshack-datv/api/status", apps[2].base.url("api/status"))
        assertEquals(true, apps[2].txCapable)
    }
}
