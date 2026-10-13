import XCTest
@testable import OpenHamShackKit

final class QO100Tests: XCTestCase {
    /// GET /api/qo100?wf=0 from openhamshack.py's own qo100_poll, its detector fed
    /// twelve synthetic BATC frames (noise, the beacon and five stations):
    /// what the box finds, not what this test hopes it finds.
    func snapshot() throws -> QO100Snapshot {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "Fixtures/qo100-fakebox", withExtension: "json"))
        return try QO100Snapshot.parse(try Data(contentsOf: url))
    }

    func testParsesTheBoxsAnswer() throws {
        let q = try snapshot()
        XCTAssertEqual(q.startMHz, 10490.5)
        XCTAssertEqual(q.stopMHz, 10499.5)
        XCTAssertEqual(q.uplinkOffsetMHz, 8089.5)
        XCTAssertEqual(q.spectrum.count, 461)
        XCTAssertTrue(q.live)
        XCTAssertEqual(q.error, "")
        XCTAssertEqual(q.sourceURL, "https://eshail.batc.org.uk/wb/")
        XCTAssertEqual(q.signals.map(\.freqMHz), [10491.5, 10494.75, 10497.75, 10498.2625, 10498.7625, 10499.25])
        XCTAssertEqual(q.beacon?.freqMHz, 10491.5)
        XCTAssertEqual(q.beacon?.tuneSymbolRateKs, 1500)
        XCTAssertEqual(q.signals.filter(\.beacon).count, 1)
        XCTAssertEqual(q.signals[2].tuneSymbolRateKs, 333)
        XCTAssertEqual(q.signals[2].title, "10497.750 · 333 kS")
        XCTAssertEqual(q.signals[0].graphLabel, "Beacon")
        XCTAssertEqual(q.signals[1].graphLabel, "1000 kS 10494.750")
        XCTAssertEqual(q.channels[333]?.first, QO100Channel(downlinkMHz: 10492.75, uplinkMHz: 2403.25, preferred: false))
        XCTAssertEqual(q.channels[1500]?.map(\.downlinkMHz), [10493.25, 10494.75, 10496.25])
        let wf = try XCTUnwrap(q.waterfall)
        XCTAssertTrue(wf.full)
        XCTAssertEqual(wf.rows.first?.count, 461, "one byte a spectrum point")
    }

    func testToleratesAnOldOrEmptyAnswer() throws {
        let q = QO100Snapshot(["spectrum": [], "signals": [["freqMHz": "x"], ["freqMHz": 10499.25, "symbolRateKs": 333]],
                               "error": "No internet: cannot look up eshail.batc.org.uk", "live": false])
        XCTAssertEqual(q.signals.count, 1)
        XCTAssertEqual(q.signals[0].lowMHz, 10499.25 - 333 * 1.35 / 2000, accuracy: 1e-9)
        XCTAssertNil(q.waterfall)
        XCTAssertEqual(QO100.stateWords(q).text, "No internet: cannot look up eshail.batc.org.uk")
        XCTAssertTrue(QO100.stateWords(q).problem)
        XCTAssertThrowsError(try QO100Snapshot.parse(Data("[1]".utf8)))
    }

    /// The console's rule, a signal's width and 0.1 MHz either side, with
    /// the nearer of two taken.
    func testTapFindsTheSignal() throws {
        let q = try snapshot()
        XCTAssertEqual(q.signal(atMHz: 10499.25), 5)
        XCTAssertEqual(q.signal(atMHz: 10491.0), 0, "anywhere on the beacon")
        XCTAssertEqual(q.signal(atMHz: 10496.0), nil, "nothing there")
        // Between 10498.2625 (125 kS) and 10498.7625: the nearer one wins.
        XCTAssertEqual(q.signal(atMHz: 10498.42), 3)
        XCTAssertEqual(q.signal(atMHz: 10498.58), 4)
    }

    func testTuningRules() {
        func rx(_ f: Double?, _ o: Double, sr: Double = 333) -> Receiver {
            Receiver(["id": 1, "frequencyMHz": f.map { .number($0) } ?? .null, "offsetMHz": .number(o), "symbolRateKs": .number(sr)])
        }
        // Terrestrial now: the box's LNB setting.
        XCTAssertEqual(QO100.offset(for: rx(437, 0), lnbLO: 9750), 9750)
        // Already on QO-100 with an LO measured to the kHz: kept.
        XCTAssertEqual(QO100.offset(for: rx(10499.25, 9739.9), lnbLO: 9750), 9739.9)
        // HamTV's downconverter is an offset too, and not an LNB.
        XCTAssertEqual(QO100.offset(for: rx(2395, 1960), lnbLO: 9750), 9750)
        XCTAssertEqual(QO100.offset(for: rx(437, 0), lnbLO: 0), 9750)
        XCTAssertTrue(QO100.onQO100(rx(10491.5, 9750)))
        XCTAssertFalse(QO100.onQO100(rx(10491.5, 0)))

        // A tap where nothing was found: the nearest 12.5 kHz, at the
        // receiver's rate unless it is the beacon's.
        XCTAssertEqual(QO100.freeTune(atMHz: 10497.3061, currentRate: 1500).frequencyMHz, 10497.3)
        XCTAssertEqual(QO100.freeTune(atMHz: 10497.3061, currentRate: 1500).symbolRateKs, 333)
        XCTAssertEqual(QO100.freeTune(atMHz: 10497.32, currentRate: 125).frequencyMHz, 10497.325)
        XCTAssertEqual(QO100.freeTune(atMHz: 10497.32, currentRate: 125).symbolRateKs, 125)
        XCTAssertEqual(QO100.freeTune(atMHz: 10497.32, currentRate: nil).symbolRateKs, 333)

        let s = QO100Signal(freqMHz: 10499.25, symbolRateKs: 333)
        XCTAssertEqual(QO100.tuned(to: s, receivers: [rx(10499.25, 9750), rx(10499.25, 0), rx(10499.25, 9750, sr: 250)]), [1])
    }

    /// The beacon from a terrestrial receiver at 333 kS: frequency, 1500 kS
    /// and the LNB offset in the one tune - never the frequency alone.
    func testBeaconTune() throws {
        let rx = Receiver(["id": 1, "frequencyMHz": 437.0, "offsetMHz": 0, "symbolRateKs": 333])
        let beacon = try XCTUnwrap(try snapshot().beacon)
        let p = QO100.tunePlan(beacon, receiver: rx, lnbLO: 9750)
        XCTAssertEqual(p.frequencyMHz, 10491.5)
        XCTAssertEqual(p.symbolRateKs, 1500)
        XCTAssertEqual(p.offsetMHz, 9750)
        // The beacon chip, which names no rate, gets the beacon's too.
        let chip = QO100.tunePlan(frequencyMHz: QO100.beaconMHz, symbolRateKs: nil, receiver: rx, lnbLO: 9750)
        XCTAssertEqual(chip.symbolRateKs, 1500)
        // A station from the beacon: its own rate, the LO kept.
        let onBeacon = Receiver(["id": 1, "frequencyMHz": 10491.5, "offsetMHz": 9739.9, "symbolRateKs": 1500])
        let station = QO100.tunePlan(QO100Signal(freqMHz: 10499.25, symbolRateKs: 333), receiver: onBeacon, lnbLO: 9750)
        XCTAssertEqual(station.symbolRateKs, 333)
        XCTAssertEqual(station.offsetMHz, 9739.9)
    }

    func testStateWords() throws {
        XCTAssertEqual(QO100.stateWords(nil).text, "Asking the box for BATC's spectrum…")
        XCTAssertEqual(QO100.stateWords(try snapshot(), verb: "select").text,
                       "Live from BATC's monitor at Goonhilly · 6 signals: select one to receive it")
        XCTAssertEqual(QO100.stateWords(QO100Snapshot(["live": false, "age": 7.4])).text, "No new spectrum for 7 s")
        XCTAssertEqual(QO100.stateWords(try snapshot(), boxProblem: "OpenHamShack is not answering").text, "OpenHamShack is not answering")
        XCTAssertTrue(QO100.credit(nil).contains("eshail.batc.org.uk/wb/"))
    }

    func testChannelsForARate() throws {
        let q = try snapshot()
        XCTAssertEqual(q.channels(forRate: 1500).rate, 1500)
        XCTAssertEqual(q.channels(forRate: 2000).rate, 333, "a rate the plan does not use shows 333 kS's")
        XCTAssertEqual(q.channels(forRate: nil).channels.count, 14)
    }

    func testWaterfallBuffer() throws {
        func wf(_ seq: Int, full: Bool, _ rows: [[UInt8]], max: Int = 3) -> QO100Waterfall {
            QO100Waterfall(["seq": .number(Double(seq)), "full": .bool(full), "maxRows": .number(Double(max)),
                            "rows": .array(rows.map { .string(Data($0).base64EncodedString()) })])!
        }
        var b = QO100WaterfallBuffer()
        XCTAssertNil(b.rgba())
        b.add(wf(10, full: true, [[1, 1], [2, 2]]))
        XCTAssertEqual(b.rows, [[2, 2], [1, 1]], "newest first")
        XCTAssertEqual(b.since, 10)
        b.add(wf(12, full: false, [[3, 3], [4, 4]]))
        XCTAssertEqual(b.rows, [[4, 4], [3, 3], [2, 2]], "kept to maxRows")
        b.add(wf(300, full: true, [[9, 9]]))
        XCTAssertEqual(b.rows, [[9, 9]], "full: start again")
        let img = try XCTUnwrap(b.rgba())
        XCTAssertEqual(img.width, 2)
        XCTAssertEqual(img.height, 3)
        XCTAssertEqual(img.bytes.count, 2 * 3 * 4)
        XCTAssertEqual(Array(img.bytes[0..<4]), [QO100.waterfallPalette[27], QO100.waterfallPalette[28], QO100.waterfallPalette[29], 255])

        // The fixture's rows go in whole.
        var real = QO100WaterfallBuffer()
        real.add(try XCTUnwrap(try snapshot().waterfall))
        XCTAssertEqual(real.rows.count, 2)
    }

    func testPalette() {
        let p = QO100.waterfallPalette
        XCTAssertEqual(p.count, 768)
        XCTAssertEqual(Array(p[0..<3]), [8, 12, 28], "the floor, -3 dB")
        XCTAssertEqual(Array(p[255 * 3..<256 * 3]), [240, 60, 30], "29 dB: red")
    }

    func testSignalsKeepTheirIdentity() {
        var ids = QO100SignalIdentity()
        let beacon = QO100Signal(freqMHz: 10491.5, beacon: true, symbolRateKs: 1500)
        let a = QO100Signal(freqMHz: 10497.75, symbolRateKs: 333), b = QO100Signal(freqMHz: 10499.25, symbolRateKs: 333)
        let first = ids.identify([beacon, a, b])
        XCTAssertEqual(Set(first).count, 3)
        // A centre that moved by a step, and a new station between.
        let moved = QO100Signal(freqMHz: 10497.7625, symbolRateKs: 333), c = QO100Signal(freqMHz: 10498.5, symbolRateKs: 125)
        let second = ids.identify([beacon, moved, c, b])
        XCTAssertEqual(second[0], first[0])
        XCTAssertEqual(second[1], first[1])
        XCTAssertEqual(second[3], first[2])
        XCTAssertFalse(first.contains(second[2]))
        // Gone and back is a new one.
        _ = ids.identify([beacon])
        XCTAssertNotEqual(ids.identify([beacon, b])[1], first[2])
    }
}
