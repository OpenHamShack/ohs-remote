import Foundation
import XCTest
@testable import OpenHamShackKit

/// ohs-libs' shared formatting vectors (I18N.md §5 item 3) run against the
/// copy of ShackFormat in OpenHamShackKit, so a copy that drifts from the
/// library, or a library change copied without its vectors, fails here.
///
/// The library's own test reads the vectors from an ohs-libs checkout with
/// ShackClient's JSON reader. OpenHamShackKit's JSON holds every number as a
/// Double, which cannot tell the vectors' integers from their decimals, so
/// this reads the copy in Fixtures with JSONSerialization instead and keeps
/// the integers as Int64. An expected `error` is a machine code; the English
/// is not compared.
final class FormatVectorsTests: XCTestCase {
    lazy var vectors: [String: Any] = {
        let url = Bundle.module.url(forResource: "Fixtures/i18n-format", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    func cases(_ section: String) -> [[String: Any]] { vectors[section] as? [[String: Any]] ?? [] }

    func value(_ v: Any?) -> ShackFormat.Value {
        guard let n = v as? NSNumber else { XCTFail("not a number: \(String(describing: v))"); return .int(0) }
        return CFNumberIsFloatType(n) ? .double(n.doubleValue) : .int(n.int64Value)
    }

    func int(_ v: Any?) -> Int? { (v as? NSNumber).map(\.intValue) }

    /// Runs every case of a section, comparing its text (or Hz) or its error code.
    func run(_ section: String, want: String, _ call: ([String: Any]) throws -> String) {
        let cs = cases(section)
        XCTAssertFalse(cs.isEmpty, "\(section) has no cases")
        for c in cs {
            let got: String
            do {
                got = try call(c)
            } catch let e as ShackFormat.FormatError {
                got = "error:" + e.code
            } catch {
                got = "unexpected: \(error)"
            }
            let expected = (c["error"] as? String).map { "error:" + $0 } ?? "\(c[want]!)"
            XCTAssertEqual(got, expected, "\(section) \(c)")
        }
    }

    func testFrequency() {
        run("frequency", want: "text") { c in
            try ShackFormat.frequency(value(c["hz"]), locale: c["locale"] as! String, unit: c["unit"] as! String,
                                      decimals: int(c["decimals"]))
        }
    }

    func testOffset() {
        run("offset", want: "text") { c in
            try ShackFormat.offset(value(c["hz"]), unit: c["unit"] as! String, locale: c["locale"] as! String,
                                   decimals: int(c["decimals"]))
        }
    }

    func testParseFrequency() {
        run("parse_frequency", want: "hz") { c in
            String(try ShackFormat.parseFrequency(c["text"] as! String, locale: c["locale"] as! String, unit: c["unit"] as? String))
        }
    }

    func testNumber() {
        run("number", want: "text") { c in
            try ShackFormat.number(value(c["x"]), locale: c["locale"] as! String, decimals: int(c["decimals"])!)
        }
    }

    func testSwr() {
        run("swr", want: "text") { c in try ShackFormat.swr(value(c["x"]), locale: c["locale"] as! String) }
    }

    func testTheVectorsAreTheVersionThisCopyWasWrittenFor() {
        XCTAssertEqual(int(vectors["version"]), 1)
    }

    /// The one display routed through the copy so far: QO-100 signal rows.
    func testADownlinkShowsInMHzWithTheLocalesDecimalSeparatorAndNoGrouping() {
        XCTAssertEqual(Tuning.downlink(10491.5, locale: "en_GB"), "10491.500\u{00A0}MHz")
        XCTAssertEqual(Tuning.downlink(10491.5, locale: "de_DE"), "10491,500\u{00A0}MHz")
        XCTAssertEqual(Tuning.downlink(10489.75, locale: "fr"), "10489,750\u{00A0}MHz")    // France groups numbers; never a frequency
        XCTAssertEqual(Tuning.downlink(10494.7625, locale: "en"), "10494.763\u{00A0}MHz") // to the kHz, half away from zero
    }

    func testAFoundationLocaleFindsItsSymbols() {
        XCTAssertEqual(Symbols.of(Locale(identifier: "de_DE").identifier).decimal, ",")
        XCTAssertEqual(Symbols.of("de_CH.UTF-8@euro").group, "\u{2019}")
    }
}
