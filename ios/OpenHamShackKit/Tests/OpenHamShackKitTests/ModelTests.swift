import XCTest
@testable import OpenHamShackKit

final class ModelTests: XCTestCase {
    func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/\(name)", withExtension: nil))
        return try Data(contentsOf: url)
    }

    /// A real /api/status, from openhamshack.py's own handler under tools/fakebox.py.
    func testParsesARealStatus() throws {
        let st = try BoxStatus.parse(try fixture("status-fakebox.json"))
        XCTAssertFalse(st.onAir)
        XCTAssertEqual(st.version, "0.9.16")
        XCTAssertEqual(st.config.callsign, "M0TST")
        XCTAssertEqual(st.config.frequencyMHz, 437.0)
        XCTAssertEqual(st.config.modeSummary, "DVB-S2 QPSK 2/3")
        XCTAssertEqual(st.config.source, "testcard")
        XCTAssertEqual(st.auth, AuthInfo(local: false, authorised: false, pinSet: true))
        XCTAssertFalse(st.auth.canControl)
        XCTAssertTrue(st.rx.locked)
        XCTAssertEqual(st.receivers.count, 1)
        let r = st.receivers[0]
        XCTAssertEqual(r.id, 1)
        XCTAssertEqual(r.frequencyMHz, 437.0)
        XCTAssertEqual(r.symbolRateKs, 333)
        XCTAssertEqual(r.modcod, "QPSK 2/3")
        XCTAssertEqual(r.video?.summary, "1280×720 h264 25 fps")
        XCTAssertEqual(st.stream.port, 19710)
        XCTAssertEqual(st.stream.state, "off")
        XCTAssertNil(st.lease)
        XCTAssertNil(st.txTimeout)
    }

    /// What the box sends while keyed under an app's lease, with a time-out.
    func testKeyedStatusWithLease() {
        let j: JSON = ["onAir": true, "onAirSeconds": 75, "txError": "",
                       "txTimeout": ["minutes": 10, "secondsLeft": 45, "warning": true],
                       "tx": ["width": 768, "remote": ["id": "ipad-abc", "secondsLeft": 4, "leaseSeconds": 5]],
                       "txCheck": ["kind": "busy", "message": "Someone is on 10499.25"]]
        let st = BoxStatus(j)
        XCTAssertTrue(st.onAir)
        XCTAssertEqual(st.onAirSeconds, 75)
        XCTAssertEqual(st.txTimeout?.secondsLeft, 45)
        XCTAssertEqual(st.txTimeout?.warning, true)
        XCTAssertEqual(st.lease?.id, "ipad-abc")
        XCTAssertEqual(st.lease?.leaseSeconds, 5)
        XCTAssertEqual(st.txCheck?.kind, "busy")
    }

    /// One field of an unexpected type costs that field, never the status:
    /// the ON AIR indicator must keep working whatever else changes.
    func testToleratesSurprises() {
        let j: JSON = ["onAir": true, "config": ["frequencyMHz": "437.25", "symbolRateKs": nil, "callsign": 7],
                       "receivers": "not a list", "rx": ["locked": "yes", "mer": "x"], "auth": nil]
        let st = BoxStatus(j)
        XCTAssertTrue(st.onAir)
        XCTAssertEqual(st.config.frequencyMHz, 437.25)
        XCTAssertNil(st.config.symbolRateKs)
        XCTAssertEqual(st.config.callsign, "")
        XCTAssertFalse(st.rx.locked)
        XCTAssertNil(st.rx.mer)
        XCTAssertEqual(st.receivers.count, 1, "receiver 1 from status.rx when there is no list")
        XCTAssertFalse(st.auth.canControl)
    }

    func testNotAStatus() {
        XCTAssertThrowsError(try BoxStatus.parse(Data("[1,2]".utf8)))
        XCTAssertThrowsError(try BoxStatus.parse(Data("<html>".utf8)))
    }

    /// The box's newer /api/stream/larix with the plain fields.
    func testStreamTargetPlain() {
        let j: JSON = ["protocol": "srt", "port": 19710, "latencyMs": 200, "passphrase": "fakebox-passphrase",
                       "secretSet": true, "secretIncluded": true,
                       "video": ["codec": "h264", "width": 1280, "height": 720, "fps": 30, "kbps": 2500]]
        let t = StreamTarget(j)
        XCTAssertEqual(t.port, 19710)
        XCTAssertEqual(t.passphrase, "fakebox-passphrase")
        XCTAssertEqual(t.width, 1280)
        XCTAssertEqual(t.kbps, 2500)
    }

    /// An older box: only the Larix links, the facts read back out of one.
    func testStreamTargetFromLarixLink() {
        let link = "larix://set/v1?conn[][url]=srt%3A%2F%2F198.51.100.208%3A19710&conn[][name]=OpenHamShack&conn[][overwrite]=on"
            + "&conn[][mode]=va&conn[][srtmode]=c&conn[][srtlatency]=250&conn[][srtpass]=secret-pass-123"
            + "&enc[vid][res]=1920x1080&enc[vid][fps]=25&enc[vid][bitrate]=4000&enc[vid][format]=avc"
        let j: JSON = ["protocol": "srt", "secretSet": true, "links": [["via": "eno1", "larix": .string(link)]]]
        let t = StreamTarget(j)
        XCTAssertEqual(t.port, 19710)
        XCTAssertEqual(t.latencyMs, 250)
        XCTAssertEqual(t.passphrase, "secret-pass-123")
        XCTAssertEqual(t.width, 1920)
        XCTAssertEqual(t.height, 1080)
        XCTAssertEqual(t.fps, 25)
        XCTAssertEqual(t.kbps, 4000)
    }

    func testStreamTargetWithoutPIN() {
        let j: JSON = ["protocol": "srt", "port": 9710, "passphrase": nil, "secretSet": true]
        let t = StreamTarget(j)
        XCTAssertNil(t.passphrase)
        XCTAssertTrue(t.secretSet)
    }

    func testSessionCookie() {
        XCTAssertEqual(OpenHamShackClient.sessionFromCookie("ohssession=abc_DEF-1; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"), "abc_DEF-1")
        XCTAssertEqual(OpenHamShackClient.sessionFromCookie("other=1; Path=/, ohssession=tok; Path=/"), "tok")
        XCTAssertNil(OpenHamShackClient.sessionFromCookie("ohssession=; Path=/"))
        XCTAssertNil(OpenHamShackClient.sessionFromCookie("session=abc"))
    }

    func testStreamURL() {
        let c = OpenHamShackClient(address: BoxAddress(host: "fe80::1", port: 8080))
        XCTAssertEqual(c.streamURL(receiver: 2).absoluteString, "http://[fe80::1]:8080/api/rx/stream.ts?rx=2")
    }

    func testRemoteID() {
        let s = Store(defaults: UserDefaults(suiteName: "test-\(UUID())")!, keychain: MemoryVault())
        let id = s.remoteID
        XCTAssertTrue(Store.validRemoteID(id))
        XCTAssertEqual(s.remoteID, id, "made once, kept")
        XCTAssertFalse(Store.validRemoteID("has space"))
        XCTAssertFalse(Store.validRemoteID(String(repeating: "a", count: 65)))
    }

    func testSavedBoxes() {
        let s = Store(defaults: UserDefaults(suiteName: "test-\(UUID())")!, keychain: MemoryVault())
        let a = s.remember(name: "Shack", address: BoxAddress(host: "198.51.100.40"))
        _ = s.remember(name: "", address: BoxAddress(host: "openhamshack.local"))
        let again = s.remember(name: "", address: BoxAddress(host: "198.51.100.40"))
        XCTAssertEqual(again.id, a.id, "one entry per address")
        XCTAssertEqual(again.name, "Shack", "an empty name keeps the old one")
        XCTAssertEqual(s.boxes.count, 2)
        s.setSession("tok", for: a.address)
        XCTAssertEqual(s.session(for: a.address), "tok")
        s.forget(a)
        XCTAssertEqual(s.boxes.count, 1)
        XCTAssertNil(s.session(for: a.address), "forgetting a box forgets its session")
    }
}

