import OpenHamShackKit
import OpenHamShackPlayer
import SwiftUI

/// One box: its picture, what it is receiving, and the controls.
///
/// On an iPad in landscape the picture fills the left and the controls sit
/// in a column on the right, so the picture is never covered by a control;
/// in portrait the picture is on top. The transmitter's state is a
/// full-width bar above both whenever there is anything to say.
///
/// An iPhone has its own two layouts, chosen by size class rather than by
/// device so that an iPad in a narrow Split View gets the phone's:
///
/// - Upright (compact width): the picture on top with a smaller overlay,
///   the panels scrolling below, and on the Transmit panel the transmit
///   button pinned under the scroll so it never needs finding. On the
///   Camera panel the received picture is hidden: the camera preview is the
///   picture that matters there, and two pictures left no room for either.
///   A full-screen button gives the picture the whole screen, as Android's.
/// - On its side (compact height): the picture fills the screen, with the
///   ON AIR bar over it, and the panels open in a column on the right when
///   asked for. The iPad's side-by-side layout was tried here first and left
///   a picture 200 points tall under a top bar on a 393-point screen.
struct BoxView: View {
    let session: BoxSession
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @State private var panel: Panel = .receive
    @State private var showPIN = false
    @State private var showSettings = false
    @State private var showOverlay = true
    @State private var fullScreen = false
    @State private var showControls = false
    @Environment(\.scenePhase) private var scenePhase

    enum Panel: String, CaseIterable, Identifiable {
        case receive = "Receive", qo100 = "QO-100", transmit = "Transmit", camera = "Camera"
        var id: String { rawValue }
    }

    var body: some View {
        Group {
            if vSize == .compact {
                phoneLandscape
            } else if hSize == .compact {
                if fullScreen {
                    FullPicture(session: session, showOverlay: $showOverlay, showPIN: $showPIN,
                                showSettings: $showSettings, showControls: nil, exit: { fullScreen = false })
                } else {
                    phonePortrait
                }
            } else {
                regular
            }
        }
        .background(Color.black)
        .sheet(isPresented: $showPIN) { PINSheet(session: session) }
        .sheet(isPresented: $showSettings) { SettingsSheet() }
        // The contract (REMOTE-CONTRACT.md 5): keep the screen on while a
        // box is open. Without it a phone's auto-lock, 30 s by default, ends
        // a transmission the operator is still watching, because a lock
        // unkeys. Only auto-lock is affected: a deliberate lock and leaving
        // the app still go through AppModel.scenePhase and the session's
        // lock observer and let go of the transmitter exactly as before.
        // Set only while the app is active, and cleared on leaving the box,
        // so the box list and a backgrounded app sleep as normal.
        .onAppear { keepAwake(scenePhase == .active) }
        .onDisappear { keepAwake(false) }
        .onChange(of: scenePhase) { _, phase in keepAwake(phase == .active) }
        // The QO-100 spectrum is polled only while its tab is showing and
        // the app is in front: the polls are what hold the box's connection
        // to BATC open, so they must stop when nobody is looking.
        .onAppear { followQO100() }
        .onDisappear { session.qo100.stop() }
        .onChange(of: panel) { followQO100() }
        .onChange(of: scenePhase) { followQO100() }
        #if DEBUG
        .onAppear {
            if let p = DebugLaunch.panel, let panel = p == "qo100" ? .qo100 : Panel(rawValue: p.capitalized) {
                self.panel = panel
                showControls = true
            }
            if DebugLaunch.fullScreen { fullScreen = true }
            if DebugLaunch.sheet == "pin" { showPIN = true }
            if DebugLaunch.sheet == "settings" { showSettings = true }
        }
        #endif
    }

    private func followQO100() {
        if panel == .qo100, scenePhase == .active { session.qo100.start() } else { session.qo100.stop() }
    }

    private func keepAwake(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }

