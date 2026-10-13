import Foundation

/// GET /api/tx/options: the transmit choices the console offers, from the
/// box itself - so the app never offers a FEC the modulation lacks, or a
/// rate list the band plan does not have, and follows the box when those
/// change. Open like /api/status.
public struct TxOptions: Equatable, Sendable {
    public struct Preset: Equatable, Hashable, Sendable {
        public var label: String
        public var frequencyMHz: Double
    }

    public var presets: [Preset]
    public var qo100Rates: [Double]
    public var terrestrialRates: [Double]
    public var modes: [String]
    /// mode -> constellations
    public var constellations: [String: [String]]
    /// DVB-S's FEC list.
    public var fecDVBS: [String]
    /// DVB-S2's FEC lists per constellation.
    public var fecDVBS2: [String: [String]]
    public var gainMin: Double
    public var gainMax: Double
    public var gainStep: Double
    /// QO-100's uplink range; inside it the QO-100 rates apply.
    public var qo100Uplink: ClosedRange<Double>?
    /// Where a key-up is allowed; nil when the operator chose no band check.
    public var licenceBands: [ClosedRange<Double>]?
    public var licenceRegion: String

    public init(_ j: JSON) {
        presets = j["presets"].array.compactMap { p in
            p["frequencyMHz"].double.map { Preset(label: p["label"].string ?? Tuning.mhz($0), frequencyMHz: $0) }
        }
        qo100Rates = j["symbolRates"]["qo100"].array.compactMap(\.double)
        terrestrialRates = j["symbolRates"]["terrestrial"].array.compactMap(\.double)
        if qo100Rates.isEmpty { qo100Rates = Tuning.qoRates }
        if terrestrialRates.isEmpty { terrestrialRates = Tuning.terrestrialRates }
        modes = j["modes"].array.compactMap(\.string)
        if modes.isEmpty { modes = ["dvbs2", "dvbs"] }
        var cons: [String: [String]] = [:]
        for (k, v) in j["constellations"].object ?? [:] { cons[k] = v.array.compactMap(\.string) }
        constellations = cons.isEmpty ? ["dvbs": ["qpsk"], "dvbs2": ["qpsk", "8psk", "16apsk", "32apsk"]] : cons
        fecDVBS = j["fec"]["dvbs"].array.compactMap(\.string)
        var s2: [String: [String]] = [:]
        for (k, v) in j["fec"]["dvbs2"].object ?? [:] { s2[k] = v.array.compactMap(\.string) }
        fecDVBS2 = s2
        gainMin = j["gainDb"]["min"].double ?? -70
        gainMax = j["gainDb"]["max"].double ?? 0
        gainStep = j["gainDb"]["step"].double ?? 1
        let up = j["qo100"]["uplinkMHz"].array.compactMap(\.double)
        qo100Uplink = up.count == 2 && up[0] <= up[1] ? up[0]...up[1] : nil
        if j["licence"]["bands"].isNull {
            licenceBands = nil
        } else {
            licenceBands = j["licence"]["bands"].array.compactMap { b in
                guard let lo = b[0].double, let hi = b[1].double, lo <= hi else { return nil }
                return lo...hi
            }
        }
        licenceRegion = j["licence"]["region"].string ?? ""
    }

    public func isQO100(_ f: Double?) -> Bool {
        guard let f, let r = qo100Uplink else { return false }
        return r.contains(f)
    }

    /// The rate chips for a transmit frequency, plus the one in use.
    public func rates(for f: Double?, current: Double?) -> [Double] {
        let set = isQO100(f) ? qo100Rates : terrestrialRates
        guard let c = current, c > 0, !set.contains(c) else { return set }
        return (set + [c]).sorted()
    }

    public func fecs(mode: String, constellation: String) -> [String] {
        mode == "dvbs" ? fecDVBS : (fecDVBS2[constellation] ?? [])
    }

    /// The licence band a frequency falls in, for showing; nil if none or no check.
    public func licenceBand(containing f: Double?) -> ClosedRange<Double>? {
        guard let f, let bands = licenceBands else { return nil }
        return bands.first { $0.contains(f) }
    }
}

/// status.txTune, in a POST /api/tx/tune answer: what changed, and which of
/// those wait for the next key-up (everything but power, while on air).
public struct TxTuneResult: Equatable, Sendable {
    public var changed: [String: JSON]
    public var nextKeyUp: [String]

    init?(_ j: JSON) {
        guard j.object != nil else { return nil }
        changed = j["changed"].object ?? [:]
        nextKeyUp = j["nextKeyUp"].array.compactMap(\.string)
    }

    static let words = ["frequencyMHz": "frequency", "symbolRateKs": "symbol rate", "mode": "mode",
                        "constellation": "modulation", "fec": "FEC", "gainDb": "power"]

    /// What waits for the next key-up. The box's own list; if it gave none
    /// while on air, the contract's rule (everything but power) - a box
    /// whose transmitter is faked, as fakebox's is, reports none.
    public func waiting(onAir: Bool) -> [String] {
        if !nextKeyUp.isEmpty || !onAir { return nextKeyUp }
        return Self.words.keys.filter { $0 != "gainDb" && changed[$0] != nil }.sorted { order($0) < order($1) }
    }

    private func order(_ k: String) -> Int {
        ["frequencyMHz", "symbolRateKs", "mode", "constellation", "fec"].firstIndex(of: k) ?? 9
    }

    /// "Frequency and FEC apply at the next key-up." or nil when nothing waits.
    public var nextKeyUpWords: String? { words(for: nextKeyUp) }

    public func nextKeyUpWords(onAir: Bool) -> String? { words(for: waiting(onAir: onAir)) }

    private func words(for keys: [String]) -> String? {
        guard !keys.isEmpty else { return nil }
        let names = keys.map { Self.words[$0] ?? $0 }
        let list = names.count == 1 ? names[0] : names.dropLast().joined(separator: ", ") + " and " + names.last!
        return list.prefix(1).uppercased() + list.dropFirst() + " \(names.count == 1 ? "applies" : "apply") at the next key-up; the transmission carries on as it was."
    }
}
