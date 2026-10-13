import OpenHamShackKit
import OpenHamShackPlayer
import SwiftUI

/// Choosing a box with the Siri Remote: boxes advertising on the network
/// first (Bonjour, the main way in here - one click, no typing), then the
/// saved ones, then an address typed with the on-screen keyboard for a box
/// Bonjour cannot reach (across a tailnet, or one not yet advertising).
struct TVBoxList: View {
    @Environment(TVModel.self) private var model
    @State private var browser = BoxBrowser()
    @State private var address = ""
    @State private var pin = ""
    @State private var problem: String?
    @State private var busy = false
    @State private var resolving: String?
    /// Focus moves to the first box found on the network when it appears
    /// (Bonjour answers after the list is first drawn, so a default focus
    /// alone lands on the saved boxes), once - never under a moving finger.
    @FocusState private var focus: String?
    @State private var focusedFound = false

    var body: some View {
        NavigationStack {
            List {
                Section("On this network") {
                    if browser.found.isEmpty {
                        Text(browser.problem ?? String(localized: "Looking for OpenHamShack boxes on this network…")).foregroundStyle(.secondary)
                    }
                    ForEach(browser.found) { f in
                        Button { Task { await use(f) } } label: {
                            HStack {
                                Image(systemName: "dot.radiowaves.left.and.right")
                                VStack(alignment: .leading) {
                                    Text(f.name)
                                    if let v = f.version { Text("OpenHamShack \(v)").font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                if resolving == f.name { ProgressView() }
                            }
                        }
                        .focused($focus, equals: "found:" + f.id)
                    }
                }
                Section("Your boxes") {
                    if model.boxes.isEmpty {
                        Text("None yet: choose one found on the network, or type an address below.").foregroundStyle(.secondary)
                    }
                    ForEach(model.boxes) { box in
                        Button { model.open(box) } label: {
                            VStack(alignment: .leading) {
                                Text(box.name)
                                Text(box.address.display).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .contextMenu { Button("Forget", role: .destructive) { model.forget(box) } }
                    }
                }
                Section {
                    TextField("Address, e.g. 198.51.100.40", text: $address)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    SecureField("Remote PIN (optional: only for tuning)", text: $pin)
                    Button(busy ? String(localized: "Connecting…") : String(localized: "Add and watch")) { Task { await add() } }
                        .disabled(address.isEmpty || busy)
                    if let problem { Text(problem).foregroundStyle(.red) }
                } header: { Text("Add a box") } footer: {
                    Text("The address is on the box's own screen, Settings › Network.")
                }
            }
            .navigationTitle("OpenHamShack")
            .onChange(of: browser.found.first?.id) { _, first in
                guard let first, !focusedFound else { return }
                focusedFound = true
                focus = "found:" + first
            }
        }
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
    }

    /// Bonjour is the main way in on the TV: a found box opens at once,
    /// with no typing on the Siri Remote.
    private func use(_ f: BoxBrowser.Found) async {
        resolving = f.name
        defer { resolving = nil }
        do {
            let a = try await BoxBrowser.resolve(f)
            model.open(model.store.remember(name: f.name, address: a))
        } catch {
            problem = (error as? LocalizedError)?.errorDescription ?? "\(error)"
        }
    }

    private func add() async {
        problem = nil
        let addr: BoxAddress
        do { addr = try BoxAddress.parse(address) } catch {
            problem = (error as? LocalizedError)?.errorDescription
            return
        }
        busy = true
        defer { busy = false }
        let client = OpenHamShackClient(address: addr)
        let st: BoxStatus
        do { st = try await client.status() } catch {
            let why = (error as? LocalizedError)?.errorDescription ?? ""
            problem = String(localized: "Could not reach an OpenHamShack at \(addr.display): \(why)")
            return
        }
        if !pin.isEmpty {
            do { model.store.setSession(try await client.login(pin: pin), for: addr) } catch {
                let why = (error as? LocalizedError)?.errorDescription ?? ""
                problem = String(localized: "PIN not accepted: \(why)")
                return
            }
        }
        let name = st.config.callsign.isEmpty ? addr.display : String(localized: "\(st.config.callsign)'s OpenHamShack")
        model.open(model.store.remember(name: name, address: addr))
    }
}

/// The picture, full screen. Select (click) shows or hides the overlay;
/// up/down steps to the next channel; right (with the overlay up) goes to
/// the overlay's Tune button, which opens the tuning panel (TVTunePanel);
/// Menu closes the panel, hides the overlay, or with both gone goes back to
/// the boxes.
///
/// The picture layer and the Tune button are siblings, not one inside the
/// other: tvOS gives the focus to one focusable view at a time, and a
/// focusable picture wrapped around the button kept the button from ever
/// being reached.
struct TVWatchView: View {
    let session: BoxSession
    @Environment(TVModel.self) private var model
    @State private var overlay = true
    @State private var tuning = false
    @State private var qo100 = false
    @State private var channelIndex = 0
    @State private var banner: String?
    @State private var bannerTask: Task<Void, Never>?
    @FocusState private var focus: Focus?

    enum Focus: Hashable { case picture, tune, qo100 }

    var body: some View {
        ZStack(alignment: .topLeading) {
            RxPlayerView(player: model.player).ignoresSafeArea()
            Color.clear
                .contentShape(Rectangle())
                .ignoresSafeArea()
                .focusable(!tuning && !qo100)
                .focused($focus, equals: .picture)
                .onTapGesture { withAnimation { overlay.toggle() } }
                .onPlayPauseCommand { withAnimation { overlay.toggle() } }
                .onMoveCommand { dir in
                    switch dir {
                    case .up: step(-1)
                    case .down: step(1)
                    case .right: if overlay { focus = .tune }
                    default: break
                    }
                }
                .onExitCommand {
                    if overlay { withAnimation { overlay = false } } else { model.close() }
                }
            if overlay {
                VStack(alignment: .leading, spacing: 20) {
                    Text(session.box.name).font(.title3).foregroundStyle(.white.opacity(0.8))
                    StatusOverlay(receiver: session.status?.receiver(model.watching), player: model.player, large: true)
                    if case .unreachable(_, let why) = session.link {
                        Text(why).font(.title3).foregroundStyle(.orange)
                    }
                    if session.status?.onAir == true {
                        Text("The box is transmitting").font(.title3.bold()).padding(12).background(.red, in: Capsule())
                    }
                    HStack(spacing: 30) {
                        Button { openTune() } label: { Label("Tune", systemImage: "dial.medium") }
                            .focused($focus, equals: .tune)
                            .disabled(tuning || qo100)
                            .onExitCommand { focus = .picture }
                        Button { openQO100() } label: { Label("QO-100", systemImage: "chart.xyaxis.line") }
                            .focused($focus, equals: .qo100)
                            .disabled(tuning || qo100)
                            .onExitCommand { focus = .picture }
                    }
                    Text("Up / down: \(channels.count > 1 ? "change channel" : "no other channels")  ·  Right: Tune, QO-100  ·  Menu: hide this")
                        .font(.callout).foregroundStyle(.white.opacity(0.7))
                }
                .padding(60)
                .transition(.opacity)
            }
            if let banner {
                Text(banner).font(.title2.bold()).padding(24)
                    .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 80)
                    .transition(.opacity)
            }
            if tuning {
                TVTunePanel(session: session, close: { closeTune() }, openQO100: { withAnimation { tuning = false }; openQO100() })
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .ignoresSafeArea()
                    .transition(.move(edge: .trailing))
            }
            if qo100 {
                TVQO100Screen(session: session) { closeQO100() }
                    .transition(.opacity)
            }
        }
        .onAppear {
            focus = .picture
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "tune") { tuning = true }
            if UserDefaults.standard.bool(forKey: "qo100") { qo100 = true }
            #endif
        }
    }

    private func openTune() {
        withAnimation { tuning = true }
    }

    private func openQO100() {
        withAnimation { qo100 = true }
    }

    private func closeQO100() {
        withAnimation { qo100 = false }
        focus = overlay ? .qo100 : .picture
    }

    private func closeTune() {
        withAnimation { tuning = false }
        focus = overlay ? .tune : .picture
    }

    private var channels: [Channel] {
        guard let st = session.status else { return [] }
        return Channel.list(status: st, canTune: session.canControl)
    }

    private func step(_ by: Int) {
        let list = channels
        guard !list.isEmpty else { return }
        channelIndex = (channelIndex + by + list.count) % list.count
        let ch = list[channelIndex]
        switch ch.kind {
        case .receiver(let id):
            model.watching = id
            model.play()
        case .preset(let f, let sr, _):
            session.tune(receiver: model.watching, toMHz: f, symbolRateKs: sr)
        }
        show(ch.title)
    }

    private func show(_ text: String) {
        withAnimation { banner = text }
        bannerTask?.cancel()
        bannerTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            withAnimation { banner = nil }
        }
    }
}
