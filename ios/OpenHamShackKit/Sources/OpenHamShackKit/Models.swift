import Foundation

// The parts of GET /api/status the remote shows, read from the loose JSON
// tree (see JSON.swift for why not Codable). Field names follow the box's,
// so a reader can grep openhamshack.py for any of them. The raw tree is kept on
// BoxStatus for anything not modelled here.

/// status.auth: what this client may do. The console's rule, kept exactly:
/// the box's own screen (local) and a client with a PIN session
/// (authorised) may change things; anyone else sees everything and changes
/// nothing.
public struct AuthInfo: Equatable, Sendable {
    public var local: Bool
    public var authorised: Bool
    public var pinSet: Bool

    public init(local: Bool = false, authorised: Bool = false, pinSet: Bool = false) {
        self.local = local
        self.authorised = authorised
        self.pinSet = pinSet
    }

    init(_ j: JSON) {
        local = j["local"].bool ?? false
        authorised = j["authorised"].bool ?? false
        pinSet = j["pinSet"].bool ?? false
    }

    public var canControl: Bool { local || authorised }
}

public struct VideoInfo: Equatable, Sendable {
    public var width: Int?
    public var height: Int?
    public var codec: String?
    public var fps: Double?

    init?(_ j: JSON) {
        guard j.object != nil else { return nil }
        width = j["width"].int
        height = j["height"].int
        codec = j["codec"].string
        fps = j["fps"].double
    }

    public var summary: String {
        var parts: [String] = []
        if let w = width, let h = height { parts.append("\(w)×\(h)") }
        if let c = codec { parts.append(c) }
        if let f = fps { parts.append("\(Int(f.rounded())) fps") }
        return parts.joined(separator: " ")
    }
}

/// One entry of status.receivers (receiver 1 is also status.rx).
public struct Receiver: Equatable, Identifiable, Sendable {
    public var id: Int
    public var tuner: String
    public var kind: String
    public var enabled: Bool
    public var locked: Bool
    public var label: String
    public var mer: Double?
    public var marginDb: Double?
    public var needDb: Double?
    public var name: String?
    public var provider: String?
    public var modcod: String?
    public var frequencyMHz: Double?
    public var symbolRateKs: Double?
    public var offsetMHz: Double
    public var heard: Bool
    public var video: VideoInfo?
    /// rx.band: the margin's colour ("good", "marginal", "bad"), not a
    /// radio band (REMOTE-API.md section 4).
    public var marginBand: String?

    public init(_ j: JSON, fallbackID: Int = 1) {
        id = j["id"].int ?? fallbackID
        tuner = j["tuner"].string ?? j["usbTuner"].string ?? "Receiver"
        kind = j["kind"].string ?? ""
        enabled = j["enabled"].bool ?? true
        locked = j["locked"].bool ?? false
        label = j["label"].string ?? ""
        mer = j["mer"].double
        marginDb = j["marginDb"].double
        needDb = j["needDb"].double
        name = j["name"].string.flatMap { $0.isEmpty ? nil : $0 }
        provider = j["provider"].string.flatMap { $0.isEmpty ? nil : $0 }
        modcod = j["modcod"].string
        frequencyMHz = j["frequencyMHz"].double
        symbolRateKs = j["symbolRateKs"].double
        offsetMHz = j["offsetMHz"].double ?? 0
        heard = j["heard"].bool ?? false
        video = VideoInfo(j["video"])
        marginBand = j["band"].string
    }

    /// The console's one-line state (receivers.js rxLine).
    public var line: String {
        if !enabled { return "Off" }
        if !locked { return label.isEmpty ? "—" : label }
        return [name ?? "Locked", mer.map { String(format: "MER %.1f dB", $0) }].compactMap { $0 }.joined(separator: " · ")
    }
}

/// status.txTimeout while keyed with a time-out set.
public struct TxTimeout: Equatable, Sendable {
    public var minutes: Int
    public var secondsLeft: Int
    public var warning: Bool

    init?(_ j: JSON) {
        guard j.object != nil, let left = j["secondsLeft"].int else { return nil }
        minutes = j["minutes"].int ?? 0
        secondsLeft = left
        warning = j["warning"].bool ?? (left <= 60)
    }
}

