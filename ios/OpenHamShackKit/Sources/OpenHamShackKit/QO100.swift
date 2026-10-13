import Foundation

// QO-100's wideband transponder as the box sees it: GET /api/qo100.
//
// The box does all the BATC work. It holds the one websocket to BATC's
// wideband monitor (eshail.batc.org.uk/wb), finds the signals in each frame
// exactly as BATC's own page does (openhamshacklib/qo100.py qo100_detect), and
// keeps three minutes of waterfall. The app only polls the box. It must
// never open BATC itself: a club server should see one connection per
// station, and the box's answer is already what an operator wants (signals
// with the rate to tune, the beacon flagged, the band plan alongside).
//
// The feed stays open only while somebody polls: QO100_IDLE (20 s) after
// the last poll the box lets it go. QO100Monitor polls once a second while
// a QO-100 screen is on show and stops when it goes, which is what lets the
// box close the connection.
//
// Everything below is read loosely from the JSON tree, as BoxStatus is: a
// field missing or of a surprising type costs that field, not the page.

/// One signal the box found on the transponder.
public struct QO100Signal: Equatable, Sendable {
    /// Downlink centre, rounded as BATC labels it (12.5 kHz below 0.7 MS, 25 kHz above).
    public var freqMHz: Double
    public var beacon: Bool
    /// Stronger than the beacon allows (BATC's over-power test): drawn red.
    public var overPower: Bool
    /// BATC's symbol-rate class, as measured from the width.
    public var symbolRateKs: Double
    /// The standard rate a receiver should be set to for it (BATC's 35 kS
    /// class is transmitted as 33, for instance).
    public var tuneSymbolRateKs: Double
    public var lowMHz: Double
    public var highMHz: Double
    /// Strength over the noise floor, dB.
    public var levelDb: Double

    public init(freqMHz: Double, beacon: Bool = false, overPower: Bool = false, symbolRateKs: Double,
                tuneSymbolRateKs: Double? = nil, lowMHz: Double? = nil, highMHz: Double? = nil, levelDb: Double = 0) {
        self.freqMHz = freqMHz
        self.beacon = beacon
        self.overPower = overPower
        self.symbolRateKs = symbolRateKs
        self.tuneSymbolRateKs = tuneSymbolRateKs ?? symbolRateKs
        let half = symbolRateKs * 1.35 / 2000
        self.lowMHz = lowMHz ?? freqMHz - half
        self.highMHz = highMHz ?? freqMHz + half
        self.levelDb = levelDb
    }

    init?(_ j: JSON) {
        guard let f = j["freqMHz"].double else { return nil }
        let sr = j["symbolRateKs"].double ?? 0
        self.init(freqMHz: f, beacon: j["beacon"].bool ?? false, overPower: j["overPower"].bool ?? false,
                  symbolRateKs: sr, tuneSymbolRateKs: j["tuneSymbolRateKs"].double ?? sr,
                  lowMHz: j["lowMHz"].double, highMHz: j["highMHz"].double, levelDb: j["levelDb"].double ?? 0)
    }

    /// "10499.250 · 333 kS", the console's chip words.
    public var title: String {
        "\(Tuning.mhz(freqMHz)) · \(Tuning.ks(tuneSymbolRateKs)) kS"
    }

    /// The label over it on the spectrum (the console's drawSpectrum).
    public var graphLabel: String {
        if beacon { return "Beacon" }
        if overPower { return "[over-power]" }
        return "\(Tuning.ks(symbolRateKs)) kS \(Tuning.mhz(freqMHz))"
    }
}

/// One channel of the band plan at a symbol rate (openhamshacklib/bands.py qo100_channels).
public struct QO100Channel: Equatable, Sendable {
    public var downlinkMHz: Double
    public var uplinkMHz: Double
    /// Where that rate should go first (333 kS and below: above 10497).
    public var preferred: Bool
}

/// The waterfall rows sent after the `wf` sequence number asked for.
public struct QO100Waterfall: Equatable, Sendable {
    public var seq: Int
    /// The rows are all the box has: drop any drawn before.
    public var full: Bool
    public var rowSeconds: Double
    public var maxRows: Int
    public var dbLow: Double
    public var dbStep: Double
    /// Oldest first, as sent; one byte a point, dbLow + byte * dbStep dB.
    public var rows: [[UInt8]]

