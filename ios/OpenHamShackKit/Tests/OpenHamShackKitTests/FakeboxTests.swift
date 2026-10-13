import XCTest
@testable import OpenHamShackKit

/// Against a running tools/fakebox.py: openhamshack.py's real handler with the
/// radio faked, so the client is exercised against the real API (login,
/// cookies, status, the lease) without a transmitter anywhere.
///
///     OPENHAMSHACK_FAKEBOX=shack:18291 swift test --filter FakeboxTests
///
/// The address must be fakebox's remote-facing port (PIN 1234). Skipped
/// when the variable is not set, so `swift test` alone needs no network.
@MainActor
final class FakeboxTests: XCTestCase {
    var address: BoxAddress!

    override func setUp() async throws {
        guard let a = ProcessInfo.processInfo.environment["OPENHAMSHACK_FAKEBOX"] else {
            throw XCTSkip("OPENHAMSHACK_FAKEBOX not set")
        }
        address = try BoxAddress.parse(a)
    }

    func testViewOnlyThenPIN() async throws {
        let c = OpenHamShackClient(address: address)
        let st = try await c.status()
        XCTAssertFalse(st.auth.canControl, "a client without a session is view-only")
        XCTAssertTrue(st.auth.pinSet)
        do {
            _ = try await c.config(["source": "testcard"])
            XCTFail("a change without the PIN must be refused")
        } catch let e as OpenHamShackError {
            guard case .forbidden = e else { return XCTFail("\(e)") }
        }
        do {
            _ = try await c.login(pin: "0000")
            XCTFail("wrong PIN accepted")
        } catch let e as OpenHamShackError {
            XCTAssertEqual(e.errorDescription, "wrong PIN")
        }
        try await c.login(pin: "1234")
        XCTAssertNotNil(c.session)
        let after = try await c.status()
        XCTAssertTrue(after.auth.authorised)
        let larix = try await c.streamTarget()
        XCTAssertNotNil(larix.passphrase, "the passphrase comes with the session")
    }