final class AddressTests: XCTestCase {
    func testParse() throws {
        XCTAssertEqual(try BoxAddress.parse("198.51.100.40"), BoxAddress(host: "198.51.100.40", port: 8080))
        XCTAssertEqual(try BoxAddress.parse(" http://openhamshack.local:8081/ "), BoxAddress(host: "openhamshack.local", port: 8081))
        XCTAssertEqual(try BoxAddress.parse("[fe80::1]:9000"), BoxAddress(host: "fe80::1", port: 9000))
        XCTAssertEqual(try BoxAddress.parse("fd7a:115c::1"), BoxAddress(host: "fd7a:115c::1", port: 8080))
        XCTAssertEqual(try BoxAddress.parse("shack:18081").display, "shack:18081")
        XCTAssertEqual(BoxAddress(host: "box").display, "box")
    }

    func testRefusals() {
        for bad in ["", "https://box", "box/path", "user@box", "box:0", "box:99999", "box:abc", "[fe80::1", "-box"] {
            XCTAssertThrowsError(try BoxAddress.parse(bad), bad)
        }
    }
}

final class TuningTests: XCTestCase {
    func testOffsets() {
        // receivers.js rxOffsetFor
        XCTAssertEqual(Tuning.offsetFor(frequencyMHz: 10491.5, currentOffset: 0, lnbLO: 9750), 9750)
        XCTAssertEqual(Tuning.offsetFor(frequencyMHz: 10491.5, currentOffset: 9739, lnbLO: 9750), 9739)
        XCTAssertEqual(Tuning.offsetFor(frequencyMHz: 437, currentOffset: 9750, lnbLO: 9750), 0)
        XCTAssertEqual(Tuning.offsetFor(frequencyMHz: 437, currentOffset: 0, lnbLO: 9750), 0)
        // An IF typed straight in (above the LO, below 10 GHz) keeps the LNB.
        XCTAssertEqual(Tuning.offsetFor(frequencyMHz: 9800, currentOffset: 9750, lnbLO: 9750), 9750)
    }