/// status.tx.remote: the remote lease, while one is held.
public struct LeaseView: Equatable, Sendable {
    public var id: String
    public var secondsLeft: Int
    public var leaseSeconds: Int

    init?(_ j: JSON) {
        guard let id = j["id"].string else { return nil }
        self.id = id
        secondsLeft = j["secondsLeft"].int ?? 0
        leaseSeconds = j["leaseSeconds"].int ?? 5
    }
}

/// status.txCheck: a QO-100 key-up refused as busy or unchecked; the same
/// deliberate action again within a minute sends it anyway (force).
public struct TxCheck: Equatable, Sendable {
    public var kind: String
    public var message: String

    init?(_ j: JSON) {
        guard j.object != nil else { return nil }
        kind = j["kind"].string ?? ""
        message = j["message"].string ?? j["text"].string ?? ""
    }
}

/// status.stream: the Stream (OBS / phone) source's listener.
public struct StreamState: Equatable, Sendable {
    public var proto: String
    public var port: Int
    public var latencyMs: Int
    public var secretSet: Bool
    /// off, listening, receiving, error (stream_status in openhamshack.py).
    public var state: String
    public var message: String
    public var note: String
    public var problem: String
    public var kbps: Double?
    /// What the transmitter is sending: "stream", "card" (the stand-in while
    /// nothing arrives), or nil while not encoding.
    public var onAir: String?

    init(_ j: JSON) {
        proto = j["protocol"].string ?? "srt"
        port = j["port"].int ?? 9710
        latencyMs = j["latencyMs"].int ?? 200
        secretSet = j["secretSet"].bool ?? false
        state = j["state"].string ?? "off"
        message = j["message"].string ?? ""
        note = j["note"].string ?? ""
        problem = j["problem"].string ?? ""
        kbps = j["kbps"].double
        onAir = j["onAir"].string
    }
}

/// status.config: the settings the remote reads. Writes go to /api/config
/// (or /api/rx/tune) as patches; nothing here is ever sent back whole.
public struct BoxConfig: Equatable, Sendable {
    public var callsign: String
    public var frequencyMHz: Double?
    public var symbolRateKs: Double?
    public var mode: String
    public var constellation: String
    public var fec: String
    public var gainDb: Double?
    public var source: String
    public var streamProtocol: String
    public var txTimeoutMin: Int
    public var qo100LnbMHz: Double
    public var rxEnabled: Bool

    init(_ j: JSON) {
        callsign = j["callsign"].string ?? ""
        frequencyMHz = j["frequencyMHz"].double
        symbolRateKs = j["symbolRateKs"].double
        mode = j["mode"].string ?? "dvbs2"
        constellation = j["constellation"].string ?? "qpsk"
        fec = j["fec"].string ?? ""
        gainDb = j["gainDb"].double
        source = j["source"].string ?? "testcard"
        streamProtocol = j["streamProtocol"].string ?? "srt"
        txTimeoutMin = j["txTimeoutMin"].int ?? 0
        qo100LnbMHz = j["qo100LnbMHz"].double ?? 9750
        rxEnabled = j["rxEnabled"].bool ?? true
    }

    /// "DVB-S2 QPSK 2/3", as the console's Transmit mode summary.
    public var modeSummary: String {
        "\(mode == "dvbs" ? "DVB-S" : "DVB-S2") \(constellation.uppercased()) \(fec)"
    }
}

/// The box's picture sources (status.config.source), in the console's words
/// (sheets.js SOURCE_WORDS).
public enum PictureSource: String, CaseIterable, Identifiable, Sendable {
    case testcard, webcam, file, stream
    public var id: String { rawValue }
    public var words: String {
        switch self {
        case .testcard: return "Test card"
        case .webcam: return "Box camera"
        case .file: return "Video file"
        case .stream: return "Stream (this device, OBS)"
        }
    }
}

public struct BoxStatus: Equatable, Sendable {
    public var raw: JSON
    public var onAir: Bool
    public var version: String
    public var config: BoxConfig
    public var auth: AuthInfo
    public var rx: Receiver
    public var receivers: [Receiver]
    /// rxView.selected: the receiver the box itself is showing and playing.
    public var selectedReceiver: Int
    public var txError: String
    public var txBlocked: String
    public var txCheck: TxCheck?
    public var txTimeout: TxTimeout?
    public var onAirSeconds: Int?
    public var lease: LeaseView?
    public var previewing: Bool
    public var stream: StreamState
    public var txNote: String
    /// Only in a POST /api/tx/tune answer.
    public var txTune: TxTuneResult?