    init?(_ j: JSON) {
        guard j.object != nil, let seq = j["seq"].int else { return nil }
        self.seq = seq
        full = j["full"].bool ?? true
        rowSeconds = j["rowSeconds"].double ?? 1
        maxRows = j["maxRows"].int ?? 180
        dbLow = j["dbLow"].double ?? -3
        dbStep = j["dbStep"].double ?? 0.125
        rows = j["rows"].array.compactMap { $0.string.flatMap { Data(base64Encoded: $0) }.map { [UInt8]($0) } }
    }
}

/// GET /api/qo100.
public struct QO100Snapshot: Equatable, Sendable {
    /// dB over the noise floor, 461 points (max-pooled pairs of BATC's 922
    /// bins) evenly across startMHz...stopMHz. Empty until the first frame.
    public var spectrum: [Double]
    public var startMHz: Double
    public var stopMHz: Double
    public var uplinkOffsetMHz: Double
    /// The band plan, by symbol rate (kS).
    public var channels: [Int: [QO100Channel]]
    /// In frequency order, as the box finds them.
    public var signals: [QO100Signal]
    /// Something still above the threshold at the top edge.
    public var outOfBand: Bool
    /// Seconds since BATC's last frame; nil before the first.
    public var age: Double?
    public var live: Bool
    /// Why there is no spectrum ("No internet: cannot look up ..."), or "".
    public var error: String
    public var source: String
    public var sourceURL: String
    public var waterfall: QO100Waterfall?

    public init(_ j: JSON) {
        spectrum = j["spectrum"].array.compactMap(\.double)
        startMHz = j["startMHz"].double ?? QO100.lowMHz
        stopMHz = j["stopMHz"].double ?? QO100.highMHz
        if stopMHz <= startMHz { (startMHz, stopMHz) = (QO100.lowMHz, QO100.highMHz) }
        uplinkOffsetMHz = j["uplinkOffsetMHz"].double ?? 8089.5
        var ch: [Int: [QO100Channel]] = [:]
        for (k, v) in j["channels"].object ?? [:] {
            guard let sr = Int(k) else { continue }
            ch[sr] = v.array.compactMap { c in
                c["downlinkMHz"].double.map {
                    QO100Channel(downlinkMHz: $0, uplinkMHz: c["uplinkMHz"].double ?? $0 - 8089.5,
                                 preferred: c["preferred"].bool ?? false)
                }
            }
        }
        channels = ch
        signals = j["signals"].array.compactMap(QO100Signal.init)
        outOfBand = j["outOfBand"].bool ?? false
        age = j["age"].double
        live = j["live"].bool ?? false
        error = j["error"].string ?? ""
        source = j["source"].string ?? "BATC QO-100 wideband spectrum monitor"
        sourceURL = j["sourceUrl"].string ?? "https://eshail.batc.org.uk/wb/"
        waterfall = QO100Waterfall(j["waterfall"])
    }

    public static func parse(_ data: Data) throws -> QO100Snapshot {
        let j = try JSON(data: data)
        guard j.object != nil else { throw OpenHamShackError.badResponse("OpenHamShack sent something that is not a QO-100 spectrum") }
        return QO100Snapshot(j)
    }

    public var beacon: QO100Signal? { signals.first(where: \.beacon) }

    /// The signal under a tap at f MHz: within its width, generously (0.1 MHz
    /// either side, the console's rule, since a 33 kS station is a sliver).
    /// Where two qualify, the nearer: the console takes the first, which on a
    /// finger-sized tap between two narrow stations was the wrong one half
    /// the time.
    public func signal(atMHz f: Double) -> Int? {
        var best: (Int, Double)?
        for (i, s) in signals.enumerated() where f >= s.lowMHz - 0.1 && f <= s.highMHz + 0.1 {
            let d = f < s.lowMHz ? s.lowMHz - f : f > s.highMHz ? f - s.highMHz : 0
            if best == nil || d < best!.1 { best = (i, d) }
        }
        return best?.0
    }

    /// The band plan's channels for a rate, or 333 kS's when the plan has
    /// none at it (a terrestrial 2000 kS): the console's gridSr.
    public func channels(forRate sr: Double?) -> (rate: Int, channels: [QO100Channel]) {
        if let sr, let c = channels[Int(sr.rounded())], !c.isEmpty { return (Int(sr.rounded()), c) }
        return (333, channels[333] ?? [])
    }
}

