import Foundation
import Observation

/// GET /api/qo100 once a second while a QO-100 screen is showing, and
/// nothing otherwise.
///
/// Once a second because the console's page does the same and BATC's frames
/// come four a second; anything well under the box's 20 s idle limit keeps
/// its feed open, and stopping is what lets the box close it, so the screens
/// call stop() as they go (and BoxSession.stop() does too, with the app).
@MainActor
@Observable
public final class QO100Monitor {
    public private(set) var snapshot: QO100Snapshot?
    public private(set) var waterfall = QO100WaterfallBuffer()
    /// A stable id per entry of snapshot.signals (QO100SignalIdentity).
    public private(set) var signalIDs: [Int] = []
    /// The box itself did not answer (or is too old to have /api/qo100).
    public private(set) var problem: String?
    public private(set) var running = false

    private let client: OpenHamShackClient
    private var task: Task<Void, Never>?
    private var identity = QO100SignalIdentity()

    public init(client: OpenHamShackClient) {
        self.client = client
    }

    public func start() {
        guard task == nil else { return }
        running = true
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let ok = await self.pollOnce()
                try? await Task.sleep(nanoseconds: ok ? 1_000_000_000 : 2_000_000_000)
            }
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        running = false
    }

    @discardableResult
    public func pollOnce() async -> Bool {
        do {
            let q = try await client.qo100(waterfallSince: waterfall.since)
            take(q)
            return true
        } catch is CancellationError {
            return false
        } catch OpenHamShackError.http(404, _) {
            problem = "This OpenHamShack has no QO-100 spectrum: update it to see what is on the transponder."
            return false
        } catch {
            problem = "OpenHamShack is not answering"
            return false
        }
    }

    /// One answer in: kept, its waterfall rows added, its signals named.
    public func take(_ q: QO100Snapshot) {
        problem = nil
        if let wf = q.waterfall { waterfall.add(wf) }
        signalIDs = identity.identify(q.signals)
        snapshot = q
    }

    public func stateWords(verb: String = "tap") -> (text: String, problem: Bool) {
        QO100.stateWords(snapshot, boxProblem: problem, verb: verb)
    }
}
