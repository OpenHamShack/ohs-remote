import OpenHamShackKit
import SwiftUI

/// Transmit: the transmit settings (TxSettingsView), the box's picture
/// source, why a key-up would be refused, and the transmit control itself.
///
/// Keying is a deliberate act - press and hold for a second - so a hand
/// resting on the iPad cannot put a carrier on the air; stopping is one
/// tap, since off is always the safe direction. The console's two taps in
/// four seconds were considered and not used: on glass a press-and-hold
/// with a filling ring is plainer, and is harder to do by accident.
struct TransmitPanel: View {
    let session: BoxSession
    /// On an iPhone the transmit controls (TransmitControls) sit below the
    /// scrolling settings, always in reach, rather than at the foot of them:
    /// on a phone the settings are several screens long, and the hold to key
    /// and the time-out's "Keep transmitting" must not need a scroll.
    var controlsPinned = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let st = session.status {
                if !session.canControl { ViewOnlyNote(session: session, what: "change the transmit settings or transmit") }
                TxSettingsView(session: session)
                source(st)
                if !controlsPinned {
                    TransmitControls(session: session)
                }
                Text("Transmit only where your licence allows. The box checks its licence settings at every key-up; this app adds a 5-second dead-man: if \(Device.this) stops answering, the box stops transmitting.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Waiting for the box…").foregroundStyle(.secondary)
            }
        }
        .padding(.top, 4)
    }

    private func source(_ st: BoxStatus) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("Picture")
            ChipRow(items: PictureSource.allCases, selected: { $0.rawValue == st.config.source }, title: \.words) { s in
                session.setSource(s)
            }
            .disabled(!session.canControl)
            if st.config.source == PictureSource.stream.rawValue {
                StreamStateLine(stream: st.stream)
            }
        }
    }
}

/// The box's Stream source listener, in words.
struct StreamStateLine: View {
    let stream: StreamState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(words, systemImage: icon).foregroundStyle(colour)
            if !stream.note.isEmpty { Text(stream.note).font(.caption).foregroundStyle(.orange) }
            if !stream.problem.isEmpty, stream.state != "receiving" {
                Text(stream.problem).font(.caption).foregroundStyle(.secondary)
            }
        }
        .font(.subheadline)
    }

    private var words: String {
        switch stream.state {
        case "receiving":
            let rate = stream.kbps.map { String(format: " at %.0f kbit/s", $0) } ?? ""
            return "The box is receiving the stream\(rate)"
        case "listening": return "The box is listening for a stream (\(stream.proto.uppercased()) port \(stream.port))"
        case "error": return "Stream source: \(stream.message.isEmpty ? "error" : stream.message)"
        default: return "Stream source off"
        }
    }

    private var icon: String {
        stream.state == "receiving" ? "dot.radiowaves.left.and.right" : stream.state == "error" ? "exclamationmark.triangle" : "ear"
    }

    private var colour: Color {
        stream.state == "receiving" ? .green : stream.state == "error" ? .red : .secondary
    }
}

/// Hold to transmit; tap to stop.
struct TransmitButton: View {
    let session: BoxSession
    var height: CGFloat = 84
    @State private var holding = false
    @State private var progress: CGFloat = 0
    static let holdSeconds = 1.0

    var body: some View {
        let st = session.status
        let onAir = st?.onAir ?? false
        let phase = session.ptt.phase
        Group {
            if onAir || phase != .idle {
                Button {
                    session.stopTransmitting()
                } label: {
                    face(title: phase == .keying && !onAir ? "Starting… tap to cancel" : "ON AIR · tap to stop",
                         colour: .red, icon: "stop.fill")
                }
                .buttonStyle(.plain)
                .disabled(!session.canControl)
            } else {
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(canKey(st) ? Color(red: 0.45, green: 0.08, blue: 0.08) : Color.gray)
                    GeometryReader { g in
                        RoundedRectangle(cornerRadius: 16).fill(Color.red).frame(width: g.size.width * progress)
                    }
                    HStack(spacing: 12) {
                        Image(systemName: "antenna.radiowaves.left.and.right").font(.title2)
                        Text(holdTitle(st)).font(.title2.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
                    }
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(.white)
                }
                .contentShape(RoundedRectangle(cornerRadius: 16))
                    .onLongPressGesture(minimumDuration: Self.holdSeconds, maximumDistance: 40) {
                        holding = false
                        progress = 0
                        session.transmit()
                    } onPressingChanged: { pressing in
                        guard canKey(st) else { return }
                        holding = pressing
                        if pressing {
                            withAnimation(.linear(duration: Self.holdSeconds)) { progress = 1 }
                        } else {
                            withAnimation(.easeOut(duration: 0.15)) { progress = 0 }
                        }
                    }
                    .allowsHitTesting(canKey(st))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityHint("Press and hold for one second to transmit")
            }
        }
        .frame(height: height)
        .sensoryFeedback(.impact(weight: .heavy), trigger: onAir)
    }

    private func canKey(_ st: BoxStatus?) -> Bool {
        guard let st, session.canControl else { return false }
        return st.txBlocked.isEmpty
    }

    private func holdTitle(_ st: BoxStatus?) -> String {
        guard let st else { return "TRANSMIT" }
        if !session.canControl { return "View only" }
        if !st.txBlocked.isEmpty { return "Can't transmit" }
        if session.ptt.check != nil || st.txCheck != nil {
            return (st.txCheck?.kind == "busy") ? "In use · hold to transmit anyway" : "Not checked · hold to transmit anyway"
        }
        return holding ? "Keep holding…" : "Hold to TRANSMIT"
    }

    private func face(title: String, colour: Color, icon: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title2)
            Text(title).font(.title2.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colour, in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.white)
    }
}

/// Why a key-up would be refused, the transmit button, and the time-out:
/// the part of the Transmit panel that is acted on rather than set. Inline
/// at the foot of the panel on an iPad; pinned below it on an iPhone.
struct TransmitControls: View {
    let session: BoxSession
    var buttonHeight: CGFloat = 84

    var body: some View {
        if let st = session.status {
            VStack(alignment: .leading, spacing: 18) {
                if !st.onAir, !st.txBlocked.isEmpty {
                    TxBlockedBox(reason: st.txBlocked)
                }
                TransmitButton(session: session, height: buttonHeight)
                timeout(st)
            }
        }
    }

    @ViewBuilder private func timeout(_ st: BoxStatus) -> some View {
        if st.onAir, let t = st.txTimeout {
            HStack {
                Image(systemName: "timer")
                Text("Time-out in \(clock(t.secondsLeft))").monospacedDigit()
                    .foregroundStyle(t.warning ? .orange : .primary)
                Spacer()
                if t.warning, session.canControl {
                    Button("Keep transmitting") { session.keepTransmitting() }.buttonStyle(.borderedProminent)
                }
            }
            .font(t.warning ? .headline : .body)
        }
    }
}