    public init(_ j: JSON) {
        raw = j
        onAir = j["onAir"].bool ?? false
        version = j["version"].string ?? ""
        config = BoxConfig(j["config"])
        auth = AuthInfo(j["auth"])
        rx = Receiver(j["rx"], fallbackID: 1)
        var list = j["receivers"].array.map { Receiver($0) }
        if list.isEmpty {
            // A box from before several receivers: receiver 1 is status.rx,
            // tuned by the rx* settings.
            var one = rx
            one.frequencyMHz = j["config"]["rxFrequencyMHz"].double
            one.symbolRateKs = j["config"]["rxSymbolRateKs"].double
            one.offsetMHz = j["config"]["rxOffsetMHz"].double ?? 0
            list = [one]
        }
        receivers = list
        selectedReceiver = j["rxView"]["selected"].int ?? 1
        txError = j["txError"].string ?? ""
        txBlocked = j["txBlocked"].string ?? ""
        txCheck = TxCheck(j["txCheck"])
        txTimeout = TxTimeout(j["txTimeout"])
        onAirSeconds = j["onAirSeconds"].int
        lease = LeaseView(j["tx"]["remote"])
        previewing = j["previewing"].bool ?? false
        stream = StreamState(j["stream"])
        // What the console shows on air beside the transmitter error.
        txTune = TxTuneResult(j["txTune"])
        txNote = j["txMonitor"].string ?? j["txNote"].string ?? j["encoder"]["note"].string ?? ""
    }

    public static func parse(_ data: Data) throws -> BoxStatus {
        let j = try JSON(data: data)
        guard j.object != nil else { throw OpenHamShackError.badResponse("OpenHamShack sent something that is not a status") }
        return BoxStatus(j)
    }

    public func receiver(_ id: Int) -> Receiver? { receivers.first { $0.id == id } }
}

/// GET /api/stream/larix: how a phone sends its camera to the Stream source.
/// port/latencyMs/passphrase/video are the plain fields the box added for
/// the apps; an older box has only the Larix links, so the same facts are
/// read back out of the first link's query when they are missing.
public struct StreamTarget: Equatable, Sendable {
    public var proto: String
    public var port: Int
    public var latencyMs: Int
    /// nil without the PIN, or when none is set (secretSet says which).
    public var passphrase: String?
    public var secretSet: Bool
    public var width: Int
    public var height: Int
    public var fps: Int
    public var kbps: Int

    public init(_ j: JSON) {
        proto = j["protocol"].string ?? "srt"
        secretSet = j["secretSet"].bool ?? false
        let link = j["links"][0]["larix"].string.flatMap(Self.larixQuery) ?? [:]
        let server = link["conn[][url]"].flatMap(URL.init(string:))
        port = j["port"].int ?? server?.port ?? 9710
        latencyMs = j["latencyMs"].int ?? link["conn[][srtlatency]"].flatMap(Int.init) ?? 200
        passphrase = j["passphrase"].string ?? link["conn[][srtpass]"]
        if passphrase?.isEmpty == true { passphrase = nil }
        let res = (link["enc[vid][res]"] ?? "1280x720").split(separator: "x").compactMap { Int($0) }
        width = j["video"]["width"].int ?? (res.count == 2 ? res[0] : 1280)
        height = j["video"]["height"].int ?? (res.count == 2 ? res[1] : 720)
        fps = j["video"]["fps"].int ?? link["enc[vid][fps]"].flatMap(Int.init) ?? 30
        kbps = j["video"]["kbps"].int ?? link["enc[vid][bitrate]"].flatMap(Int.init) ?? 2500
    }

    static func larixQuery(_ s: String) -> [String: String]? {
        guard let q = s.split(separator: "?", maxSplits: 1).dropFirst().first else { return nil }
        var out: [String: String] = [:]
        for pair in q.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            let k = String(kv[0]).removingPercentEncoding ?? String(kv[0])
            if out[k] == nil { out[k] = String(kv[1]).removingPercentEncoding }
        }
        return out
    }
}
