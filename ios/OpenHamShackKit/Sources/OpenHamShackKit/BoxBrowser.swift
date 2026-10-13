import Foundation
import Network
import Observation

/// OpenHamShackes advertising themselves on the local network.
///
/// The box publishes `_openhamshack._tcp` through avahi (REMOTE-API.md 1): the
/// instance name is its callsign (or host name), and the TXT record carries
/// name, version and port. A box imaged before 0.9.17 may not advertise
/// until its OS update has run, and nothing crosses a tailnet, so typing
/// an address stays the fallback. Needs NSBonjourServices and
/// NSLocalNetworkUsageDescription in Info.plist, or iOS refuses to browse.
@MainActor
@Observable
public final class BoxBrowser {
    public static let serviceType = "_openhamshack._tcp"

    public struct Found: Identifiable, Hashable, Sendable {
        public var id: String { name }
        /// The service instance name: the callsign, or the box's host name.
        public let name: String
        /// TXT version= (the OpenHamShack release), when advertised.
        public let version: String?
        /// TXT port=, for showing; the address comes from resolving.
        public let port: Int?
        let endpoint: NWEndpoint

        public init(name: String, version: String?, port: Int?, endpoint: NWEndpoint) {
            self.name = name
            self.version = version
            self.port = port
            self.endpoint = endpoint
        }

        /// From an instance name and its TXT record.
        static func make(name: String, txt: [String: String], endpoint: NWEndpoint) -> Found {
            let shown = txt["name"].flatMap { $0.isEmpty ? nil : $0 } ?? name
            return Found(name: shown, version: txt["version"].flatMap { $0.isEmpty ? nil : $0 },
                         port: txt["port"].flatMap(Int.init), endpoint: endpoint)
        }
    }

    public private(set) var found: [Found] = []
    public private(set) var problem: String?
    private var browser: NWBrowser?

    public init() {}

    public func start() {
        guard browser == nil else { return }
        let params = NWParameters.tcp
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let list = results.compactMap { r -> Found? in
                guard case .service(let name, _, _, _) = r.endpoint else { return nil }
                var txt: [String: String] = [:]
                if case .bonjour(let record) = r.metadata { txt = record.dictionary }
                return Found.make(name: name, txt: txt, endpoint: r.endpoint)
            }.sorted { $0.name < $1.name }
            Task { @MainActor in self?.found = list }
        }
        b.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                if case .failed(let e) = state { self?.problem = "Looking for boxes failed: \(e)" }
                if case .waiting(let e) = state { self?.problem = "Not allowed to look for boxes on this network (\(e))" }
                if case .ready = state { self?.problem = nil }
            }
        }
        b.start(queue: .main)
        browser = b
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }

    /// The address a found box answers on: connect once, read the far end,
    /// hang up. A Bonjour name would do for HTTP, but VLC and libsrt resolve
    /// names themselves and .local names are slow or absent there; an
    /// address works everywhere.
    public nonisolated static func resolve(_ found: Found, timeout: TimeInterval = 5) async throws -> BoxAddress {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<BoxAddress, Error>) in
            // IPv4 where the box has it: a link-local IPv6 answer needs its
            // zone, which a URL typed into VLC or libsrt does not keep, and
            // HaishinKit's SRT is IPv4 only.
            let params = NWParameters.tcp
            if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
            let conn = NWConnection(to: found.endpoint, using: params)
            let once = Once()
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if case .hostPort(let host, let port)? = conn.currentPath?.remoteEndpoint {
                        var h = "\(host)"
                        if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) }   // drop an IPv6 zone
                        once.run { cont.resume(returning: BoxAddress(host: h, port: Int(port.rawValue))) }
                    } else {
                        once.run { cont.resume(throwing: OpenHamShackError.network("Could not find that box's address")) }
                    }
                    conn.cancel()
                case .failed(let e):
                    once.run { cont.resume(throwing: OpenHamShackError.network("Could not reach \(found.name): \(e)")) }
                    conn.cancel()
                default:
                    break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                once.run { cont.resume(throwing: OpenHamShackError.network("\(found.name) did not answer")) }
                conn.cancel()
            }
        }
    }
}

final class Once: @unchecked Sendable {
    private var done = false
    private let lock = NSLock()
    func run(_ f: () -> Void) {
        let first: Bool = lock.withLock {
            if done { return false }
            done = true
            return true
        }
        if first { f() }
    }
}