    func testRates() {
        XCTAssertEqual(Tuning.rates(viaLNB: true, current: 333), Tuning.qoRates)
        XCTAssertEqual(Tuning.rates(viaLNB: false, current: 92), [92] + Tuning.terrestrialRates)
        XCTAssertEqual(Tuning.band(for: 437)?.name, "70 cm")
        XCTAssertEqual(Tuning.band(for: 10499.25)?.name, "QO-100")
        XCTAssertNil(Tuning.band(for: 50))
    }

    func testBandsWithoutQO100() {
        XCTAssertFalse(Tuning.terrestrialBands.contains { $0.viaLNB })
        XCTAssertEqual(Tuning.terrestrialBands.count, 3)
        XCTAssertEqual(Tuning.band(for: 10499.25)?.name, "QO-100", "still named on the overlay")
    }

    /// A frequency typed or nudged into another band takes a rate that band
    /// uses, in the same tune.
    func testRateFollowsTheBand() {
        // Into the beacon's segment: 1500, whatever the receiver had.
        XCTAssertEqual(Tuning.rate(movingTo: 10491.5, offsetMHz: 9750, current: 333), 1500)
        XCTAssertEqual(Tuning.rate(movingTo: 10491.75, offsetMHz: 9750, current: 2000), 1500)
        XCTAssertNil(Tuning.rate(movingTo: 10491.5, offsetMHz: 9750, current: 1500))
        // Onto the transponder with a terrestrial-only rate: the nearest QO-100 one.
        XCTAssertEqual(Tuning.rate(movingTo: 10499.25, offsetMHz: 9750, current: 2000), 1500)
        XCTAssertEqual(Tuning.rate(movingTo: 10499.25, offsetMHz: 9750, current: 4000), 1500)
        XCTAssertNil(Tuning.rate(movingTo: 10499.25, offsetMHz: 9750, current: 333), "a QO-100 rate is kept")
        // Back to terrestrial from a QO-100-only rate.
        XCTAssertEqual(Tuning.rate(movingTo: 437, offsetMHz: 0, current: 33), 125)
        XCTAssertEqual(Tuning.rate(movingTo: 437, offsetMHz: 0, current: 66), 125)
        XCTAssertNil(Tuning.rate(movingTo: 437, offsetMHz: 0, current: 1500))
        XCTAssertEqual(Tuning.rate(movingTo: 437, offsetMHz: 0, current: nil), 333)
    }

    func testNudges() {
        XCTAssertEqual(Tuning.nudged(437, step: 0.025, presses: 3), 437.075)
        XCTAssertEqual(Tuning.nudged(437, step: 0.25, presses: -2), 436.5)
        XCTAssertEqual(Tuning.nudged(146.5, step: 1, presses: 1), 147.5)
        // Forty small steps make one megahertz exactly, not 437.99999999.
        XCTAssertEqual(Tuning.nudged(437, step: 0.025, presses: 40), 438)
        // An odd starting point is kept, not snapped to the step's grid.
        XCTAssertEqual(Tuning.nudged(437.01, step: 0.25, presses: 1), 437.26)
        // Never to zero or below.
        XCTAssertEqual(Tuning.nudged(0.5, step: 1, presses: -3), 1)
    }

