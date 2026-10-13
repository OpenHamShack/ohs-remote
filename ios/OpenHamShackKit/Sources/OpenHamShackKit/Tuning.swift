import Foundation

/// The receive bands and symbol rates the console offers, in one place.
///
/// The numbers are the console's (index.html's receiver presets, sheets.js
/// SR_SETS): the app offers the same choices as the box's own screen so an
/// operator moving between them finds the same chips.
public enum Tuning {
    public struct Band: Identifiable, Hashable, Sendable {
        public var id: String { name }
        public let name: String
        /// The preset frequency the band's chip tunes to, in MHz.
        public let frequencyMHz: Double
        /// QO-100: received through the LNB, so tuned with the LNB offset.
        public let viaLNB: Bool
        public let lowMHz: Double
        public let highMHz: Double
    }

    public static let bands: [Band] = [
        Band(name: "2 m", frequencyMHz: 146.5, viaLNB: false, lowMHz: 144, highMHz: 148),
        Band(name: "70 cm", frequencyMHz: 437.0, viaLNB: false, lowMHz: 430, highMHz: 440),
        Band(name: "23 cm", frequencyMHz: 1255.0, viaLNB: false, lowMHz: 1240, highMHz: 1325),
        Band(name: "QO-100", frequencyMHz: 10491.5, viaLNB: true, lowMHz: 10490, highMHz: 10500),
    ]

    /// The band chips of the receive panels. QO-100 is not among them: it
    /// has a screen of its own (the spectrum, the signals on it, the band
    /// plan), and its beacon chip moved there. `bands` keeps it so that a
    /// frequency on the transponder is still named "QO-100".
    public static var terrestrialBands: [Band] { bands.filter { !$0.viaLNB } }

    public static let qoRates: [Double] = [33, 66, 125, 250, 333, 500, 1000, 1500]
    public static let terrestrialRates: [Double] = [125, 250, 333, 500, 1000, 1500, 2000, 3000, 4000]

    public static func band(for frequencyMHz: Double?) -> Band? {
        guard let f = frequencyMHz else { return nil }
        return bands.first { f >= $0.lowMHz && f <= $0.highMHz }
    }

    /// The rate chips for a receiver: QO-100's when it is tuned through an
    /// LNB, the terrestrial ones otherwise, plus the rate in use if it is not
    /// among them (sheets.js srChips).
    public static func rates(viaLNB: Bool, current: Double?) -> [Double] {
        let set = viaLNB ? qoRates : terrestrialRates
        guard let c = current, c > 0, !set.contains(c) else { return set }
        return (set + [c]).sorted()
    }

    /// The rate to send with a move to f when none was chosen, or nil to
    /// leave the receiver's own. A frequency typed or nudged across into
    /// another band must not keep a rate that band does not use: the
    /// QO-100 beacon's segment (below 10492.0, where BATC's detector and the
    /// band plan allow nothing else) is always 1500 kS; elsewhere on QO-100
    /// a terrestrial rate becomes the nearest QO-100 one, and the other way
    /// about, nearest by ratio as symbol rates are spaced.
    public static func rate(movingTo f: Double, offsetMHz: Double, current: Double?) -> Double? {
        let viaLNB = offsetMHz > 0 && f >= 10000
        if viaLNB, f >= QO100.lowMHz - 0.5, f < 10492.0 { return current == QO100.beaconRateKs ? nil : QO100.beaconRateKs }
        let set = viaLNB ? qoRates : terrestrialRates
        guard let c = current, c > 0 else { return 333 }
        if set.contains(c) { return nil }
        return set.min { abs(log($0 / c)) < abs(log($1 / c)) }
    }

    /// Which LNB offset a typed frequency means (receivers.js rxOffsetFor):
    /// 10 GHz and up is through the LNB; anything at or below its LO is not.
    public static func offsetFor(frequencyMHz n: Double, currentOffset: Double, lnbLO: Double) -> Double {
        let lnb = currentOffset
        if n >= 10000 { return lnb > 0 ? lnb : lnbLO }
        return n <= lnb ? 0 : lnb
    }

