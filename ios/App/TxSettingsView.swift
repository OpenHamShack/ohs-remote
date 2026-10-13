import OpenHamShackKit
import SwiftUI

/// The transmit settings - frequency, symbol rate, mode, modulation, FEC,
/// power - set through POST /api/tx/tune under the console's rules, with
/// the choices GET /api/tx/options gives.
///
/// Every change goes to the box at once and the box's answer is what is
/// shown: a refusal (400) saved nothing and its sentence appears under the
/// controls; on air, what waits for the next key-up is said (only power
/// changes the carrier at once). A frequency outside the licence is saved,
/// as the console saves it, and the reason shows beside TRANSMIT
/// (txBlocked). View-only: the same values, read-only.
struct TxSettingsView: View {
    let session: BoxSession
    @State private var typed = ""
    @FocusState private var typing: Bool

    var body: some View {
        if let st = session.status {
            let cfg = st.config
            let opts = session.txOptions
            VStack(alignment: .leading, spacing: 16) {
                frequency(cfg, opts)
                if let opts {
                    rates(cfg, opts)
                    modes(cfg, opts)
                    power(cfg, opts)
                } else {
                    Text("\(Tuning.ks(cfg.symbolRateKs)) kS · \(cfg.modeSummary)\(cfg.gainDb.map { String(format: " · %.0f dB", $0) } ?? "")")
                    Text("This box does not offer transmit settings to apps (it needs OpenHamShack 0.9.17 or later): change them on the box.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let m = session.txTuneMessage {
                    HStack(alignment: .top) {
                        Image(systemName: m.refused ? "xmark.octagon.fill" : "clock.arrow.circlepath")
                        Text(m.text).frame(maxWidth: .infinity, alignment: .leading)
                        Button { session.clearTxTuneMessage() } label: { Image(systemName: "xmark") }
                            .buttonStyle(.plain).accessibilityLabel("Dismiss")
                    }
                    .font(.subheadline)
                    .padding(10)
                    .background((m.refused ? Color.red : Color.blue).opacity(0.25), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .disabled(!session.canControl)
        }
    }

    private func frequency(_ cfg: BoxConfig, _ opts: TxOptions?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Transmit frequency")
            HStack(alignment: .firstTextBaseline) {
                Text(Tuning.mhz(cfg.frequencyMHz)).font(.system(size: 34, weight: .semibold, design: .rounded)).monospacedDigit()
                Text("MHz").foregroundStyle(.secondary)
                if opts?.isQO100(cfg.frequencyMHz) == true {
                    Text("QO-100 uplink").foregroundStyle(.secondary)
                } else if let b = Tuning.band(for: cfg.frequencyMHz) {
                    Text(b.name).foregroundStyle(.secondary)
                }
            }
            if session.canControl {
                HStack {
                    TextField("Type a frequency in MHz", text: $typed)
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                        .focused($typing)
                        .onSubmit(setTyped)
                    Button("Set", action: setTyped)
                        .buttonStyle(.borderedProminent)
                        .disabled(number(typed) == nil)
                }
            }
            if let presets = opts?.presets, !presets.isEmpty {
                ChipRow(items: presets, selected: { abs($0.frequencyMHz - (cfg.frequencyMHz ?? 0)) < 0.0005 },
                        title: { "\($0.label) \(Tuning.mhz($0.frequencyMHz))" }) { p in
                    session.txTune(["frequencyMHz": .number(p.frequencyMHz)])
                }
            }
        }
    }

    private func number(_ s: String) -> Double? {
        guard let f = Double(s.replacingOccurrences(of: ",", with: ".")), f > 0 else { return nil }
        return f
    }

    private func setTyped() {
        guard let f = number(typed) else { return }
        session.txTune(["frequencyMHz": .number(f)])
        typed = ""
        typing = false
    }

    private func rates(_ cfg: BoxConfig, _ opts: TxOptions) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(opts.isQO100(cfg.frequencyMHz) ? "Symbol rate (QO-100), kS/s" : "Symbol rate, kS/s")
            ChipRow(items: opts.rates(for: cfg.frequencyMHz, current: cfg.symbolRateKs),
                    selected: { $0 == cfg.symbolRateKs }, title: { Tuning.ks($0) }) { sr in
                session.txTune(["symbolRateKs": .number(sr)])
            }
        }
    }

    private func modes(_ cfg: BoxConfig, _ opts: TxOptions) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Mode, modulation and FEC")
            ChipRow(items: opts.modes, selected: { $0 == cfg.mode }, title: { $0 == "dvbs" ? "DVB-S" : "DVB-S2" }) { m in
                // The box moves the modulation and FEC to ones the mode has.
                session.txTune(["mode": .string(m)])
            }
            ChipRow(items: opts.constellations[cfg.mode] ?? [], selected: { $0 == cfg.constellation },
                    title: { $0.uppercased() }) { c in
                session.txTune(["constellation": .string(c)])
            }
            ChipRow(items: opts.fecs(mode: cfg.mode, constellation: cfg.constellation), selected: { $0 == cfg.fec },
                    title: { $0 }) { f in
                session.txTune(["fec": .string(f)])
            }
        }
    }

    private func power(_ cfg: BoxConfig, _ opts: TxOptions) -> some View {
        let g = cfg.gainDb ?? opts.gainMin
        return VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Power")
            HStack(spacing: 12) {
                Text(String(format: "%.0f dB", g)).font(.title3.weight(.semibold)).monospacedDigit()
                    .frame(minWidth: 70, alignment: .leading)
                Text(g >= opts.gainMax ? "full power" : "below full").foregroundStyle(.secondary)
                Spacer()
                Button { session.txTune(["gainDb": .number(max(opts.gainMin, g - opts.gainStep))]) } label: {
                    Image(systemName: "minus").frame(width: 28, height: 22)
                }
                .disabled(g <= opts.gainMin)
                Button { session.txTune(["gainDb": .number(min(opts.gainMax, g + opts.gainStep))]) } label: {
                    Image(systemName: "plus").frame(width: 28, height: 22)
                }
                .disabled(g >= opts.gainMax)
            }
            .buttonStyle(.bordered)
            Text(session.status?.onAir == true ? "Power changes the transmission at once." : "0 dB is the radio's full output.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Why TRANSMIT is greyed: the box's own reason (no callsign, outside the
/// licence's bands, a PTT relay problem), right beside the button.
struct TxBlockedBox: View {
    let reason: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "nosign").font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text("Can't transmit").font(.headline)
                Text(reason).font(.subheadline)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.orange, lineWidth: 2))
        .foregroundStyle(.white)
    }
}
