import OpenHamShackKit
import SwiftUI

/// The spectrum, the line saying how live it is, and what a tap on it does:
/// the top of the QO-100 tab. On an iPad on its side it sits full width
/// above the picture and the panel column (BoxView), because 420 points
/// gave a 333 kS station a sliver 20 points wide; elsewhere it heads the
/// panel itself.
///
/// A tap on a signal tunes the receiver whose picture is shown to it, at
/// its standard rate, through the LNB (QO100.tunePlan). A tap where
/// nothing was found asks first, offering the nearest 12.5 kHz: a trace in
/// the waterfall from a minute ago is not a station to tune to unasked.
struct QO100Spectrum: View {
    let session: BoxSession
    var compact = false
    @Environment(AppModel.self) private var model
    @State private var offer: FreeTune?

    struct FreeTune: Identifiable {
        let frequencyMHz: Double
        let symbolRateKs: Double
        var id: Double { frequencyMHz }
    }

    var body: some View {
        let monitor = session.qo100
        let rx = session.status?.receiver(model.watching)
        let state = monitor.stateWords()
        VStack(alignment: .leading, spacing: 6) {
            QO100Graph(monitor: monitor, receiveMHz: rx.flatMap { QO100.onQO100($0) ? $0.frequencyMHz : nil },
                       highlighted: receiving,
                       spectrumHeight: compact ? 150 : 200, waterfallHeight: compact ? 80 : 120) { f in tap(f) }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Circle().fill(state.problem ? Color.orange : monitor.snapshot?.live == true ? .green : .secondary)
                    .frame(width: 8, height: 8)
                Text(state.text).font(.footnote).foregroundStyle(state.problem ? .orange : .secondary)
            }
        }
        // A String, not a LocalizedStringKey: with the `?? ""` the literal
        // pair became two catalog keys, one of them empty, which the string
        // check rightly refuses.
        .confirmationDialog(offer.map { String(localized: "No signal found at \(Tuning.mhz($0.frequencyMHz)) MHz now.") } ?? "",
                            isPresented: Binding(get: { offer != nil }, set: { if !$0 { offer = nil } }),
                            titleVisibility: .visible, presenting: offer) { o in
            Button("Receive \(Tuning.mhz(o.frequencyMHz)) at \(Tuning.ks(o.symbolRateKs)) kS") {
                session.tuneQO100(receiver: model.watching, frequencyMHz: o.frequencyMHz, symbolRateKs: o.symbolRateKs)
            }
            Button("Cancel", role: .cancel) {}
        }
        #if DEBUG
        .task {
            // -qoTap <MHz>: a tap there once the spectrum is in, for testing
            // click-to-tune in the simulator against fakebox.
            guard let f = DebugLaunch.qoTap else { return }
            while session.qo100.snapshot == nil || session.status == nil { try? await Task.sleep(nanoseconds: 200_000_000) }
            tap(f)
        }
        #endif
    }

    /// The signal the receiver is on: marked on the spectrum, and its label
    /// kept when the top of the band is too crowded for all of them.
    private var receiving: Int? {
        guard let q = session.qo100.snapshot, let st = session.status else { return nil }
        return q.signals.firstIndex { QO100.tuned(to: $0, receivers: st.receivers).contains(model.watching) }
    }

    private func tap(_ f: Double) {
        guard let q = session.qo100.snapshot else { return }
        if let i = q.signal(atMHz: f) {
            session.tuneQO100(receiver: model.watching, frequencyMHz: q.signals[i].freqMHz,
                              symbolRateKs: q.signals[i].tuneSymbolRateKs)
        } else if session.canControl {
            let t = QO100.freeTune(atMHz: f, currentRate: session.status?.receiver(model.watching)?.symbolRateKs)
            offer = FreeTune(frequencyMHz: t.frequencyMHz, symbolRateKs: t.symbolRateKs)
        } else {
            session.notice = session.pinSet ? "View only: enter the PIN to control this OpenHamShack." :
                "View only: set a remote PIN on the box's touchscreen (Settings → Security) to allow control."
        }
    }
}

/// QO-100's tab: the spectrum (unless BoxView has it above), the receiver
/// it tunes, the signals found as a list to tap, the beacon and the band
/// plan's channels at the receiver's rate, and the credit. Watching needs
/// nothing; tuning needs the PIN, as everywhere else.
struct QO100Panel: View {
    let session: BoxSession
    /// false where the spectrum is drawn above the panel.
    var showSpectrum = true
    var compact = false
    @Environment(AppModel.self) private var model

    var body: some View {
        let q = session.qo100.snapshot
        VStack(alignment: .leading, spacing: 18) {
            if showSpectrum { QO100Spectrum(session: session, compact: compact) }
            if let st = session.status, let r = st.receiver(model.watching) {
                receiverPicker(st)
                receiver(r)
                if !session.canControl { ViewOnlyNote(session: session, what: "tune") }
                signals(q, st)
                Group {
                    channels(q, r)
                    if QO100.onQO100(r) { rates(r) }
                }
                .disabled(!session.canControl)
            } else {
                Text(session.status == nil ? "Waiting for the box…" : "Receiver \(model.watching) is not on this box.")
                    .foregroundStyle(.secondary)
            }
            Text(QO100.credit(q)).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.top, 4)
    }

