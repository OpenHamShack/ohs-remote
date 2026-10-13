import XCTest
@testable import OpenHamShackKit

/// A box's transmitter as the lease contract describes it, with knobs for
/// the ways a real one lets an app down.
final class FakeLink: PTTLink, @unchecked Sendable {
    private let lock = NSLock()
    private var _keyed = false
    private var _calls: [String] = []
    var refuse: String?
    var networkDown = false
    var keyDelay: UInt64 = 0
    /// The box ends the transmission by itself (time-out, someone at the box).
    var boxUnkeyed = false

    var keyed: Bool { lock.withLock { _keyed } }
    var calls: [String] { lock.withLock { _calls } }
    private func log(_ s: String) { lock.withLock { _calls.append(s) } }

    func key(remote: String, force: Bool) async throws -> KeyAnswer {
        log("key \(remote)\(force ? " force" : "")")
        if keyDelay > 0 { try? await Task.sleep(nanoseconds: keyDelay) }
        if networkDown { throw OpenHamShackError.network("down") }
        if let why = refuse { return KeyAnswer(error: why) }
        lock.withLock { _keyed = true }
        return KeyAnswer(error: nil)
    }

    func heartbeat(remote: String) async throws -> Bool {
        log("beat")
        if networkDown { throw OpenHamShackError.network("down") }
        if boxUnkeyed { lock.withLock { _keyed = false } }
        return keyed
    }

    func unkey() async throws {
        log("unkey")
        if networkDown { throw OpenHamShackError.network("down") }
        lock.withLock { _keyed = false }
    }
}

@MainActor
final class PTTControllerTests: XCTestCase {
    func make(_ link: FakeLink, giveUp: TimeInterval = 0.3, grace: TimeInterval = 0.2) -> PTTController {
        PTTController(link: link, remoteID: "ipad-test", beatInterval: 0.05, giveUp: giveUp, statusGrace: grace)
    }

    func until(_ what: String, timeout: TimeInterval = 2, _ cond: @escaping @MainActor () -> Bool) async {
        let end = Date().addingTimeInterval(timeout)
        while !cond() {
            if Date() > end { XCTFail("timed out waiting for: \(what)"); return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    func testKeysAndHeartbeats() async {
        let link = FakeLink()
        let ptt = make(link)
        ptt.key()
        XCTAssertEqual(ptt.phase, .keying)
        await until("keyed") { ptt.phase == .keyed }
        await until("three beats") { ptt.beats >= 3 }
        XCTAssertTrue(link.keyed)
        XCTAssertEqual(link.calls.first, "key ipad-test")
        ptt.unkey()
        await ptt.settle()
        XCTAssertEqual(ptt.phase, .idle)
        XCTAssertFalse(link.keyed)
        XCTAssertNil(ptt.error)
        let beats = link.calls.filter { $0 == "beat" }.count
        try? await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(link.calls.filter { $0 == "beat" }.count, beats, "no heartbeat after the stop")
    }

    func testRefusalIsShownAndNothingBeats() async {
        let link = FakeLink()
        link.refuse = "Not transmitting: 2 m transmit allowed up to 146.9375 MHz"
        let ptt = make(link)
        ptt.key()
        await until("refused") { ptt.phase == .idle && ptt.error != nil }
        XCTAssertEqual(ptt.error, link.refuse)
        try? await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertFalse(link.calls.contains("beat"))
    }

    /// The box answers 409: it stopped (time-out, someone at the box). Drop
    /// at once and send nothing more.
    func testBoxEndsIt() async {
        let link = FakeLink()
        let ptt = make(link)
        ptt.key()
        await until("keyed") { ptt.phase == .keyed }
        link.boxUnkeyed = true
        await until("dropped") { ptt.phase == .idle }
        XCTAssertEqual(ptt.error, "OpenHamShack is no longer transmitting")
        XCTAssertFalse(link.calls.contains("unkey"))
    }

    /// Heartbeats failing: trouble shown, then give up and send a key-down.
    func testGivesUpWhenTheNetworkGoes() async {
        let link = FakeLink()
        let ptt = make(link, giveUp: 0.3)
        ptt.key()
        await until("keyed") { ptt.phase == .keyed }
        link.networkDown = true
        await until("trouble") { ptt.linkTrouble }
        XCTAssertEqual(ptt.phase, .keyed, "trouble is not yet giving up")
        await until("gave up") { ptt.phase == .idle }
        XCTAssertTrue(ptt.error?.contains("Lost contact") == true)
        link.networkDown = false
        await ptt.settle()
        XCTAssertTrue(link.calls.contains("unkey"), "a key-down is tried in case the network comes back")
    }

    /// Release while the key-up is still on its way: the key-down goes now
    /// and again after the key-up's answer, so the box ends up off whichever
    /// it saw last.
    func testReleaseDuringKeyUp() async {
        let link = FakeLink()
        link.keyDelay = 200_000_000
        let ptt = make(link)
        ptt.key()
        try? await Task.sleep(nanoseconds: 50_000_000)
        ptt.release("the app went to the background")
        XCTAssertEqual(ptt.phase, .idle)
        XCTAssertEqual(ptt.error, "Stopped transmitting: the app went to the background")
        try? await Task.sleep(nanoseconds: 300_000_000)
        await ptt.settle()
        XCTAssertFalse(link.keyed, "off, though the key-up arrived after the first key-down")
        XCTAssertEqual(link.calls.filter { $0 == "unkey" }.count, 2)
        XCTAssertFalse(link.calls.contains("beat"))
    }

    /// Backgrounding must never stop a transmission someone else keyed.
    func testReleaseLeavesOthersAlone() async {
        let link = FakeLink()
        let ptt = make(link)
        ptt.release("the app went to the background")
        await ptt.settle()
        XCTAssertTrue(link.calls.isEmpty)
        XCTAssertNil(ptt.error)
    }

    /// The operator's stop always sends, whoever keyed.
    func testUnkeyAlwaysSends() async {
        let link = FakeLink()
        let ptt = make(link)
        ptt.unkey()
        await ptt.settle()
        XCTAssertEqual(link.calls, ["unkey"])
    }

    func testStatusOffAirTwiceAfterGrace() async {
        let link = FakeLink()
        let ptt = make(link, grace: 0.1)
        ptt.key()
        await until("keyed") { ptt.phase == .keyed }
        ptt.onStatus(onAir: false, txError: "")
        XCTAssertEqual(ptt.phase, .keyed, "within the grace a poll from before the key-up is ignored")
        try? await Task.sleep(nanoseconds: 150_000_000)
        ptt.onStatus(onAir: false, txError: "Transmit time-out after 10 min")
        XCTAssertEqual(ptt.phase, .keyed, "one poll is not enough")
        ptt.onStatus(onAir: false, txError: "Transmit time-out after 10 min")
        XCTAssertEqual(ptt.phase, .idle)
        XCTAssertEqual(ptt.error, "Transmit time-out after 10 min")
        await ptt.settle()
    }

    func testKeyFailureSendsKeyDown() async {
        let link = FakeLink()
        link.networkDown = true
        let ptt = make(link)
        ptt.key()
        await until("failed") { ptt.phase == .idle && ptt.error != nil }
        XCTAssertTrue(ptt.error!.hasPrefix("Not transmitting"))
        await ptt.settle()
        XCTAssertEqual(link.calls.filter { $0 == "unkey" }.count, 3, "tried three times: the key-up may have arrived")
    }
}