/// The rules the console's QO-100 page tunes by (www/js/qo100.js), in one
/// place for the iPad, the iPhone and the Apple TV.
public enum QO100 {
    public static let lowMHz = 10490.5
    public static let highMHz = 10499.5
    public static let beaconMHz = 10491.5
    public static let beaconRateKs = 1500.0

    /// Through the LNB on QO-100's downlink (the console's onQo). An offset
    /// alone is not enough: HamTV's downconverter is an offset too, and its
    /// LO is no LNB's.
    public static func onQO100(_ r: Receiver) -> Bool {
        r.offsetMHz > 0 && (r.frequencyMHz ?? 0) >= 10000
    }

    /// The LNB offset to tune a QO-100 frequency with: the receiver's own
    /// while it is already on QO-100 (an operator's measured LO, 9739.9
    /// say, must survive a change of station), else the box's LNB setting.
    public static func offset(for r: Receiver, lnbLO: Double) -> Double {
        onQO100(r) ? r.offsetMHz : (lnbLO > 0 ? lnbLO : 9750)
    }

    /// What one tune to a QO-100 frequency sends: the frequency, the rate
    /// and the LNB offset together, in one /api/rx/tune, so the box restarts
    /// its tuner once and never sits on the beacon at a station's 333 kS (an
    /// Android TV did, with the rate left to a second step that never came).
    /// The rate is the one given (a signal's tuneSymbolRateKs, a channel's
    /// row), else what Tuning.rate(movingTo:) picks for that frequency.
    public static func tunePlan(frequencyMHz f: Double, symbolRateKs: Double?, receiver r: Receiver,
                                lnbLO: Double) -> (frequencyMHz: Double, symbolRateKs: Double?, offsetMHz: Double) {
        let o = offset(for: r, lnbLO: lnbLO)
        return (f, symbolRateKs ?? Tuning.rate(movingTo: f, offsetMHz: o, current: r.symbolRateKs), o)
    }

    /// The same for a signal the box found: always at its standard rate.
    public static func tunePlan(_ s: QO100Signal, receiver r: Receiver,
                                lnbLO: Double) -> (frequencyMHz: Double, symbolRateKs: Double?, offsetMHz: Double) {
        tunePlan(frequencyMHz: s.freqMHz, symbolRateKs: s.tuneSymbolRateKs, receiver: r, lnbLO: lnbLO)
    }

    /// What a tap where nothing was found offers: the nearest 12.5 kHz, at
    /// the receiver's own rate unless that is still the beacon's 1500 (most
    /// stations are 333 or less).
    public static func freeTune(atMHz f: Double, currentRate: Double?) -> (frequencyMHz: Double, symbolRateKs: Double) {
        let fr = (f * 80).rounded() / 80
        let cur = (currentRate ?? 0) > 0 ? currentRate! : 333
        return (fr, cur > 1000 ? 333 : cur)
    }

    /// The receivers tuned to a signal now: through the LNB, within 20 kHz,
    /// at its tune rate.
    public static func tuned(to s: QO100Signal, receivers: [Receiver]) -> [Int] {
        receivers.filter { $0.enabled && $0.offsetMHz > 0 && abs(($0.frequencyMHz ?? 0) - s.freqMHz) < 0.02
            && $0.symbolRateKs == s.tuneSymbolRateKs }.map(\.id)
    }

    /// The line over the spectrum (the console's #qostate). `snapshot` nil:
    /// nothing has come back from the box yet; `boxProblem`: the box itself
    /// did not answer. `verb` is how a signal is chosen on this device.
    public static func stateWords(_ q: QO100Snapshot?, boxProblem: String? = nil, verb: String = "tap") -> (text: String, problem: Bool) {
        if let boxProblem { return (boxProblem, true) }
        guard let q else { return ("Asking the box for BATC's spectrum…", false) }
        if q.live {
            let n = q.signals.count
            return ("Live from BATC's monitor at Goonhilly · \(n) signal\(n == 1 ? "" : "s"): \(verb) one to receive it"
                    + (q.outOfBand ? " · something out of band at the top" : ""), false)
        }
        if !q.error.isEmpty { return (q.error, true) }
        if let a = q.age { return ("No new spectrum for \(Int(a.rounded())) s", true) }
        return ("The box is connecting to BATC…", false)
    }

