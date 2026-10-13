import Foundation
import Network
import Observation

/// One open box: its status, polled as the console polls it, what this
/// client may do there, and the actions the remote offers.
///
/// Every screen of the app reads the box through one of these, so there is
/// one poll per box, one PTT lease and one place that decides when to let
/// go of the transmitter.
@MainActor
@Observable
public final class BoxSession {
    public enum Link: Equatable, Sendable {
        case connecting
        case online
        /// Polls are failing; since when, and the last reason.
        case unreachable(since: Date, reason: String)
    }

    public let box: SavedBox
    public let client: OpenHamShackClient
    public let ptt: PTTController

    public private(set) var status: BoxStatus?
    public private(set) var link: Link = .connecting
    /// Called when the box answers again after being unreachable: the app
    /// reopens the picture, whose connection may have died silently.
    public var onReturn: (() -> Void)?
    /// The last action's refusal or failure, for a transient banner.
    public var notice: String?
    /// When the operator last changed something here: a poll arriving just
    /// after must not paint back the old value (core.js's `editing`).
    private var editedAt: TimeInterval = -10

    private let store: Store
    private var pollTask: Task<Void, Never>?
    private var pathMonitor: NWPathMonitor?
    private var failures = 0

    public init(box: SavedBox, store: Store = .shared) {
        self.box = box
        self.store = store
        client = OpenHamShackClient(address: box.address, session: store.session(for: box.address))
        ptt = PTTController(link: OpenHamShackPTTLink(client: client), remoteID: store.remoteID)
        qo100 = QO100Monitor(client: client)
    }

    /// The QO-100 spectrum, polled only while a QO-100 screen is showing.
    public let qo100: QO100Monitor

    /// The console's rule: the box's own screen or a PIN session may change
    /// things. Unknown until the first status, and then false: a remote that
    /// does not know yet behaves as view-only.
    public var canControl: Bool { status?.auth.canControl ?? false }
    public var pinSet: Bool { status?.auth.pinSet ?? true }

    // MARK: - Polling

