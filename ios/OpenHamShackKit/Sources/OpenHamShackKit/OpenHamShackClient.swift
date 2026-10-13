import Foundation

public enum OpenHamShackError: LocalizedError, Equatable {
    /// The box answered with an error; message is its own "error" text.
    case http(Int, String)
    /// 403: this client may only look (no PIN session, or a wrong PIN).
    case forbidden(String)
    case badResponse(String)
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .http(_, let m), .forbidden(let m), .badResponse(let m), .network(let m): return m
        }
    }
}

/// The console API, as the box's own browser console uses it.
///
/// Every request goes to the one BoxAddress the operator chose. The PIN
/// session is the `ohssession` cookie the box sets on POST /api/login; it is
/// held here per box and sent by hand, never through a shared cookie store,
/// because two boxes (or one box by two addresses) must not see each other's
/// session, and the player and SRT sender need none.
public final class OpenHamShackClient: @unchecked Sendable {
    public let address: BoxAddress
    private let lock = NSLock()
    private var _session: String?
    private let urlSession: URLSession

    public init(address: BoxAddress, session: String? = nil) {
        self.address = address
        self._session = session
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpShouldSetCookies = false
        cfg.httpCookieAcceptPolicy = .never
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        // A request waits for its own timeout, not for a network to appear:
        // a key-down that cannot be sent must fail quickly so it is retried.
        cfg.waitsForConnectivity = false
        cfg.timeoutIntervalForRequest = 5
        // One box, a handful of requests: a status poll stuck behind a slow
        // POST would freeze the ON AIR indicator.
        cfg.httpMaximumConnectionsPerHost = 4
        urlSession = URLSession(configuration: cfg)
    }

    public var session: String? {
        get { lock.withLock { _session } }
        set { lock.withLock { _session = newValue } }
    }

    /// The received picture of receiver rx, as an MPEG-TS over HTTP that never
    /// ends (GET /api/rx/stream.ts). Open like /api/status: no session needed.
    public func streamURL(receiver rx: Int) -> URL {
        var c = URLComponents(url: address.baseURL.appendingPathComponent("api/rx/stream.ts"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "rx", value: String(rx))]
        return c.url!
    }

    // MARK: - The calls

    public func status() async throws -> BoxStatus {
        try BoxStatus.parse(try await request("GET", "/api/status", timeout: 4))
    }

    /// POST /api/login {"pin"}: the session token from Set-Cookie, which is
    /// also kept for every later request. Five wrong PINs in a minute lock
    /// remote sign-in on the box for that minute; its message says so.
    @discardableResult
    public func login(pin: String) async throws -> String {
        let (data, response) = try await send("POST", "/api/login", body: ["pin": .string(pin)], timeout: 6)
        guard response.statusCode == 200 else { throw Self.error(data, response.statusCode) }
        let headers = response.allHeaderFields
        for (k, v) in headers {
            guard let name = k as? String, name.lowercased() == "set-cookie", let value = v as? String else { continue }
            if let token = Self.sessionFromCookie(value) {
                session = token
                return token
            }
        }
        throw OpenHamShackError.badResponse("OpenHamShack accepted the PIN but sent no session")
    }

    /// POST /api/logout: ends this session on the box (and only it). Safe in
    /// any state: answered 200 with no session or one already ended. The
    /// token is forgotten here whatever the box says.
    public func logout() async {
        if session != nil { _ = try? await request("POST", "/api/logout", body: [:], timeout: 4) }
        session = nil
    }

    /// GET /api/tx/options: the transmit choices.
    public func txOptions() async throws -> TxOptions {
        TxOptions(try JSON(data: try await request("GET", "/api/tx/options")))
    }

    /// POST /api/tx/tune: any subset of frequencyMHz, symbolRateKs, mode,
    /// constellation, fec, gainDb. 400 refuses the whole patch, nothing
    /// saved; the box's sentence is the error.
    @discardableResult
    public func txTune(_ patch: [String: JSON]) async throws -> BoxStatus {
        try BoxStatus.parse(try await request("POST", "/api/tx/tune", body: patch))
    }

    /// POST /api/config with a patch of settings.
    @discardableResult
    public func config(_ patch: [String: JSON]) async throws -> BoxStatus {
        try BoxStatus.parse(try await request("POST", "/api/config", body: patch))
    }

    /// POST /api/rx/tune: one receiver's frequency, symbol rate, LNB offset.
    @discardableResult
    public func tune(receiver id: Int, frequencyMHz: Double? = nil, symbolRateKs: Double? = nil,
                     offsetMHz: Double? = nil) async throws -> BoxStatus {
        var body: [String: JSON] = ["id": .number(Double(id))]
        if let f = frequencyMHz { body["frequencyMHz"] = .number(f) }
        if let s = symbolRateKs { body["symbolRateKs"] = .number(s) }
        if let o = offsetMHz { body["offsetMHz"] = .number(o) }
        return try BoxStatus.parse(try await request("POST", "/api/rx/tune", body: body))
    }

    /// POST /api/rx/select: the receiver the box itself shows and plays.
    public func selectReceiver(_ id: Int) async throws {
        _ = try await request("POST", "/api/rx/select", body: ["id": .number(Double(id))])
    }

