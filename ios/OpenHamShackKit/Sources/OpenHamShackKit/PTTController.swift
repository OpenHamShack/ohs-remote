import Foundation
import Observation

/// What the controller needs from the box: the three calls of the remote
/// lease. OpenHamShackPTTLink is the real one; the tests use a fake.
public protocol PTTLink: Sendable {
    /// Key up naming remote. The box's answer: refused with a reason, or not.
    func key(remote: String, force: Bool) async throws -> KeyAnswer
    /// true: still keyed for us. false: the box has unkeyed (409). Throws on network trouble.
    func heartbeat(remote: String) async throws -> Bool
    /// Key down, whoever keyed.
    func unkey() async throws
}

/// error non-nil: not transmitting, and why (status.txError). check: the
/// console's "transmit anyway" is on offer for the next key-up.
public struct KeyAnswer: Sendable, Equatable {
    public var error: String?
    public var check: TxCheck?
    public init(error: String?, check: TxCheck? = nil) {
        self.error = error
        self.check = check
    }
}

public struct OpenHamShackPTTLink: PTTLink {
    let client: OpenHamShackClient
    public init(client: OpenHamShackClient) { self.client = client }

    public func key(remote: String, force: Bool) async throws -> KeyAnswer {
        let st = try await client.ptt(on: true, remote: remote, force: force)
        // A refused key-up is still 200: onAir false, and txError says why
        // (licence, callsign, Stream source with nothing connected, busy
        // QO-100, the radio not answering). Never a lease then.
        if !st.onAir {
            let why = !st.txError.isEmpty ? st.txError : !st.txBlocked.isEmpty ? "Not transmitting: " + st.txBlocked
                : "OpenHamShack did not go on air"
            return KeyAnswer(error: why, check: st.txCheck)
        }
        if st.lease == nil, st.raw["tx"].object?["remote"] == nil {
            // A box from before the lease (0.9.16 and older) ignores
            // "remote" and keys with nothing to stop it but the time-out.
            // Never leave that running from an iPad: down again at once.
            try? await client.ptt(on: false)
            return KeyAnswer(error: "Not transmitting: this OpenHamShack is too old for transmitting from the app "
                + "(it has no remote lease). Update the box, then try again.")
        }
        return KeyAnswer(error: nil)
    }

    public func heartbeat(remote: String) async throws -> Bool {
        try await client.heartbeat(remote: remote)
    }

    public func unkey() async throws {
        try await client.ptt(on: false)
    }
}

public enum PTTPhase: Equatable, Sendable {
    case idle, keying, keyed
}

/// Transmit with the box's remote lease.
///
/// The rule throughout: **the safe direction is off**. The box keys only on
/// an explicit request and unkeys itself 5 s after the last heartbeat (the
/// lease, TX_REMOTE_LEASE in openhamshack.py); this side adds its own reasons to
/// stop and never a reason to stay up:
///
///  - key() sends `on` with this install's id, then heartbeats every second.
///  - A heartbeat answered 409 means the box already stopped (time-out, an
///    encoder error, someone at the box): drop to idle at once.
///  - Heartbeats failing for giveUp (3 s): give up, say so, and send a
///    key-down in case the network comes back - the box's own 5 s lease is
///    the backstop, this is the faster of the two.
///  - Two status polls in a row saying off air, after statusGrace from the
///    key-up: the box stopped; believe it.
///  - release() (the app left the foreground, the network went, the box
///    screen closed): stop heartbeating first, then send the key-down. Only
///    when *this* app keyed - backgrounding an iPad must never stop a
///    transmission someone started at the box.
///  - Letting go while the key-up is still on its way: the key-down is sent
///    straight away *and* again when the key-up's answer arrives, so it is
///    right whichever request the box sees last.
///
/// The same rules, in the same order, as the Android app's PttController,
/// so the two remotes behave alike at a box. Key-downs run in their own
/// unstructured tasks, never children of anything the UI cancels: a
/// key-down started as a screen closes must not be cancelled with it.
@MainActor
@Observable
public final class PTTController {
    public private(set) var phase: PTTPhase = .idle
    /// Why the last transmission ended or was refused; shown until the next key.
    public private(set) var error: String?
    /// A busy/unchecked QO-100 refusal: the next key may be forced, for a minute.
    public private(set) var check: TxCheck?
    /// Heartbeats are failing but the give-up time has not passed yet.
    public private(set) var linkTrouble = false
    /// Heartbeats the box has accepted this transmission (for the UI's pulse).
    public private(set) var beats = 0

    public let remoteID: String
    private let link: any PTTLink
    private let beatInterval: TimeInterval
    private let giveUp: TimeInterval
    private let statusGrace: TimeInterval
    private let now: () -> TimeInterval

