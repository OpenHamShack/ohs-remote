import Foundation
import Security

/// A box the operator has used: its name, the address that reached it, and
/// whether a PIN session is held for it (the token itself is in the
/// Keychain - it is as good as the PIN for as long as the box keeps it).
public struct SavedBox: Codable, Hashable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    public var address: BoxAddress
    public var lastUsed: Date

    public init(id: UUID = UUID(), name: String, address: BoxAddress, lastUsed: Date = Date()) {
        self.id = id
        self.name = name
        self.address = address
        self.lastUsed = lastUsed
    }
}

/// The player settings an operator may change.
public struct PlayerSettings: Codable, Equatable, Sendable {
    /// VLC's network caching, in ms. 200-300 keeps a DATV picture close to
    /// the box's own; more rides out a poor Wi-Fi link.
    public var cachingMs: Int = 250
    /// VideoToolbox decoding. Off falls back to software, which copes better
    /// with some badly broken streams at the cost of battery.
    public var hardwareDecoding: Bool = true

    public init() {}
}

/// Everything the app remembers, in UserDefaults except the session tokens.
public final class Store: @unchecked Sendable {
    public static let shared = Store()

    private let defaults: UserDefaults
    private let keychain: TokenVault

    public init(defaults: UserDefaults = .standard, keychain: TokenVault = KeychainVault()) {
        self.defaults = defaults
        self.keychain = keychain
    }

    // MARK: Boxes

    public var boxes: [SavedBox] {
        get {
            guard let data = defaults.data(forKey: "boxes"),
                  let list = try? JSONDecoder().decode([SavedBox].self, from: data) else { return [] }
            return list.sorted { $0.lastUsed > $1.lastUsed }
        }
        set {
            defaults.set(try? JSONEncoder().encode(newValue), forKey: "boxes")
        }
    }

    /// Add or update by address (one entry per address), most recent first.
    @discardableResult
    public func remember(name: String, address: BoxAddress) -> SavedBox {
        var list = boxes
        if let i = list.firstIndex(where: { $0.address == address }) {
            list[i].lastUsed = Date()
            if !name.isEmpty { list[i].name = name }
            boxes = list
            return list[i]
        }
        let box = SavedBox(name: name.isEmpty ? address.display : name, address: address)
        list.append(box)
        boxes = list
        return box
    }

    public func forget(_ box: SavedBox) {
        boxes = boxes.filter { $0.id != box.id }
        keychain.set(nil, for: box.address.display)
    }

    public func rename(_ box: SavedBox, to name: String) {
        boxes = boxes.map { $0.id == box.id ? SavedBox(id: $0.id, name: name, address: $0.address, lastUsed: $0.lastUsed) : $0 }
    }

    // MARK: Sessions

    public func session(for address: BoxAddress) -> String? { keychain.get(address.display) }
    public func setSession(_ token: String?, for address: BoxAddress) { keychain.set(token, for: address.display) }

    // MARK: Install id

    /// This install's id for the PTT lease: random, made once, kept. The box
    /// shows it on its console and in its log, so it says what it is.
    public var remoteID: String {
        if let id = defaults.string(forKey: "remoteID"), Self.validRemoteID(id) { return id }
        let alphabet = Array("abcdefghijkmnpqrstuvwxyz23456789")
        let tail = String((0..<10).map { _ in alphabet[Int.random(in: 0..<alphabet.count)] })
        #if os(tvOS)
        let id = "appletv-\(tail)"
        #else
        let id = "\(Self.isPhone ? "iphone" : "ipad")-\(tail)"
        #endif
        defaults.set(id, forKey: "remoteID")
        return id
    }

    /// Whether this is an iPhone, from the hardware model ("iPhone17,3"), or
    /// on a simulator the model it simulates. Read this way rather than from
    /// UIDevice so the package needs no UIKit and nothing main-actor. An id
    /// already made is kept: one made as "ipad-" on a phone before this
    /// existed stays, since the box may have it in its log.
    static var isPhone: Bool {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return sim.hasPrefix("iPhone")
        }
        var u = utsname()
        uname(&u)
        let machine = withUnsafeBytes(of: &u.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return machine.hasPrefix("iPhone")
    }

    /// TX_REMOTE_ID_RE on the box: 1-64 of A-Z a-z 0-9 . _ : -
    public static func validRemoteID(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 64 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "._:-".contains($0)) }
    }

    // MARK: Settings

    public var player: PlayerSettings {
        get {
            guard let data = defaults.data(forKey: "player"),
                  let s = try? JSONDecoder().decode(PlayerSettings.self, from: data) else { return PlayerSettings() }
            return s
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: "player") }
    }

    /// The box opened last, opened again at launch.
    public var lastBoxID: UUID? {
        get { defaults.string(forKey: "lastBox").flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: "lastBox") }
    }
}

public protocol TokenVault: Sendable {
    func get(_ account: String) -> String?
    func set(_ value: String?, for account: String)
}

/// The Keychain, one generic password per box address. Only readable after
/// first unlock; never synced (a session belongs to this device).
public struct KeychainVault: TokenVault {
    let service = "com.openhamshack.remote.session"

    public init() {}

    public func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func set(_ value: String?, for account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
        guard let value else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// For tests and previews.
public final class MemoryVault: TokenVault, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init() {}
    public func get(_ account: String) -> String? { lock.withLock { values[account] } }
    public func set(_ value: String?, for account: String) { lock.withLock { values[account] = value } }
}
