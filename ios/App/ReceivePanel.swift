import OpenHamShackKit
import SwiftUI

/// Tuning the receiver whose picture is shown: band, frequency, symbol
/// rate - the console's Receive section and receiver window, with the same
/// chips. Watching is open to anyone; tuning needs the PIN.
struct ReceivePanel: View {
    let session: BoxSession
    /// To the QO-100 tab, where the satellite is tuned from its spectrum.
    var openQO100: (() -> Void)?
    @Environment(AppModel.self) private var model
    @State private var typed = ""
    @FocusState private var typing: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let st = session.status, let r = st.receiver(model.watching) {
                receiverPicker(st)
                state(r)
                if !session.canControl {
                    ViewOnlyNote(session: session, what: "tune")
                }
                Group {
                    frequency(r)
                    bands(r)
                    rates(r)
                    if st.selectedReceiver != r.id {
                        Button("Show receiver \(r.id) on the box's own screen") { session.showOnBox(receiver: r.id) }
                            .buttonStyle(.bordered)
                    }
                }
                .disabled(!session.canControl)
                if let openQO100 {
                    // Outside the group above: watching the spectrum needs no PIN.
                    Button(action: openQO100) {
                        Label("QO-100: what is on the transponder now", systemImage: "chart.xyaxis.line")
                    }
                    .buttonStyle(.bordered)
                }
            } else {
                Text(session.status == nil ? "Waiting for the box…" : "Receiver \(model.watching) is not on this box.")
                    .foregroundStyle(.secondary)
            }
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

    private func state(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Receiver \(r.id) · \(r.tuner)").font(.caption).foregroundStyle(.secondary)
            Text(r.line).font(.headline)
        }
    }

    private func frequency(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Frequency")
            HStack(alignment: .firstTextBaseline) {
                Text(Tuning.mhz(r.frequencyMHz)).font(.system(size: 40, weight: .semibold, design: .rounded)).monospacedDigit()
                Text("MHz").foregroundStyle(.secondary)
                if r.offsetMHz > 0 {
                    Text("LNB \(Tuning.ks(r.offsetMHz))").font(.caption).foregroundStyle(.secondary)
                }
            }
            HStack {
                TextField("Type a frequency in MHz", text: $typed)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .focused($typing)
                    .onSubmit(tuneTyped)
                Button("Tune", action: tuneTyped)
                    .buttonStyle(.borderedProminent)
                    .disabled(Double(typed.replacingOccurrences(of: ",", with: ".")) == nil)
            }
        }
    }

    private func tuneTyped() {
        guard let f = Double(typed.replacingOccurrences(of: ",", with: ".")), f > 0 else { return }
        session.tune(receiver: model.watching, toMHz: f)
        typed = ""
        typing = false
    }

    private func bands(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Band")
            ChipRow(items: Tuning.terrestrialBands, selected: { Tuning.band(for: r.frequencyMHz) == $0 }, title: { b in
                "\(b.name) \(Tuning.mhz(b.frequencyMHz))"
            }) { b in
                session.tune(receiver: r.id, toMHz: b.frequencyMHz)
            }
        }
    }

    private func rates(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(r.offsetMHz > 0 ? "Symbol rate (QO-100 rates), kS/s" : "Symbol rate, kS/s")
            ChipRow(items: Tuning.rates(viaLNB: r.offsetMHz > 0, current: r.symbolRateKs),
                    selected: { $0 == r.symbolRateKs }, title: { Tuning.ks($0) }) { sr in
                session.tune(receiver: r.id, symbolRateKs: sr)
            }
        }
    }
}

struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
    }
}

/// A wrapping row of choice chips, as the console's.
struct ChipRow<Item: Hashable>: View {
    let items: [Item]
    let selected: (Item) -> Bool
    let title: (Item) -> String
    let action: (Item) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { item in
                let on = selected(item)
                Button { action(item) } label: {
                    Text(title(item)).font(.subheadline.weight(on ? .semibold : .regular)).monospacedDigit()
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(on ? Color.accentColor : Color(uiColor: .tertiarySystemFill), in: Capsule())
                        .foregroundStyle(on ? .white : .primary)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Lays children out left to right, wrapping to a new line when full.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                y += line + spacing
                x = 0
                line = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, size.height)
        }
        return CGSize(width: min(widest, width), height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                y += line + spacing
                x = bounds.minX
                line = 0
            }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

/// Why a control is greyed: this iPad is view-only here, and how to change that.
struct ViewOnlyNote: View {
    let session: BoxSession
    let what: String
    @State private var showPIN = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.fill")
            VStack(alignment: .leading, spacing: 6) {
                Text(session.pinSet ? "View only: enter the box's remote PIN to \(what)."
                     : "View only: set a remote PIN on the box's touchscreen (Settings › Security) to \(what) from here.")
                    .font(.subheadline)
                if session.pinSet {
                    Button("Enter PIN") { showPIN = true }.buttonStyle(.bordered)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
        .sheet(isPresented: $showPIN) { PINSheet(session: session) }
    }
}
