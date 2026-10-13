import Foundation

/// Where a OpenHamShack is: the host and port the operator typed, and nothing else.
///
/// Every URL the app opens - the console API, the receive stream, the SRT
/// stream to the box's Stream source - is built from this one value. The box
/// reports its own addresses (status.stream.urls, the Larix links), but those
/// are the addresses it sees on its own interfaces, and the iPad may be
/// reaching it over the tailnet, through a NAT or by a name; the address that
/// works is the one the operator used. So nothing the box says is ever
/// turned into a URL here.
///
/// The same rules as the Android app's BoxAddress, so one typed address
/// means the same thing on both: plain HTTP only (the box has no
/// certificate); a pasted "http://" and a trailing slash are forgiven;
/// "https://" is refused rather than silently downgraded.
public struct BoxAddress: Hashable, Codable, Sendable, CustomStringConvertible {
    public static let defaultPort = 8080

    public let host: String
    public let port: Int

    public init(host: String, port: Int = BoxAddress.defaultPort) {
        self.host = host
        self.port = port
    }

    /// The host as it goes into a URL: an IPv6 literal in brackets.
    public var urlHost: String { host.contains(":") ? "[\(host)]" : host }

    /// http://host:port, no trailing slash; API paths are appended to it.
    public var baseURL: URL { URL(string: "http://\(urlHost):\(port)")! }

    /// What the operator would type to get this back: host, or host:port.
    public var display: String { port == Self.defaultPort ? urlHost : "\(urlHost):\(port)" }

    public var description: String { display }

    public struct ParseError: LocalizedError, Equatable {
        public let message: String
        public var errorDescription: String? { message }
    }

    private static let hostname = try! NSRegularExpression(pattern: "^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")
    private static let ipv6 = try! NSRegularExpression(pattern: "^[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?$")

    private static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// Parse what the operator typed, or throw saying why not.
    public static func parse(_ input: String) throws -> BoxAddress {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else {
            throw ParseError(message: "Type the box's address, for example 198.51.100.40 or openhamshack.local")
        }
        if s.lowercased().hasPrefix("https://") {
            throw ParseError(message: "OpenHamShack serves plain HTTP: use http:// or just the address")
        }
        if s.lowercased().hasPrefix("http://") { s = String(s.dropFirst(7)) }
        while s.hasSuffix("/") { s.removeLast() }
        if s.contains(where: { $0.isWhitespace || "/@?#".contains($0) }) {
            throw ParseError(message: "An address is just host or host:port")
        }
        var host: String
        var port = defaultPort
        if s.hasPrefix("[") {
            guard let end = s.firstIndex(of: "]"), s.distance(from: s.startIndex, to: end) > 1 else {
                throw ParseError(message: "An IPv6 address needs its closing ]")
            }
            host = String(s[s.index(after: s.startIndex)..<end])
            let rest = s[s.index(after: end)...]
            if !rest.isEmpty {
                guard rest.hasPrefix(":") else { throw ParseError(message: "Expected :port after ]") }
                port = try parsePort(String(rest.dropFirst()))
            }
            guard matches(ipv6, host) else { throw ParseError(message: "Not an IPv6 address: \(host)") }
        } else if s.filter({ $0 == ":" }).count > 1 {
            // Bare IPv6 without brackets: no way to tell a port from the
            // last group, so it is all address.
            guard matches(ipv6, s) else { throw ParseError(message: "Not an IPv6 address: \(s)") }
            host = s
        } else {
            if let colon = s.firstIndex(of: ":") {
                host = String(s[..<colon])
                port = try parsePort(String(s[s.index(after: colon)...]))
            } else {
                host = s
            }
            guard matches(hostname, host) else { throw ParseError(message: "Not a host name or address: \(host)") }
        }
        return BoxAddress(host: host, port: port)
    }

    private static func parsePort(_ p: String) throws -> Int {
        guard let n = Int(p), (1...65535).contains(n) else {
            throw ParseError(message: "The port must be a number from 1 to 65535")
        }
        return n
    }
}
