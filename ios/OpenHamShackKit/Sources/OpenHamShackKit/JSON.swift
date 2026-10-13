import Foundation

/// A JSON value, read loosely.
///
/// The box's /api/status is a large document that grows with every OpenHamShack
/// release, and it is written for its own browser console, which reads it
/// with JavaScript's tolerance: a field that is missing, null or briefly of
/// another type costs that one field, never the page. A Codable model would
/// have the opposite property - one surprising value and the whole status
/// fails to decode, which on a remote with a transmitter behind it means the
/// ON AIR indicator stops updating. So the status is parsed into this tree
/// and every model reads the fields it wants through accessors that return
/// nil for anything unexpected.
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public init(data: Data) throws {
        let any = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        self = JSON(any: any)
    }

    public init(any: Any?) {
        switch any {
        case nil, is NSNull:
            self = .null
        case let n as NSNumber:
            // JSONSerialization gives booleans as NSNumber too; CFBoolean
            // is the only way to tell them apart.
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                self = .bool(n.boolValue)
            } else {
                self = .number(n.doubleValue)
            }
        case let s as String:
            self = .string(s)
        case let a as [Any]:
            self = .array(a.map { JSON(any: $0) })
        case let o as [String: Any]:
            self = .object(o.mapValues { JSON(any: $0) })
        default:
            self = .null
        }
    }

    public subscript(key: String) -> JSON {
        if case .object(let o) = self { return o[key] ?? .null }
        return .null
    }

    public subscript(index: Int) -> JSON {
        if case .array(let a) = self, a.indices.contains(index) { return a[index] }
        return .null
    }

    public var isNull: Bool { self == .null }

    public var string: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var double: Double? {
        switch self {
        case .number(let n): return n
        // The box writes numbers as numbers, but a value typed into a
        // console field has been seen stored as a string.
        case .string(let s): return Double(s)
        default: return nil
        }
    }

    public var int: Int? {
        guard let d = double, d.isFinite else { return nil }
        return Int(d.rounded())
    }

    public var bool: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var array: [JSON] {
        if case .array(let a) = self { return a }
        return []
    }

    public var object: [String: JSON]? {
        if case .object(let o) = self { return o }
        return nil
    }

    /// Back to Foundation types, for JSONSerialization.
    public var foundation: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .number(let n): return n
        case .string(let s): return s
        case .array(let a): return a.map(\.foundation)
        case .object(let o): return o.mapValues(\.foundation)
        }
    }

    public func encoded() -> Data {
        (try? JSONSerialization.data(withJSONObject: foundation, options: [.fragmentsAllowed, .sortedKeys])) ?? Data("null".utf8)
    }
}

extension JSON: ExpressibleByDictionaryLiteral, ExpressibleByStringLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByFloatLiteral, ExpressibleByIntegerLiteral, ExpressibleByNilLiteral, ExpressibleByArrayLiteral {
    public init(dictionaryLiteral elements: (String, JSON)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, b in b }))
    }
    public init(stringLiteral value: String) { self = .string(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(nilLiteral: ()) { self = .null }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
}