    private var wanted = false
    private var beatTask: Task<Void, Never>?
    private var keyedAt: TimeInterval = 0
    private var offAirPolls = 0
    /// Unkeys sent and not yet finished; tests wait on these.
    private var pending: [Task<Void, Never>] = []

    public init(link: any PTTLink, remoteID: String, beatInterval: TimeInterval = 1.0, giveUp: TimeInterval = 3.0,
                statusGrace: TimeInterval = 3.0, now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.link = link
        self.remoteID = remoteID
        self.beatInterval = beatInterval
        self.giveUp = giveUp
        self.statusGrace = statusGrace
        self.now = now
    }

    /// True from the key-up being sent until the transmission ends: what the
    /// app's own reasons to stop (background, network) should act on.
    public var holdsKey: Bool { wanted || phase != .idle }

    /// Key up. Ignored unless idle. force: the console's "transmit anyway".
    public func key(force: Bool = false) {
        guard phase == .idle else { return }
        wanted = true
        error = nil
        check = nil
        linkTrouble = false
        beats = 0
        phase = .keying
        Task {
            let answer: KeyAnswer
            do {
                answer = try await link.key(remote: remoteID, force: force)
            } catch {
                // The request may have reached the box and only the answer
                // been lost: make sure.
                answer = KeyAnswer(error: "Not transmitting: " + ((error as? LocalizedError)?.errorDescription ?? "OpenHamShack did not answer"))
                sendUnkey()
            }
            guard wanted else {
                // Let go (release/unkey) while the key-up was out. The state
                // is already idle; the box may have keyed after our first
                // key-down, so another.
                sendUnkey()
                return
            }
            if let why = answer.error {
                wanted = false
                phase = .idle
                error = why
                check = answer.check
                return
            }
            keyedAt = now()
            offAirPolls = 0
            phase = .keyed
            startHeartbeats()
        }
    }

    /// The operator's stop: always sends a key-down, even if someone else keyed.
    public func unkey() {
        stopLocally(nil)
        sendUnkey()
    }

    /// A reason outside the operator's hand to stop (background, network
    /// lost, leaving the box). Sends a key-down only if this app keyed.
    public func release(_ reason: String) {
        guard holdsKey else { return }
        stopLocally("Stopped transmitting: \(reason)")
        sendUnkey()
    }

    /// Every status poll while this app holds the key: the box has the last word.
    public func onStatus(onAir: Bool, txError: String) {
        guard phase == .keyed else { return }
        if onAir {
            offAirPolls = 0
            return
        }
        if now() - keyedAt < statusGrace { return }
        offAirPolls += 1
        if offAirPolls >= 2 {
            stopLocally(txError.isEmpty ? "OpenHamShack stopped transmitting" : txError)
            sendUnkey()
        }
    }

    /// For tests: every key-down sent so far has finished (or given up).
    public func settle() async {
        while !pending.isEmpty {
            let p = pending
            pending.removeAll()
            for t in p { await t.value }
        }
    }

    private func stopLocally(_ why: String?) {
        wanted = false
        beatTask?.cancel()
        beatTask = nil
        phase = .idle
        linkTrouble = false
        error = why
        check = nil
    }

    private func startHeartbeats() {
        beatTask?.cancel()
        beatTask = Task { [weak self] in
            guard let self else { return }
            var lastOK = self.now()
            while !Task.isCancelled, self.wanted {
                let started = self.now()
                var stillKeyed: Bool?
                do {
                    stillKeyed = try await self.link.heartbeat(remote: self.remoteID)
                } catch {
                    stillKeyed = nil
                }
                guard !Task.isCancelled, self.wanted else { return }
                switch stillKeyed {
                case true?:
                    lastOK = self.now()
                    self.beats += 1
                    if self.linkTrouble { self.linkTrouble = false }
                case false?:
                    // The box ended it; the reason is in its txError, which
                    // the status poll shows. Nothing to send.
                    self.wanted = false
                    self.phase = .idle
                    self.linkTrouble = false
                    self.error = "OpenHamShack is no longer transmitting"
                    return
                case nil:
                    if self.now() - lastOK >= self.giveUp {
                        self.wanted = false
                        self.phase = .idle
                        self.linkTrouble = false
                        self.error = "Lost contact with OpenHamShack, so stopped. The box stops by itself within 5 s of the last heartbeat it heard."
                        self.sendUnkey()
                        return
                    }
                    self.linkTrouble = true
                }
                let wait = max(0, self.beatInterval - (self.now() - started))
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            }
        }
    }

    private func sendUnkey() {
        let link = self.link
        let t = Task.detached {
            for attempt in 0..<3 {
                do {
                    try await link.unkey()
                    return
                } catch {
                    if attempt < 2 { try? await Task.sleep(nanoseconds: 400_000_000) }
                }
            }
        }
        pending.append(t)
    }
}