    func testTypedFrequency() {
        XCTAssertEqual(Tuning.parseMHz("437.25"), 437.25)
        XCTAssertEqual(Tuning.parseMHz(" 10491,5 "), 10491.5)
        XCTAssertNil(Tuning.parseMHz(""))
        XCTAssertNil(Tuning.parseMHz("0"))
        XCTAssertNil(Tuning.parseMHz("-5"))
        XCTAssertNil(Tuning.parseMHz("437 MHz"))
    }

    func testChannels() {
        let st = BoxStatus(["receivers": [["id": 1, "enabled": true], ["id": 2, "enabled": false], ["id": 3, "enabled": true]]])
        XCTAssertEqual(Channel.list(status: st, canTune: false).map(\.id), ["rx1", "rx3"])
        XCTAssertGreaterThan(Channel.list(status: st, canTune: true).count, 2)
    }
}

final class RoundTwoTests: XCTestCase {
    /// GET /api/tx/options as the box sends it (REMOTE-API.md 6).
    func testTxOptions() {
        let j: JSON = ["presets": [["label": "2 m", "frequencyMHz": 146.5], ["label": "23 cm", "frequencyMHz": 1255.0]],
                       "symbolRates": ["qo100": [33, 66, 333], "terrestrial": [125, 333, 2000]],
                       "modes": ["dvbs2", "dvbs"],
                       "constellations": ["dvbs": ["qpsk"], "dvbs2": ["qpsk", "8psk"]],
                       "fec": ["dvbs": ["1/2", "7/8"], "dvbs2": ["qpsk": ["1/4", "2/3"], "8psk": ["3/5", "9/10"]]],
                       "gainDb": ["min": -70, "max": 0, "step": 1],
                       "qo100": ["uplinkMHz": [2401.0, 2410.0]],
                       "licence": ["region": "scotland", "bands": [[144.0, 146.9375], [1240.0, 1325.0]]]]
        let o = TxOptions(j)
        XCTAssertEqual(o.presets.map(\.label), ["2 m", "23 cm"])
        XCTAssertEqual(o.rates(for: 2403.25, current: 333), [33, 66, 333])
        XCTAssertEqual(o.rates(for: 437, current: 92), [92, 125, 333, 2000])
        XCTAssertEqual(o.fecs(mode: "dvbs2", constellation: "8psk"), ["3/5", "9/10"])
        XCTAssertEqual(o.fecs(mode: "dvbs", constellation: "8psk"), ["1/2", "7/8"])
        XCTAssertEqual(o.licenceBand(containing: 146.5), 144.0...146.9375)
        XCTAssertNil(o.licenceBand(containing: 146.95))
        XCTAssertFalse(o.licenceBands == nil)
        XCTAssertNil(TxOptions(["licence": ["bands": nil]]).licenceBands, "no band check")
    }

    func testTxTuneWords() {
        let st = BoxStatus(["onAir": true, "txTune": ["changed": ["frequencyMHz": 1255.0, "fec": "3/4", "gainDb": -5],
                                                      "nextKeyUp": ["frequencyMHz", "fec"]]])
        XCTAssertEqual(st.txTune?.nextKeyUpWords, "Frequency and FEC apply at the next key-up; the transmission carries on as it was.")
        XCTAssertNil(BoxStatus(["txTune": ["changed": [:], "nextKeyUp": []]]).txTune?.nextKeyUpWords)
        XCTAssertNil(BoxStatus([:]).txTune)
        // A box that lists nothing on air: the contract's rule fills in.
        let bare = BoxStatus(["txTune": ["changed": ["gainDb": -5, "fec": "3/4", "frequencyMHz": 1255.0], "nextKeyUp": []]]).txTune!
        XCTAssertEqual(bare.waiting(onAir: true), ["frequencyMHz", "fec"])
        XCTAssertEqual(bare.waiting(onAir: false), [])
    }

    func testBonjourTXT() {
        let e = NWEndpointStub.endpoint
        let f = BoxBrowser.Found.make(name: "openhamshack", txt: ["name": "G0ABC", "version": "0.9.17", "port": "8080"], endpoint: e)
        XCTAssertEqual(f.name, "G0ABC")
        XCTAssertEqual(f.version, "0.9.17")
        XCTAssertEqual(f.port, 8080)
        let bare = BoxBrowser.Found.make(name: "openhamshack", txt: [:], endpoint: e)
        XCTAssertEqual(bare.name, "openhamshack")
        XCTAssertNil(bare.version)
    }
}

import Network
enum NWEndpointStub {
    static let endpoint = NWEndpoint.service(name: "x", type: "_openhamshack._tcp", domain: "local.", interface: nil)
}