    /// Key under the lease, heartbeat, then go silent: the box must unkey by
    /// itself within its 5 s lease and say why.
    func testLeaseRunsOut() async throws {
        let c = OpenHamShackClient(address: address)
        try await c.login(pin: "1234")
        let id = "test-\(Int.random(in: 1000...9999))"
        let up = try await c.ptt(on: true, remote: id)
        XCTAssertTrue(up.onAir, up.txError)
        for _ in 0..<3 {
            let held = try await c.heartbeat(remote: id)
            XCTAssertTrue(held)
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
        let other = try await c.heartbeat(remote: "someone-else")
        XCTAssertFalse(other, "another id does not hold the lease")
        // Silence.
        var off = false
        for _ in 0..<16 {
            try await Task.sleep(nanoseconds: 500_000_000)
            if !(try await c.status()).onAir { off = true; break }
        }
        XCTAssertTrue(off, "the box unkeyed by itself")
        let st = try await c.status()
        XCTAssertFalse(st.txError.isEmpty, "and said why")
        let late = try await c.heartbeat(remote: id)
        XCTAssertFalse(late, "a heartbeat after the lease ran out is 409")
    }

    /// The controller end to end: key, hold, unkey.
    func testControllerAgainstTheBox() async throws {
        let c = OpenHamShackClient(address: address)
        try await c.login(pin: "1234")
        let ptt = PTTController(link: OpenHamShackPTTLink(client: c), remoteID: "test-ctl")
        ptt.key()
        let end = Date().addingTimeInterval(10)
        while ptt.phase != .keyed, Date() < end { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertEqual(ptt.phase, .keyed, ptt.error ?? "")
        try await Task.sleep(nanoseconds: 3_000_000_000)
        XCTAssertGreaterThanOrEqual(ptt.beats, 2)
        let during = try await c.status()
        XCTAssertTrue(during.onAir)
        // during.lease is not checked: fakebox answers the PTT calls itself,
        // and its status's tx.remote stays the real daemon's (null). The
        // real box's lease view is held by openhamshack's test_remote.py.
        ptt.unkey()
        await ptt.settle()
        let after = try await c.status()
        XCTAssertFalse(after.onAir)
    }

    func testStreamCarriesTS() async throws {
        let c = OpenHamShackClient(address: address)
        let (bytes, response) = try await URLSession.shared.bytes(from: c.streamURL(receiver: 1))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        var got = [UInt8]()
        for try await b in bytes {
            got.append(b)
            if got.count >= 188 * 20 { break }
        }
        let syncs = stride(from: 0, to: got.count, by: 188).filter { got[$0] == 0x47 }.count
        XCTAssertGreaterThan(syncs, 15, "MPEG-TS, 188-byte packets")
    }

    /// /api/tx/tune: saved with txTune, refused whole with the box's
    /// sentence, an out-of-licence frequency saved but txBlocked.
    func testTxTune() async throws {
        let c = OpenHamShackClient(address: address)
        let opts = try await c.txOptions()
        XCTAssertFalse(opts.presets.isEmpty)
        try await c.login(pin: "1234")
        let ok = try await c.txTune(["frequencyMHz": 437.0, "symbolRateKs": 333])
        XCTAssertEqual(ok.txTune?.changed["frequencyMHz"]?.double, 437.0)
        XCTAssertTrue(ok.txBlocked.isEmpty, ok.txBlocked)
        XCTAssertTrue(ok.auth.authorised, "a POST's status carries auth")
        do {
            _ = try await c.txTune(["frequencyMHz": 1255.0, "constellation": "8psk", "fec": "1/4"])
            XCTFail("a FEC 8PSK lacks must be refused")
        } catch let e as OpenHamShackError {
            guard case .http(400, let why) = e else { return XCTFail("\(e)") }
            XCTAssertTrue(why.contains("fec"), why)
        }
        let after = try await c.status()
        XCTAssertEqual(after.config.frequencyMHz, 437.0, "a refused patch saves nothing")
        let blocked = try await c.txTune(["frequencyMHz": 146.95])
        XCTAssertFalse(blocked.txBlocked.isEmpty, "outside the licence: saved, but key-up blocked")
        _ = try await c.txTune(["frequencyMHz": 437.0])
    }

    func testLogout() async throws {
        let c = OpenHamShackClient(address: address)
        let token = try await c.login(pin: "1234")
        await c.logout()
        XCTAssertNil(c.session)
        let again = OpenHamShackClient(address: address, session: token)
        let st = try await again.status()
        XCTAssertFalse(st.auth.authorised, "the box ended that session")
        await OpenHamShackClient(address: address).logout()   // safe with none
    }

    /// GET /api/qo100 without a session, as the QO-100 screens poll it: the
    /// box's real handler, with whatever BATC is sending now (or why not).
    /// The band plan needs no internet, so it is always there.
    func testQO100() async throws {
        let c = OpenHamShackClient(address: address)
        let monitor = QO100Monitor(client: c)
        let ok = await monitor.pollOnce()
        XCTAssertTrue(ok)
        let q = try XCTUnwrap(monitor.snapshot)
        XCTAssertEqual(q.startMHz, 10490.5)
        XCTAssertEqual(q.channels[333]?.count, 14)
        XCTAssertNotNil(q.waterfall, "?wf= asks for the rows")
        // Again: only rows after the ones already had.
        let since = monitor.waterfall.since
        let again = try await c.qo100(waterfallSince: since)
        XCTAssertLessThanOrEqual(again.waterfall?.rows.count ?? 0, 5)
        // The first poll after a quiet spell (re)opens the box's feed; within
        // a few more it is live, or the box says why not.
        var last = q
        for _ in 0..<10 where !last.live && last.error.isEmpty {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            last = try await c.qo100()
        }
        XCTAssertTrue(last.live || !last.error.isEmpty, "live, or saying why not")
        if last.live { XCTAssertEqual(last.spectrum.count, 461) }
    }
}
