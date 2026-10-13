import OpenHamShackKit
import SwiftUI

/// QO-100 on the Apple TV: the spectrum across the top, the signals the box
/// found as big rows under it. Up/down moves between them (the focused one
/// is marked on the spectrum, so the eye finds it there too) and select
/// tunes the receiver being watched to it - at its standard rate, through
/// the LNB, in one tune (QO100.tunePlan), as a tap on the iPad does.
///
/// A screen of its own over the picture rather than a column beside it, as
/// the Tune panel is: the spectrum is only readable across a room at the
/// TV's full width. The lock line on the right says whether the tune took.
///
/// Opened from the overlay's QO-100 button and from the Tune panel; Menu
/// goes back. The spectrum is polled only while it is open (the polls hold
/// the box's connection to BATC). Watching needs nothing; tuning needs the
/// PIN, entered here or in Tune.
struct TVQO100Screen: View {
    let session: BoxSession
    let close: () -> Void
    @Environment(TVModel.self) private var model
    @FocusState private var focus: Focus?

    enum Focus: Hashable { case signal(Int), back }

    var body: some View {
        let monitor = session.qo100
        let q = monitor.snapshot
        let rx = session.status?.receiver(model.watching)
        let state = monitor.stateWords(verb: "select")
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .firstTextBaseline) {
                    Text("QO-100 wideband").font(.title2.bold())
                    Spacer()
                    Text(state.text).font(.callout).foregroundStyle(state.problem ? .orange : .secondary).lineLimit(1)
                }
                QO100Graph(monitor: monitor, receiveMHz: rx.flatMap { QO100.onQO100($0) ? $0.frequencyMHz : nil },
                           highlighted: focusedIndex, large: true, spectrumHeight: 250, waterfallHeight: 100)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                HStack(alignment: .top, spacing: 48) {
                    signalList(q)
                        .frame(width: 1060)
                        .focusSection()
                    side(rx, q)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .focusSection()
                }
            }
            .padding(.horizontal, 80)
            .padding(.vertical, 50)
        }
        .onAppear {
            session.qo100.start()
            focus = .back
        }
        .onDisappear { session.qo100.stop() }
        .onExitCommand(perform: close)
        .onChange(of: session.qo100.signalIDs) { _, ids in
            // The first spectrum in: start on the signal being received, or
            // the first one, rather than on Back.
            if focus == .back || focus == nil, let first = initialFocus(ids) { focus = .signal(first) }
        }
    }

    private var focusedIndex: Int? {
        guard case .signal(let id) = focus else { return nil }
        return session.qo100.signalIDs.firstIndex(of: id)
    }

    private func initialFocus(_ ids: [Int]) -> Int? {
        guard !didAutoFocus, let q = session.qo100.snapshot, !ids.isEmpty, ids.count == q.signals.count else { return nil }
        didAutoFocus = true
        let rs = session.status?.receivers ?? []
        let i = q.signals.firstIndex { QO100.tuned(to: $0, receivers: rs).contains(model.watching) } ?? 0
        return ids[i]
    }
    @State private var didAutoFocus = false
    @State private var hint = false

    @ViewBuilder private func signalList(_ q: QO100Snapshot?) -> some View {
        if let q, !q.signals.isEmpty {
            ScrollView {
                VStack(spacing: 14) {
                    ForEach(Array(zip(session.qo100.signalIDs, q.signals)), id: \.0) { id, s in
                        row(s, id: id)
                    }
                }
                .padding(.vertical, 14)
                .padding(.horizontal, 24)
            }
            .frame(maxHeight: .infinity)
        } else {
            Text(q == nil ? "Waiting for the spectrum…" : "No signals found on the transponder now.")
                .font(.title3).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func row(_ s: QO100Signal, id: Int) -> some View {
        let on = QO100.tuned(to: s, receivers: session.status?.receivers ?? []).contains(model.watching)
        return Button {
            guard session.canControl else { hint = true; return }
            session.tuneQO100(receiver: model.watching, frequencyMHz: s.freqMHz, symbolRateKs: s.tuneSymbolRateKs)
        } label: {
            HStack(spacing: 28) {
                Image(systemName: on ? "checkmark.circle.fill" : s.beacon ? "dot.radiowaves.up.forward" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(on ? .green : s.overPower ? .red : .primary)
                    .frame(width: 44)
                Text(verbatim: Tuning.downlink(s.freqMHz, locale: Locale.current.identifier)).font(.title2.bold()).monospacedDigit().fixedSize()
                Text("\(Tuning.ks(s.tuneSymbolRateKs)) kS").font(.title3).monospacedDigit().fixedSize()
                if s.beacon { Text("Beacon").font(.title3.weight(.semibold)) }
                if s.overPower { Text("over-power").font(.title3.weight(.semibold)).foregroundStyle(.red) }
                Spacer(minLength: 12)
                if on { Text("Receiving").font(.title3.weight(.semibold)).foregroundStyle(.green).fixedSize() }
                Text(String(format: "%.1f dB", s.levelDb)).font(.title3).monospacedDigit().fixedSize()
            }
            .lineLimit(1)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity)
        }
        .focused($focus, equals: .signal(id))
    }

    private func side(_ rx: Receiver?, _ q: QO100Snapshot?) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            if let r = rx {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Receiver \(r.id)\(r.tuner.isEmpty ? "" : " · \(r.tuner)")").font(.callout).foregroundStyle(.secondary)
                    Text(QO100.onQO100(r) ? "\(Tuning.mhz(r.frequencyMHz)) MHz · \(Tuning.ks(r.symbolRateKs)) kS"
                                          : "\(Tuning.mhz(r.frequencyMHz)) MHz: not QO-100")
                        .font(.title3).monospacedDigit().lineLimit(2).minimumScaleFactor(0.8)
                    Text(r.line).font(.headline).foregroundStyle(r.locked ? .green : .orange)
                }
            }
            if !session.canControl {
                TVSignIn(session: session, what: "tune", autofocus: false)
                if hint { Text("Sign in above to tune: watching needs no PIN, tuning does.").foregroundStyle(.yellow) }
            }
            Button(action: close) { Label("Back to the picture", systemImage: "chevron.backward") }
                .focused($focus, equals: .back)
            Text(QO100.credit(q)).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}