    /// The iPad's layout, unchanged from before the iPhone layouts existed.
    private var regular: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height && geo.size.width >= 700
            VStack(spacing: 0) {
                TopBar(session: session, showPIN: $showPIN, showSettings: $showSettings)
                TransmitBanner(session: session)
                if wide {
                    if panel == .qo100 {
                        QO100Spectrum(session: session)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(Color(uiColor: .systemGroupedBackground))
                    }
                    HStack(spacing: 0) {
                        picture
                        Divider()
                        controls(spectrumAbove: panel == .qo100).frame(width: min(420, geo.size.width * 0.38))
                    }
                } else {
                    picture.aspectRatio(16 / 9, contentMode: .fit)
                    Divider()
                    controls()
                }
            }
        }
    }

    private var phonePortrait: some View {
        VStack(spacing: 0) {
            TopBar(session: session, showPIN: $showPIN, showSettings: $showSettings, compact: true)
            TransmitBanner(session: session, compact: true)
            if panel != .camera {
                picture(compact: true, fullScreen: { fullScreen = true })
                    .aspectRatio(16 / 9, contentMode: .fit)
                Divider()
            }
            controls(pinTransmit: true, buttonHeight: 64)
        }
    }

    private var phoneLandscape: some View {
        HStack(spacing: 0) {
            FullPicture(session: session, showOverlay: $showOverlay, showPIN: $showPIN,
                        showSettings: $showSettings, showControls: $showControls, exit: nil)
            if showControls {
                Divider()
                // Wider for QO-100: the spectrum is in the column, and at
                // 340 points a narrow station was too thin to hit.
                controls(pinTransmit: true, buttonHeight: 60)
                    .frame(width: panel == .qo100 ? 470 : 340)
                    .transition(.move(edge: .trailing))
            }
        }
    }

    private var picture: some View { picture(compact: false, fullScreen: nil) }

    private func picture(compact: Bool, fullScreen: (() -> Void)?) -> some View {
        ZStack(alignment: .topLeading) {
            RxPlayerView(player: model.player)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { showOverlay.toggle() } }
            if showOverlay {
                StatusOverlay(receiver: session.status?.receiver(model.watching), player: model.player, compact: compact)
                    .padding(compact ? 8 : 12)
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    PictureButtons(session: session, compact: compact) {
                        if let fullScreen {
                            Button(action: fullScreen) { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                                .accessibilityLabel("Full screen")
                        }
                    }
                }
            }
            .padding(compact ? 8 : 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    /// The panel picker and the panel. On a phone the Transmit panel's
    /// button and time-out sit under the scroll (TransmitPanel.controlsPinned).
    private func controls(pinTransmit: Bool = false, buttonHeight: CGFloat = 84, spectrumAbove: Bool = false) -> some View {
        VStack(spacing: 0) {
            Picker("Panel", selection: $panel) {
                ForEach(Panel.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(pinTransmit ? 8 : 12)
            ScrollView {
                Group {
                    switch panel {
                    case .receive: ReceivePanel(session: session) { panel = .qo100 }
                    case .qo100: QO100Panel(session: session, showSpectrum: !spectrumAbove, compact: pinTransmit)
                    case .transmit: TransmitPanel(session: session, controlsPinned: pinTransmit)
                    case .camera: CameraPanel(session: session)
                    }
                }
                .padding(.horizontal, pinTransmit ? 12 : 16)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            if pinTransmit, panel == .transmit, session.status != nil {
                Divider()
                TransmitControls(session: session, buttonHeight: buttonHeight)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

/// The picture with nothing around it: an iPhone on its side, or full
/// screen. A tap hides the overlay and the buttons together; the ON AIR bar
/// is never hidden, and the way off air is always one tap on it (the
/// contract's "stopping is one tap... including full screen").
private struct FullPicture: View {
    let session: BoxSession
    @Binding var showOverlay: Bool
    @Binding var showPIN: Bool
    @Binding var showSettings: Bool
    /// nil where there are no panels to open (full screen upright).
    var showControls: Binding<Bool>?
    /// Leave full screen; nil on its side, where turning upright does that.
    var exit: (() -> Void)?
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            RxPlayerView(player: model.player)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { showOverlay.toggle() } }
            VStack(spacing: 0) {
                TransmitBanner(session: session, compact: true)
                ZStack(alignment: .topLeading) {
                    Color.clear
                    if showOverlay {
                        StatusOverlay(receiver: session.status?.receiver(model.watching), player: model.player, compact: true)
                            .allowsHitTesting(false)
                            .transition(.opacity)
                        VStack {
                            Spacer()
                            HStack(alignment: .bottom) {
                                boxButtons
                                Spacer()
                                PictureButtons(session: session, compact: true) {
                                    if let exit {
                                        Button(action: exit) { Image(systemName: "arrow.down.right.and.arrow.up.left") }
                                            .accessibilityLabel("Leave full screen")
                                    }
                                    if let showControls {
                                        Button {
                                            withAnimation(.easeInOut(duration: 0.2)) { showControls.wrappedValue.toggle() }
                                        } label: {
                                            Image(systemName: showControls.wrappedValue ? "sidebar.trailing" : "slider.horizontal.3")
                                        }
                                        .accessibilityLabel(showControls.wrappedValue ? "Hide the controls" : "Show the controls")
                                    }
                                }
                            }
                        }
                        .transition(.opacity)
                    }
                }
                .padding(8)
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
    }

    /// Back to the boxes, the PIN state and settings: the top bar's job,
    /// which has no room here.
    private var boxButtons: some View {
        HStack(spacing: 10) {
            Button { model.close() } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel("Boxes")
            AuthButton(session: session, showPIN: $showPIN, compact: true)
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .accessibilityLabel("Settings")
        }
        .buttonStyle(.bordered)
        .tint(.white)
        .padding(6)
        .background(.black.opacity(0.5), in: Capsule())
    }
}

/// Mute, and which receiver's picture this device shows, and on a phone
/// whatever else belongs on the picture (full screen, the controls).
private struct PictureButtons<Extra: View>: View {
    let session: BoxSession
    var compact = false
    @ViewBuilder var extra: () -> Extra
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        HStack(spacing: 10) {
            if let rs = session.status?.receivers.filter(\.enabled), rs.count > 1 {
                Menu {
                    Picker("Receiver", selection: $model.watching) {
                        ForEach(rs) { r in Text("Receiver \(r.id) · \(r.line)").tag(r.id) }
                    }
                } label: {
                    if compact {
                        Label("Receiver \(model.watching)", systemImage: "rectangle.on.rectangle").labelStyle(.titleOnly)
                    } else {
                        Label("Receiver \(model.watching)", systemImage: "rectangle.on.rectangle")
                    }
                }
            }
            Button {
                model.player.muted.toggle()
            } label: {
                Image(systemName: model.player.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
            }
            .accessibilityLabel(model.player.muted ? "Unmute" : "Mute")
            extra()
        }
        .buttonStyle(.bordered)
        .controlSize(compact ? .small : .regular)
        .tint(.white)
        .padding(compact ? 4 : 6)
        .background(.black.opacity(0.5), in: Capsule())
    }
}

extension PictureButtons where Extra == EmptyView {
    init(session: BoxSession) {
        self.init(session: session, compact: false) { EmptyView() }
    }
}

private struct TopBar: View {
    let session: BoxSession
    @Binding var showPIN: Bool
    @Binding var showSettings: Bool
    /// An iPhone upright: "Boxes" becomes its chevron and the PIN state its
    /// short form, so the box's name is not cut to a few letters.
    var compact = false
    @Environment(AppModel.self) private var model

    var body: some View {
        HStack(spacing: compact ? 8 : 12) {
            Button { model.close() } label: {
                if compact {
                    Image(systemName: "chevron.left").font(.title3.weight(.semibold)).padding(.trailing, 4)
                } else {
                    Label("Boxes", systemImage: "chevron.left")
                }
            }
            .accessibilityLabel("Boxes")
            VStack(alignment: .leading, spacing: 2) {
                Text(session.box.name).font(.headline).lineLimit(1)
                HStack(spacing: 6) {
                    Circle().fill(linkColour).frame(width: 8, height: 8)
                    Text(linkWords).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .layoutPriority(1)
            Spacer(minLength: 0)
            AuthButton(session: session, showPIN: $showPIN, compact: compact)
                // Never wrapped ("Con-trol"): the name gives way instead.
                .fixedSize(horizontal: compact, vertical: false)
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .accessibilityLabel("Settings")
        }
        .padding(.horizontal, compact ? 12 : 16)
        .padding(.vertical, compact ? 6 : 10)
        .background(.bar)
    }

    private var linkColour: Color {
        switch session.link {
        case .online: return .green
        case .connecting: return .yellow
        case .unreachable: return .red
        }
    }

    private var linkWords: String {
        switch session.link {
        case .connecting: return "Connecting to \(session.box.address.display)…"
        case .online:
            let v = session.status?.version ?? ""
            return "\(session.box.address.display) · OpenHamShack \(v)"
        case .unreachable(let since, let reason):
            return "\(reason) (since \(since.formatted(date: .omitted, time: .standard)))"
        }
    }
}

/// Whether this device may control the box, and the way to change that:
/// signed in (with sign-out), view only with the PIN to enter, or view only
/// because the box has no PIN. The top bar's, and the full-screen picture's.
struct AuthButton: View {
    let session: BoxSession
    @Binding var showPIN: Bool
    var compact = false

    var body: some View {
        if session.status == nil {
            EmptyView()
        } else if session.status?.auth.local == true {
            if compact {
                Label("Box screen", systemImage: "display").labelStyle(.iconOnly)
            } else {
                Label("Box screen", systemImage: "display").font(.subheadline)
            }
        } else if session.canControl {
            Menu {
                Button("Sign out on \(Device.this)", role: .destructive) { session.signOut() }
            } label: {
                Label("Control", systemImage: "key.fill").font(.subheadline)
            }
            .tint(.green)
        } else if session.pinSet {
            Button { showPIN = true } label: {
                Label(compact ? "Enter PIN" : "View only · Enter PIN", systemImage: "lock.fill")
            }
            .buttonStyle(.borderedProminent)
        } else {
            Label(compact ? "View only" : "View only: no PIN set on the box", systemImage: "lock.slash")
                .font(.subheadline).foregroundStyle(.secondary)
                .accessibilityLabel("View only: no PIN set on the box")
        }
    }
}

/// Everything about the transmitter that matters right now, full width:
/// on air (and for how long, and the time-out), the lease's health, and why
/// the last transmission was refused or stopped.
struct TransmitBanner: View {
    let session: BoxSession
    /// A phone: the same bar in smaller type, so the on-air line (time,
    /// frequency, rate, time-out, who keyed) fits beside Stop at 375 points.
    var compact = false
    @State private var dismissed = ""

    var body: some View {
        VStack(spacing: 0) {
            if let st = session.status, st.onAir {
                HStack(spacing: compact ? 10 : 14) {
                    Text("ON AIR").font(compact ? .headline.weight(.heavy) : .title3.weight(.heavy))
                    Text(onAirLine(st)).font(compact ? .footnote.weight(.semibold) : .headline).monospacedDigit()
                        .lineLimit(compact ? 3 : 2)
                    Spacer(minLength: compact ? 4 : nil)
                    if session.canControl {
                        Button("Stop") { session.stopTransmitting() }
                            .buttonStyle(.borderedProminent).tint(.white).foregroundStyle(.red)
                    }
                }
                .padding(.horizontal, compact ? 12 : 16).padding(.vertical, compact ? 6 : 10)
                .frame(maxWidth: .infinity)
                .background(Color.red)
                .foregroundStyle(.white)
            }
            if let st = session.status, !st.onAir, !st.txBlocked.isEmpty, session.canControl {
                // Where the eye is, not only beside the button: a frequency
                // just set outside the licence is saved, and this is the
                // first thing that says the next key-up will be refused.
                bar("Can't transmit: \(st.txBlocked)", colour: .orange, key: nil)
            }
            if session.ptt.linkTrouble {
                bar("Heartbeats to the box are failing. If they do not get through within 5 s the box stops transmitting by itself.",
                    colour: .orange, key: nil)
            }
            if let why = message, why != dismissed {
                bar(why, colour: .orange, key: why)
            }
            if let n = session.notice {
                bar(n, colour: .gray, key: nil, close: { session.notice = nil })
            }
        }
    }

    private var message: String? {
        if let e = session.ptt.error, !e.isEmpty { return e }
        if let e = session.status?.txError, !e.isEmpty { return e }
        return nil
    }

    private func bar(_ text: String, colour: Color, key: String?, close: (() -> Void)? = nil) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(compact ? .footnote : .subheadline).frame(maxWidth: .infinity, alignment: .leading)
            if let key {
                Button("OK") { dismissed = key }
            } else if let close {
                Button("OK", action: close)
            }
        }
        .padding(.horizontal, compact ? 12 : 16).padding(.vertical, compact ? 6 : 8)
        .background(colour.opacity(0.85))
        .foregroundStyle(.white)
    }

    private func onAirLine(_ st: BoxStatus) -> String {
        var parts: [String] = []
        if let s = st.onAirSeconds { parts.append(clock(s)) }
        // What is on the air, not what the next key-up will send.
        let air = session.onAirConfig ?? st.config
        parts.append("\(Tuning.mhz(air.frequencyMHz)) MHz")
        parts.append("\(Tuning.ks(air.symbolRateKs)) kS")
        if let t = st.txTimeout { parts.append("time-out in \(clock(t.secondsLeft))") }
        if let l = st.lease {
            parts.append(l.id == session.ptt.remoteID ? "from \(Device.this)" : "from remote \(l.id)")
        }
        return parts.joined(separator: "  ·  ")
    }
}

func clock(_ seconds: Int) -> String {
    let s = max(0, seconds), m = s / 60
    if m >= 60 { return String(format: "%d:%02d:%02d", m / 60, m % 60, s % 60) }
    return String(format: "%d:%02d", m, s % 60)
}