    @ViewBuilder private func receiverPicker(_ st: BoxStatus) -> some View {
        let rs = st.receivers.filter(\.enabled)
        if rs.count > 1 {
            @Bindable var model = model
            Picker("Receiver", selection: $model.watching) {
                ForEach(rs) { Text("Receiver \($0.id)").tag($0.id) }
            }
            .pickerStyle(.segmented)
        }
    }

    private func receiver(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Receiver \(r.id) · \(r.tuner)").font(.caption).foregroundStyle(.secondary)
            if QO100.onQO100(r) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(Tuning.mhz(r.frequencyMHz)).font(.title2.weight(.semibold)).monospacedDigit()
                    Text("MHz · \(Tuning.ks(r.symbolRateKs)) kS · LNB \(Tuning.ks(r.offsetMHz))")
                        .font(.subheadline).foregroundStyle(.secondary).monospacedDigit()
                }
                Text(r.line).font(.headline).foregroundStyle(r.locked ? .green : .primary)
            } else {
                Text("On \(Tuning.mhz(r.frequencyMHz)) MHz, not QO-100 (no LNB). Tap a signal or a channel to receive QO-100.")
                    .font(.subheadline)
            }
        }
    }

    @ViewBuilder private func signals(_ q: QO100Snapshot?, _ st: BoxStatus) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("On the transponder now")
            if let q, !q.signals.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(zip(session.qo100.signalIDs, q.signals)), id: \.0) { _, s in
                        signalRow(s, tunedOn: QO100.tuned(to: s, receivers: st.receivers))
                        if s != q.signals.last { Divider().padding(.leading, 44) }
                    }
                }
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
            } else {
                Text(q == nil ? "Waiting for the spectrum…" : "No signals found.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private func signalRow(_ s: QO100Signal, tunedOn: [Int]) -> some View {
        let here = tunedOn.contains(model.watching)
        return Button {
            session.tuneQO100(receiver: model.watching, frequencyMHz: s.freqMHz, symbolRateKs: s.tuneSymbolRateKs)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: here ? "checkmark.circle.fill" : s.beacon ? "dot.radiowaves.up.forward" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(here ? .green : s.overPower ? .red : .accentColor)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(verbatim: Tuning.downlink(s.freqMHz, locale: Locale.current.identifier)).font(.body.weight(.semibold)).monospacedDigit()
                        if s.beacon { Text("Beacon").font(.caption.weight(.semibold)).foregroundStyle(.secondary) }
                        if s.overPower { Text("over-power").font(.caption.weight(.semibold)).foregroundStyle(.red) }
                    }
                    Text(detail(s, tunedOn: tunedOn)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                }
                Spacer(minLength: 4)
                LevelBar(db: s.levelDb).frame(width: compact ? 44 : 64, height: 6)
                Text(String(format: "%.1f dB", s.levelDb)).font(.caption).monospacedDigit().foregroundStyle(.secondary)
                    .frame(width: 52, alignment: .trailing)
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(session.canControl ? "Tunes the receiver to it" : "View only")
    }

    private func detail(_ s: QO100Signal, tunedOn: [Int]) -> String {
        var parts = ["\(Tuning.ks(s.tuneSymbolRateKs)) kS"]
        if s.symbolRateKs != s.tuneSymbolRateKs { parts.append("measured \(Tuning.ks(s.symbolRateKs)) kS") }
        let st = session.status
        if (st?.receivers.filter(\.enabled).count ?? 1) > 1, !tunedOn.isEmpty {
            parts.append("Rx " + tunedOn.map(String.init).joined(separator: ", "))
        } else if !tunedOn.isEmpty {
            parts.append("receiving")
        }
        return parts.joined(separator: " · ")
    }

    /// The beacon, then the band plan's channels at the receiver's rate (or
    /// 333 kS's, at a rate the plan does not use), from the box's own table.
    private func channels(_ q: QO100Snapshot?, _ r: Receiver) -> some View {
        let (rate, chans) = q?.channels(forRate: QO100.onQO100(r) ? r.symbolRateKs : nil) ?? (333, [])
        let items = [QO100.beaconMHz] + chans.map(\.downlinkMHz)
        let on = QO100.onQO100(r) ? r.frequencyMHz : nil
        return VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Beacon and band plan, \(rate) kS")
            ChipRow(items: items, selected: { abs($0 - (on ?? 0)) < 0.001 },
                    title: { $0 == QO100.beaconMHz ? "Beacon \(Tuning.mhz($0))" : String(format: "%.2f", $0) }) { f in
                session.tuneQO100(receiver: r.id, frequencyMHz: f,
                                  symbolRateKs: f == QO100.beaconMHz ? QO100.beaconRateKs : Double(rate))
            }
        }
    }

    private func rates(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Symbol rate (QO-100 rates), kS/s")
            ChipRow(items: Tuning.rates(viaLNB: true, current: r.symbolRateKs),
                    selected: { $0 == r.symbolRateKs }, title: { Tuning.ks($0) }) { sr in
                session.tune(receiver: r.id, symbolRateKs: sr)
            }
        }
    }
}

/// A signal's strength over the noise, 0 to 15 dB, as a bar.
private struct LevelBar: View {
    let db: Double
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color(uiColor: .tertiarySystemFill))
                Capsule().fill(db >= 10 ? Color.orange : Color.accentColor)
                    .frame(width: g.size.width * CGFloat(max(0.04, min(1, db / 15))))
            }
        }
    }
}
