import OpenHamShackKit
import SwiftUI

/// Receive tuning on the Apple TV, over the picture: which receiver, the
/// band presets, the symbol rate, and a frequency - the iPad's Receive
/// panel, rebuilt for a Siri Remote.
///
/// It is a panel down the right of the picture rather than a screen of its
/// own, so the lock and MER can be watched while tuning: the point of
/// nudging a frequency is to see the signal come in.
///
/// The frequency is set two ways. Typed, with the tvOS keyboard, for a
/// frequency known in advance. Or nudged: left/right on the frequency row
/// moves a pending frequency by the chosen step (25 kHz, 250 kHz, 1 MHz)
/// and select sends it - once, not per press, so a run of presses is one
/// tune at the box rather than a queue of them, each restarting the tuner.
///
/// Tuning needs the PIN. Without a session the panel offers the PIN (kept
/// per box in the Keychain, as the iPad does); with no PIN set on the box
/// it says how to set one. Watching never needs it. There is still no
/// transmit and no camera on the TV.
struct TVTunePanel: View {
    let session: BoxSession
    let close: () -> Void
    /// To the QO-100 screen (TVQO100Screen), where the satellite is tuned
    /// from its spectrum.
    var openQO100: (() -> Void)?
    @Environment(TVModel.self) private var model
    @State private var typed = ""
    @State private var step = 0.25
    /// The frequency the nudges have reached and select would send; nil
    /// when it is the receiver's own.
    @State private var pending: Double?
    @FocusState private var focus: Field?

    enum Field: Hashable { case nudge, typed }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Tune").font(.title2.bold())
                if let st = session.status, let r = st.receiver(model.watching) {
                    state(r)
                    if !session.canControl { TVSignIn(session: session, what: "tune") }
                    if let openQO100 {
                        // Open to view-only too: watching needs no PIN.
                        Button(action: openQO100) {
                            Label("QO-100: what is on the transponder", systemImage: "chart.xyaxis.line")
                        }
                    }
                    Group {
                        receivers(st)
                        nudge(r)
                        bands(r)
                        rates(r)
                        typedFrequency(r)
                    }
                    .disabled(!session.canControl)
                    .opacity(session.canControl ? 1 : 0.45)
                } else {
                    Text(session.status == nil ? "Waiting for the box…" : "Receiver \(model.watching) is not on this box.")
                        .foregroundStyle(.secondary)
                }
                Text("Menu: back to the picture").font(.callout).foregroundStyle(.secondary)
            }
            .padding(48)
        }
        .frame(width: 860)
        .frame(maxHeight: .infinity)
        // Dark rather than a material: a material over a test card or a
        // colourful picture took on its colours and the chips' text with it.
        .background(Color.black.opacity(0.82))
        .focusSection()
        .onExitCommand(perform: close)
        .onAppear {
            if session.canControl { focus = .nudge }
        }
        #if DEBUG
        .task {
            // After the first status, which carries the frequency to nudge from.
            while session.status == nil { try? await Task.sleep(nanoseconds: 200_000_000) }
            applyDebug()
        }
        #endif
        .onChange(of: session.canControl) { _, can in if can { focus = .nudge } }
    }

    // MARK: - Parts

    private func state(_ r: Receiver) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Receiver \(r.id)\(r.tuner.isEmpty ? "" : " · \(r.tuner)")").font(.callout).foregroundStyle(.secondary)
            Text("\(Tuning.mhz(r.frequencyMHz)) MHz · \(Tuning.ks(r.symbolRateKs)) kS").font(.title3).monospacedDigit()
            Text(r.line).font(.headline).foregroundStyle(r.locked ? .green : .orange)
        }
    }

    @ViewBuilder private func receivers(_ st: BoxStatus) -> some View {
        let rs = st.receivers.filter(\.enabled)
        if rs.count > 1 {
            section("Receiver") {
                chips(rs.map(\.id), selected: { $0 == model.watching }, title: { "Receiver \($0)" }) { id in
                    pending = nil
                    model.watching = id
                    model.play()
                }
            }
        }
    }

    /// Left/right moves the pending frequency by `step`; select sends it.
    /// Alone on its row so that left/right have nowhere else to take the
    /// focus: they only ever nudge.
    private func nudge(_ r: Receiver) -> some View {
        let shown = pending ?? r.frequencyMHz
        let waiting = pending != nil && pending != r.frequencyMHz
        return section("Frequency") {
            chips(Tuning.nudgeSteps, selected: { $0 == step }, title: stepWords) { step = $0 }
            Button { applyPending(r) } label: {
                HStack(spacing: 24) {
                    Image(systemName: "chevron.left")
                    Text("\(Tuning.mhz(shown)) MHz").font(.title2.bold()).monospacedDigit()
                        .lineLimit(1).fixedSize()
                    Image(systemName: "chevron.right")
                }
                .frame(maxWidth: .infinity)
            }
            .focused($focus, equals: .nudge)
            .onMoveCommand { dir in
                guard let f = shown else { return }
                switch dir {
                case .left: pending = Tuning.nudged(f, step: step, presses: -1)
                case .right: pending = Tuning.nudged(f, step: step, presses: 1)
                default: break
                }
            }
            Text(waiting ? "Select: tune to \(Tuning.mhz(pending)) MHz\(rateWords(r))  ·  Menu: leave it"
                         : "Left / right: \(stepWords(step)) a press, then select")
                .font(.callout).foregroundStyle(waiting ? .yellow : .secondary)
        }
    }

    /// The rate a nudge across into another band will take with it (the
    /// QO-100 beacon's 1500, a QO-100 rate for a terrestrial one, and back),
    /// said before select rather than found afterwards.
    private func rateWords(_ r: Receiver) -> String {
        guard let p = pending else { return "" }
        let o = Tuning.offsetFor(frequencyMHz: p, currentOffset: r.offsetMHz, lnbLO: session.status?.config.qo100LnbMHz ?? 9750)
        guard let sr = Tuning.rate(movingTo: p, offsetMHz: o, current: r.symbolRateKs) else { return "" }
        return " at \(Tuning.ks(sr)) kS"
    }

    private func applyPending(_ r: Receiver) {
        if let p = pending, p != r.frequencyMHz { session.tune(receiver: r.id, toMHz: p) }
        pending = nil
    }

    private func stepWords(_ s: Double) -> String {
        s < 1 ? "\(Int((s * 1000).rounded())) kHz" : "\(Tuning.ks(s)) MHz"
    }

    private func bands(_ r: Receiver) -> some View {
        section("Band") {
            chips(Tuning.terrestrialBands, selected: { Tuning.band(for: r.frequencyMHz) == $0 }, title: { b in
                "\(b.name) \(Tuning.mhz(b.frequencyMHz))"
            }) { b in
                pending = nil
                session.tune(receiver: r.id, toMHz: b.frequencyMHz)
            }
        }
    }

    private func rates(_ r: Receiver) -> some View {
        section(r.offsetMHz > 0 ? "Symbol rate (QO-100 rates), kS/s" : "Symbol rate, kS/s") {
            chips(Tuning.rates(viaLNB: r.offsetMHz > 0, current: r.symbolRateKs),
                  selected: { $0 == r.symbolRateKs }, title: { Tuning.ks($0) }) { sr in
                session.tune(receiver: r.id, symbolRateKs: sr)
            }
        }
    }

    private func typedFrequency(_ r: Receiver) -> some View {
        section("Type a frequency") {
            HStack(spacing: 20) {
                TextField("MHz, e.g. 437.25", text: $typed)
                    .keyboardType(.decimalPad)
                    .focused($focus, equals: .typed)
                    .onSubmit { tuneTyped(r) }
                Button("Tune") { tuneTyped(r) }
                    .disabled(Tuning.parseMHz(typed) == nil)
            }
        }
    }

    private func tuneTyped(_ r: Receiver) {
        guard let f = Tuning.parseMHz(typed) else { return }
        pending = nil
        session.tune(receiver: r.id, toMHz: f)
        typed = ""
    }

    // MARK: - Pieces

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            content()
        }
    }

    /// Choice chips that wrap, as the iPad's ChipRow, with tvOS buttons so
    /// each takes the focus. The chosen one is marked by its tint as well as
    /// a check, since focus also changes how a button looks.
    private func chips<Item: Hashable>(_ items: [Item], selected: @escaping (Item) -> Bool,
                                       title: @escaping (Item) -> String,
                                       action: @escaping (Item) -> Void) -> some View {
        TVFlow(spacing: 16) {
            ForEach(items, id: \.self) { item in
                let on = selected(item)
                Button { action(item) } label: {
                    HStack(spacing: 8) {
                        if on { Image(systemName: "checkmark") }
                        Text(title(item)).fontWeight(on ? .semibold : .regular).monospacedDigit()
                    }
                }
                .tint(on ? .accentColor : nil)
            }
        }
    }

    #if DEBUG
    /// -nudge <presses> -step <MHz>: as that many presses of right (or left,
    /// negative) on the frequency row, for screenshots; -select YES then
    /// presses select, through the same path as the button.
    private func applyDebug() {
        let d = UserDefaults.standard
        if d.object(forKey: "step") != nil { step = d.double(forKey: "step") }
        let n = d.integer(forKey: "nudge")
        if n != 0, let r = session.status?.receiver(model.watching), let f = r.frequencyMHz {
            pending = Tuning.nudged(f, step: step, presses: n)
            if d.bool(forKey: "select") { applyPending(r) }
        }
    }
    #endif
}