    /// Poll once a second (main.js's setInterval(refresh, 1000)); slower while
    /// the box does not answer, so a box that is off is not hammered.
    public func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.pollOnce()
                let wait: UInt64 = self.failures == 0 ? 1_000_000_000 : 2_000_000_000
                try? await Task.sleep(nanoseconds: wait)
            }
        }
        if pathMonitor == nil {
            let m = NWPathMonitor()
            m.pathUpdateHandler = { [weak self] path in
                guard path.status != .satisfied else { return }
                Task { @MainActor in self?.ptt.release("the network went away") }
            }
            m.start(queue: DispatchQueue(label: "openhamshack.path"))
            pathMonitor = m
        }
    }

    /// Stop polling (the app is going to the background, or the box screen
    /// is closing) - and let go of the transmitter first.
    public func stop(reason: String) {
        ptt.release(reason)
        qo100.stop()
        pollTask?.cancel()
        pollTask = nil
        pathMonitor?.cancel()
        pathMonitor = nil
    }

    public func pollOnce() async {
        do {
            let st = try await client.status()
            failures = 0
            if case .unreachable = link { onReturn?() }
            link = .online
            apply(st)
        } catch is CancellationError {
            return
        } catch {
            failures += 1
            let reason = (error as? LocalizedError)?.errorDescription ?? "OpenHamShack did not answer"
            if case .unreachable(let since, _) = link {
                link = .unreachable(since: since, reason: reason)
            } else if failures >= 2 || status == nil {
                link = .unreachable(since: Date(), reason: reason)
            }
        }
    }

    private func apply(_ st: BoxStatus) {
        var st = st
        if ProcessInfo.processInfo.systemUptime - editedAt < 1.5, let old = status {
            // Keep the settings just changed until the box has caught up.
            st.config = old.config
            st.receivers = st.receivers.map { r in old.receiver(r.id).map { o in
                var r = r
                r.frequencyMHz = o.frequencyMHz
                r.symbolRateKs = o.symbolRateKs
                r.offsetMHz = o.offsetMHz
                return r
            } ?? r }
        }
        if st.onAir {
            // What is on the air now: the settings when the transmission
            // was first seen, with power following (it applies at once).
            // status.config is what the *next* key-up sends, which a tune
            // on air changes while the carrier stays where it was.
            if onAirConfig == nil { onAirConfig = st.config }
            onAirConfig?.gainDb = st.config.gainDb
        } else {
            onAirConfig = nil
        }
        status = st
        ptt.onStatus(onAir: st.onAir, txError: st.txError)
        if st.auth.local == false, st.auth.authorised == false, client.session != nil {
            // The box no longer knows our session: any change of the PIN at
            // the box - a new one, or cleared - ends every remote session
            // (REMOTE-API.md 2). Every status carries auth, a POST's answer
            // too, so this is seen on the first answer after it happens.
            sessionLost()
        }
        if txOptions == nil, !loadingOptions { loadTxOptions() }
    }

    /// Back to view-only, saying why, and forgetting the dead token.
    private func sessionLost() {
        ptt.release("the box ended this device's session")
        client.session = nil
        store.setSession(nil, for: box.address)
        notice = (status?.auth.pinSet ?? true)
            ? "Signed out by the box: its remote PIN was changed. Enter the new PIN to control it again."
            : "Signed out by the box: its remote PIN was cleared. Set one on the box's touchscreen to control it from here."
    }

    // MARK: - Transmit settings

    /// The transmit settings of the transmission on air, nil off air.
    public private(set) var onAirConfig: BoxConfig?

    /// GET /api/tx/options, fetched once per box open (and again if it failed).
    public private(set) var txOptions: TxOptions?
    private var loadingOptions = false
    /// The last /api/tx/tune: what it said, and whether it was refused.
    public private(set) var txTuneMessage: (text: String, refused: Bool)?

    public func loadTxOptions() {
        loadingOptions = true
        Task {
            // An older box has no /api/tx/options (404): the panel then
            // shows the settings read-only.
            self.txOptions = try? await self.client.txOptions()
            self.loadingOptions = false
        }
    }

    /// POST /api/tx/tune with any subset of the six keys. A refusal (400)
    /// saved nothing and its sentence is shown as it is; on air, what waits
    /// for the next key-up is said.
    public func txTune(_ patch: [String: JSON]) {
        guard canControl else {
            notice = "View only: enter the PIN to change the transmit settings."
            return
        }
        Task {
            do {
                let st = try await self.client.txTune(patch)
                self.apply(st)
                if st.onAir, let words = st.txTune?.nextKeyUpWords(onAir: true) {
                    let power = st.txTune?.changed["gainDb"] != nil ? " Power changed at once." : ""
                    self.txTuneMessage = (words + power, false)
                } else if st.onAir, st.txTune?.changed["gainDb"] != nil {
                    self.txTuneMessage = ("Power changed on air.", false)
                } else {
                    self.txTuneMessage = nil
                }
            } catch let e as OpenHamShackError {
                if case .forbidden = e, self.client.session != nil {
                    await self.pollOnce()
                    if self.client.session == nil { return }
                }
                self.txTuneMessage = ("Not changed: \(e.errorDescription ?? "refused")", true)
            } catch {
                self.txTuneMessage = ("Not changed: OpenHamShack did not answer", true)
            }
        }
    }

    public func clearTxTuneMessage() { txTuneMessage = nil }

    // MARK: - PIN

    public func signIn(pin: String) async -> String? {
        do {
            let token = try await client.login(pin: pin)
            store.setSession(token, for: box.address)
            await pollOnce()
            return nil
        } catch {
            return (error as? LocalizedError)?.errorDescription ?? "Not signed in"
        }
    }

    /// "Sign out": ends this session on the box too (POST /api/logout), so a
    /// lost iPad's token is not left valid for a year.
    public func signOut() {
        ptt.release("signed out")
        store.setSession(nil, for: box.address)
        Task {
            await client.logout()
            await pollOnce()
        }
    }

    // MARK: - Actions

    /// Run a change, with the console's view-only rule and its error shown.
    private func change(_ what: @escaping () async throws -> BoxStatus?) {
        guard canControl else {
            notice = pinSet ? "View only: enter the PIN to control this OpenHamShack." :
                "View only: set a remote PIN on the box's touchscreen (Settings → Security) to allow control."
            return
        }
        editedAt = ProcessInfo.processInfo.systemUptime
        Task {
            do {
                if let st = try await what() { self.apply(st) }
                self.notice = nil
            } catch {
                self.editedAt = -10
                if case .forbidden? = error as? OpenHamShackError, self.client.session != nil {
                    // Refused with a session in hand: the box ended it. The
                    // poll that follows shows auth and says so.
                    await self.pollOnce()
                    if self.client.session == nil { return }
                }
                self.notice = (error as? LocalizedError)?.errorDescription ?? "Not done"
            }
        }
    }

    public func tune(receiver id: Int, frequencyMHz: Double? = nil, symbolRateKs: Double? = nil, offsetMHz: Double? = nil) {
        // Shown at once only when it will be sent: a view-only tap painted
        // the new frequency (the QO-100 page's marker jumped) until the next
        // poll put it back.
        if canControl, var st = status, let i = st.receivers.firstIndex(where: { $0.id == id }) {
            // Show it at once; the poll confirms it.
            if let f = frequencyMHz { st.receivers[i].frequencyMHz = f }
            if let s = symbolRateKs { st.receivers[i].symbolRateKs = s }
            if let o = offsetMHz { st.receivers[i].offsetMHz = o }
            status = st
        }
        change { try await self.client.tune(receiver: id, frequencyMHz: frequencyMHz, symbolRateKs: symbolRateKs, offsetMHz: offsetMHz) }
    }

    /// Tune to a frequency typed or chosen, with the LNB offset worked out as
    /// the console does.
    /// The rate goes with it in the same request: the one given, or one the
    /// new band uses if the receiver's is not (Tuning.rate(movingTo:)).
    public func tune(receiver id: Int, toMHz f: Double, symbolRateKs: Double? = nil) {
        guard let r = status?.receiver(id) else { return }
        let lo = status?.config.qo100LnbMHz ?? 9750
        let o = Tuning.offsetFor(frequencyMHz: f, currentOffset: r.offsetMHz, lnbLO: lo)
        tune(receiver: id, frequencyMHz: f, symbolRateKs: symbolRateKs ?? Tuning.rate(movingTo: f, offsetMHz: o, current: r.symbolRateKs),
             offsetMHz: o)
    }

    /// Tune to a QO-100 signal or channel (QO100.tunePlan), by the console's QO-100 page's
    /// rule: through the LNB, keeping the receiver's own offset while it is
    /// already on QO-100, else the box's LNB setting (QO100.offset).
    public func tuneQO100(receiver id: Int, frequencyMHz f: Double, symbolRateKs: Double?) {
        guard let r = status?.receiver(id) else { return }
        let p = QO100.tunePlan(frequencyMHz: f, symbolRateKs: symbolRateKs, receiver: r, lnbLO: status?.config.qo100LnbMHz ?? 9750)
        tune(receiver: id, frequencyMHz: p.frequencyMHz, symbolRateKs: p.symbolRateKs, offsetMHz: p.offsetMHz)
    }

    /// The receiver the box itself shows and plays.
    public func showOnBox(receiver id: Int) {
        change { try await self.client.selectReceiver(id); return nil }
    }

    /// The box's picture source (what it transmits).
    public func setSource(_ source: PictureSource) {
        if var st = status { st.config.source = source.rawValue; status = st }
        change { try await self.client.config(["source": .string(source.rawValue)]) }
    }

    public func keepTransmitting() {
        change { try await self.client.keepTransmitting() }
    }

    /// Key up, with the console's "transmit anyway" when a QO-100 check
    /// refused the last one.
    public func transmit() {
        guard canControl else {
            notice = "View only: enter the PIN to transmit."
            return
        }
        ptt.key(force: ptt.check != nil || status?.txCheck != nil)
    }

    public func stopTransmitting() {
        ptt.unkey()
    }
}