    /// POST /api/ptt. With remote, a key-up is held under that id's lease.
    @discardableResult
    public func ptt(on: Bool, remote: String? = nil, force: Bool = false) async throws -> BoxStatus {
        var body: [String: JSON] = ["on": .bool(on)]
        if let remote { body["remote"] = .string(remote) }
        if force { body["force"] = .bool(true) }
        // A key-up can take the box several seconds (a LibreSDR set over its
        // own web page, the QO-100 busy check waiting on BATC): REMOTE-API.md
        // asks for at least 30 s. A key-down must not wait that long to be
        // retried.
        return try BoxStatus.parse(try await request("POST", "/api/ptt", body: body, timeout: on ? 35 : 3))
    }

    /// POST /api/ptt/heartbeat. true: still keyed under our lease. false:
    /// the box answered 409 - it is no longer transmitting for us.
    public func heartbeat(remote: String) async throws -> Bool {
        let (data, response) = try await send("POST", "/api/ptt/heartbeat", body: ["remote": .string(remote)], timeout: 2)
        switch response.statusCode {
        case 200: return true
        case 409: return false
        default: throw Self.error(data, response.statusCode)
        }
    }

    /// POST /api/ptt/keep: a fresh transmit time-out period.
    @discardableResult
    public func keepTransmitting() async throws -> BoxStatus {
        try BoxStatus.parse(try await request("POST", "/api/ptt/keep", body: [:]))
    }

    /// GET /api/stream/larix: the Stream source's SRT facts (the passphrase
    /// only with a PIN session).
    public func streamTarget() async throws -> StreamTarget {
        StreamTarget(try JSON(data: try await request("GET", "/api/stream/larix")))
    }

    /// GET /api/qo100: BATC's QO-100 wideband spectrum and the signals in
    /// it, as the box has them (QO100.swift). Open like /api/status. With
    /// waterfallSince, also the waterfall rows after that sequence number
    /// (0 for all of them). Polling this is what keeps the box's own
    /// connection to BATC open; the app never opens one.
    public func qo100(waterfallSince: Int? = nil) async throws -> QO100Snapshot {
        let q = waterfallSince.map { [URLQueryItem(name: "wf", value: String(max(0, $0)))] }
        return try QO100Snapshot.parse(try await request("GET", "/api/qo100", query: q, timeout: 4))
    }

    // MARK: - Plumbing

    private func request(_ method: String, _ path: String, query: [URLQueryItem]? = nil, body: [String: JSON]? = nil,
                         timeout: TimeInterval = 6) async throws -> Data {
        let (data, response) = try await send(method, path, query: query, body: body, timeout: timeout)
        guard (200..<300).contains(response.statusCode) else { throw Self.error(data, response.statusCode) }
        return data
    }

    private func send(_ method: String, _ path: String, query: [URLQueryItem]? = nil, body: [String: JSON]?,
                      timeout: TimeInterval) async throws -> (Data, HTTPURLResponse) {
        var url = address.baseURL.appendingPathComponent(String(path.dropFirst()))
        if let query, var c = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            c.queryItems = query
            url = c.url ?? url
        }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        if let token = session { req.setValue("ohssession=\(token)", forHTTPHeaderField: "Cookie") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = JSON.object(body).encoded()
        }
        do {
            let (data, response) = try await urlSession.data(for: req)
            guard let http = response as? HTTPURLResponse else { throw OpenHamShackError.badResponse("Not an HTTP answer") }
            return (data, http)
        } catch let e as OpenHamShackError {
            throw e
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw OpenHamShackError.network(Self.networkWords(error))
        }
    }

    static func error(_ data: Data, _ code: Int) -> OpenHamShackError {
        let text = (try? JSON(data: data))?["error"].string ?? "OpenHamShack answered \(code)"
        return code == 403 ? .forbidden(text) : .http(code, text)
    }

    static func networkWords(_ error: Error) -> String {
        guard let e = error as? URLError else { return "OpenHamShack did not answer" }
        switch e.code {
        case .timedOut: return "OpenHamShack did not answer in time"
        case .cannotFindHost, .dnsLookupFailed: return "No box by that name on this network"
        case .cannotConnectToHost: return "OpenHamShack refused the connection (is it on, and the port right?)"
        case .notConnectedToInternet, .networkConnectionLost: return "No network"
        case .appTransportSecurityRequiresSecureConnection: return "Blocked by App Transport Security"
        default: return "OpenHamShack did not answer (\(e.code.rawValue))"
        }
    }

    /// "ohssession=TOKEN; Path=/; ..." -> TOKEN. Foundation may fold several
    /// Set-Cookie headers into one, comma-separated, so each piece is tried.
    static func sessionFromCookie(_ header: String) -> String? {
        for cookie in header.components(separatedBy: ",") {
            let first = cookie.split(separator: ";", maxSplits: 1).first.map(String.init) ?? ""
            let kv = first.split(separator: "=", maxSplits: 1)
            if kv.count == 2, kv[0].trimmingCharacters(in: .whitespaces) == "ohssession" {
                let v = kv[1].trimmingCharacters(in: .whitespaces)
                if !v.isEmpty { return v }
            }
        }
        return nil
    }
}