    /// The steps a TV remote's left/right nudges a frequency by. A remote
    /// has no keypad worth using, so after a band chip the way to a
    /// frequency is a few presses: 1 MHz to cross a band, 250 kHz between
    /// channels, 25 kHz to centre on one.
    public static let nudgeSteps: [Double] = [0.025, 0.25, 1]

    /// `f` moved by `presses` steps (negative is down), to the nearest kHz so
    /// that forty presses of 0.025 land on a round number and not on
    /// 437.99999999. Never at or below zero: the box would refuse it, and a
    /// remote held down should stop rather than wrap.
    public static func nudged(_ f: Double, step: Double, presses: Int) -> Double {
        let kHz = ((f + step * Double(presses)) * 1000).rounded()
        return max(step, kHz / 1000)
    }

    /// A frequency typed by an operator, in MHz: a decimal comma accepted
    /// (a TV keyboard may offer only one), nothing that is not a positive
    /// number.
    public static func parseMHz(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let f = Double(t), f > 0, f.isFinite else { return nil }
        return f
    }

    public static func mhz(_ f: Double?) -> String {
        guard let f else { return "—" }
        return String(format: "%.3f", f)
    }

    /// A QO-100 downlink frequency as I18N.md §4.1 has every OpenHamShack
    /// app show one: MHz to three decimals (kHz resolution, so a column of
    /// them keeps its width), the locale's decimal separator, never grouped,
    /// the unit after a no-break space - "10491.500 MHz", or "10491,500 MHz"
    /// in German. `locale` is a tag such as `Locale.current.identifier`. The
    /// box reports MHz as a Double; it is rounded to whole Hz, the unit
    /// ShackFormat counts in. ShackFormat refuses only a bad unit or a
    /// non-finite number, neither of which can reach it from here, so the
    /// plain `mhz` text is a fallback that never shows in practice.
    public static func downlink(_ f: Double, locale: String) -> String {
        guard f.isFinite, let text = try? ShackFormat.frequency(Int64((f * 1_000_000).rounded()), locale: locale, unit: "MHz")
        else { return mhz(f) }
        return text
    }

    public static func ks(_ s: Double?) -> String {
        guard let s else { return "—" }
        return s == s.rounded() ? String(Int(s)) : String(format: "%.1f", s)
    }
}

/// A place the TV's up/down steps to: one of the box's receivers as it is
/// tuned now, or a preset applied to the receiver being watched.
public struct Channel: Identifiable, Hashable, Sendable {
    public enum Kind: Hashable, Sendable {
        case receiver(Int)
        case preset(frequencyMHz: Double, symbolRateKs: Double, viaLNB: Bool)
    }

    public let id: String
    public let kind: Kind
    public let title: String

    public static func list(status: BoxStatus, canTune: Bool) -> [Channel] {
        var out = status.receivers.filter(\.enabled).map {
            Channel(id: "rx\($0.id)", kind: .receiver($0.id), title: "Receiver \($0.id)")
        }
        guard canTune else { return out }
        let presets: [(String, Double, Double, Bool)] = [
            ("2 m 146.500 · 333 kS", 146.5, 333, false),
            ("70 cm 437.000 · 333 kS", 437.0, 333, false),
            ("23 cm 1255.000 · 2000 kS", 1255.0, 2000, false),
            ("QO-100 beacon · 1500 kS", 10491.5, 1500, true),
            ("QO-100 10499.25 · 333 kS", 10499.25, 333, true),
        ]
        out += presets.map {
            Channel(id: "p\($0.1)-\($0.2)", kind: .preset(frequencyMHz: $0.1, symbolRateKs: $0.2, viaLNB: $0.3), title: $0.0)
        }
        return out
    }
}