    /// The credit for the picture, which is BATC's: their monitor, their
    /// receiver at Goonhilly, fetched by the box.
    public static func credit(_ q: QO100Snapshot?) -> String {
        let source = q?.source ?? "BATC QO-100 wideband spectrum monitor"
        let url = (q?.sourceURL ?? "https://eshail.batc.org.uk/wb/")
            .replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
        return "Spectrum: \(source), \(url) - British Amateur Television Club, by way of the box."
    }

    /// The waterfall's colours (the console's WF_LUT): the floor dark,
    /// through blue and cyan to yellow and red at 15 dB and over, so the
    /// beacon and a strong station stand out and the noise does not.
    public static let waterfallPalette: [UInt8] = {
        let stops: [(Double, [Double])] = [(-3, [8, 12, 28]), (0.5, [10, 20, 70]), (3, [20, 70, 190]), (6, [20, 190, 220]),
                                           (10, [250, 230, 60]), (15, [240, 60, 30])]
        var lut = [UInt8](repeating: 0, count: 256 * 3)
        for b in 0..<256 {
            let db = -3 + Double(b) * 0.125
            var k = 0
            while k < stops.count - 2 && db > stops[k + 1].0 { k += 1 }
            let (d0, c0) = stops[k], (d1, c1) = stops[k + 1]
            let t = max(0, min(1, (db - d0) / (d1 - d0)))
            for j in 0..<3 { lut[b * 3 + j] = UInt8((c0[j] + (c1[j] - c0[j]) * t).rounded(.down)) }
        }
        return lut
    }()
}

/// The waterfall kept on this device: up to maxRows, newest first, added to
/// from each poll's `waterfall` (only the rows not yet had, after `since`).
public struct QO100WaterfallBuffer: Equatable, Sendable {
    public private(set) var rows: [[UInt8]] = []
    /// The newest row held, sent back as ?wf= so only newer ones come.
    public private(set) var since = 0
    public private(set) var maxRows = 180

    public init() {}

    public mutating func add(_ wf: QO100Waterfall) {
        maxRows = max(1, wf.maxRows)
        if wf.full { rows = [] }
        // A change of width (a different feed) would draw nonsense under the old rows.
        if let w = wf.rows.last?.count, let had = rows.first?.count, w != had { rows = [] }
        rows.insert(contentsOf: wf.rows.reversed(), at: 0)
        if rows.count > maxRows { rows.removeLast(rows.count - maxRows) }
        since = wf.seq
    }

    /// RGBA, `width` x `maxRows`, newest row at the top and the rows not
    /// yet received the floor's colour - ready for a CGImage.
    public func rgba() -> (width: Int, height: Int, bytes: [UInt8])? {
        guard let w = rows.first?.count, w > 0 else { return nil }
        let h = maxRows, lut = QO100.waterfallPalette
        var out = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            let row = y < rows.count ? rows[y] : nil
            for x in 0..<w {
                let b = Int(row.map { x < $0.count ? $0[x] : 0 } ?? 0), o = (y * w + x) * 4
                out[o] = lut[b * 3]; out[o + 1] = lut[b * 3 + 1]; out[o + 2] = lut[b * 3 + 2]; out[o + 3] = 255
            }
        }
        return (w, h, out)
    }
}

/// Gives each signal an id that survives the next frame, so a list row
/// keeps its place - and on the Apple TV, the focus - while the box's
/// measured centre wanders by a 12.5 kHz step from one second to the next.
/// A signal is the one before if it overlaps it and is the same kind
/// (beacon or not); anything else is new.
public struct QO100SignalIdentity: Sendable {
    private var last: [(id: Int, signal: QO100Signal)] = []
    private var next = 1

    public init() {}

    public mutating func identify(_ signals: [QO100Signal]) -> [Int] {
        var used = Set<Int>()
        var out: [(id: Int, signal: QO100Signal)] = []
        for s in signals {
            let match = last.filter { !used.contains($0.id) && $0.signal.beacon == s.beacon
                && $0.signal.lowMHz <= s.highMHz && s.lowMHz <= $0.signal.highMHz }
                .min { abs($0.signal.freqMHz - s.freqMHz) < abs($1.signal.freqMHz - s.freqMHz) }
            let id: Int
            if let match { id = match.id } else { id = next; next += 1 }
            used.insert(id)
            out.append((id, s))
        }
        last = out
        return out.map(\.id)
    }
}