/// Lays children out left to right, wrapping when the row is full.
struct TVFlow: Layout {
    var spacing: CGFloat = 16

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width { y += line + spacing; x = 0; line = 0 }
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
            if x > bounds.minX, x + size.width > bounds.maxX { y += line + spacing; x = bounds.minX; line = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

/// The console's view-only words, and on a TV the way out of it: the PIN
/// typed with the remote, once, kept per box (as the iPad keeps it). The
/// Tune panel's and the QO-100 screen's. The PIN field takes the focus
/// when it appears, since signing in is the only thing to do there first.
struct TVSignIn: View {
    let session: BoxSession
    /// What the PIN allows here: "tune".
    let what: String
    /// Take the focus on appearing (the Tune panel, where nothing else can
    /// be done first); not on the QO-100 screen, where watching can.
    var autofocus = true
    @State private var pin = ""
    @State private var signingIn = false
    @State private var pinProblem: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if session.pinSet {
                Label("View only: enter the box's remote PIN to \(what).", systemImage: "lock.fill")
                SecureField("Remote PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .focused($focused)
                    .onSubmit { Task { await go() } }
                Button(signingIn ? "Signing in…" : "Sign in") { Task { await go() } }
                    .disabled(pin.isEmpty || signingIn)
                if let pinProblem { Text(pinProblem).foregroundStyle(.red) }
                Text("The PIN set on the box's touchscreen, Settings › Security. This Apple TV stays signed in to the box until the PIN is changed there.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Label("View only: set a remote PIN on the box's touchscreen (Settings › Security) to \(what) from here.",
                      systemImage: "lock.slash")
            }
        }
        .padding(24)
        .background(.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 16))
        .onAppear { if autofocus, session.pinSet { focused = true } }
    }

    private func go() async {
        signingIn = true
        pinProblem = await session.signIn(pin: pin)
        signingIn = false
        if pinProblem == nil { pin = "" }
    }
}
