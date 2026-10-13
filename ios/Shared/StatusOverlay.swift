import OpenHamShackKit
import OpenHamShackPlayer
import SwiftUI

/// What is being received, over the picture: the iPad's, the iPhone's and
/// the TV's overlay are this one view, so they read the same.
///
/// `compact` is for an iPhone held upright, where the picture is only about
/// 220 points tall and the full overlay covered two thirds of it: the lock
/// line and the tuning line stay (they are what an operator glances at),
/// with MER and margin folded onto the tuning line; the station and the
/// video format go, since they are on the Receive panel just below.
struct StatusOverlay: View {
    let receiver: Receiver?
    let player: RxPlayer
    var large = false
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: large ? 10 : 4) {
            HStack(spacing: 8) {
                Circle().fill(lockColour).frame(width: large ? 18 : 10, height: large ? 18 : 10)
                Text(lockLine).font(large ? .title2.bold() : compact ? .subheadline.bold() : .headline).lineLimit(compact ? 1 : nil)
            }
            if let r = receiver, compact {
                Text(compactLine(r)).font(.caption).monospacedDigit().lineLimit(2)
            } else if let r = receiver {
                Text(tuningLine(r)).font(large ? .title3 : .subheadline).monospacedDigit()
                if r.locked {
                    HStack(spacing: 10) {
                        if let mer = r.mer {
                            Text(String(format: "MER %.1f dB", mer)).monospacedDigit()
                        }
                        if let m = r.marginDb {
                            Text(String(format: "margin %+.1f dB", m)).monospacedDigit()
                                .foregroundStyle(marginColour(r))
                        }
                    }
                    .font(large ? .title3 : .subheadline)
                    let ident = [r.name, r.provider].compactMap { $0 }.joined(separator: " · ")
                    if !ident.isEmpty { Text(ident).font(large ? .title3 : .subheadline) }
                    if let v = r.video { Text(v.summary).font(large ? .body : .caption).foregroundStyle(.secondary) }
                }
            }
            if let p = playerLine {
                Text(p).font(large ? .body : .caption).foregroundStyle(.yellow).lineLimit(compact ? 1 : nil)
            }
        }
        .padding(large ? 24 : compact ? 6 : 10)
        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: large ? 16 : 8))
        .foregroundStyle(.white)
    }

    private var lockLine: String {
        guard let r = receiver else { return "No receiver" }
        if !r.enabled { return "Receiver \(r.id) off" }
        if r.locked { return r.label.isEmpty ? "Locked" : r.label }
        return r.label.isEmpty ? "Not locked" : r.label
    }

    private var lockColour: Color {
        guard let r = receiver, r.enabled else { return .gray }
        return r.locked ? .green : .orange
    }

    private func tuningLine(_ r: Receiver) -> String {
        var parts = ["\(Tuning.mhz(r.frequencyMHz)) MHz", "\(Tuning.ks(r.symbolRateKs)) kS"]
        if let m = r.modcod, r.locked { parts.append(m) }
        if let b = Tuning.band(for: r.frequencyMHz) { parts.insert(b.name, at: 0) }
        return parts.joined(separator: " · ")
    }

    private func compactLine(_ r: Receiver) -> String {
        var parts = ["\(Tuning.mhz(r.frequencyMHz)) MHz", "\(Tuning.ks(r.symbolRateKs)) kS"]
        if r.locked {
            if let m = r.modcod { parts.append(m) }
            if let mer = r.mer { parts.append(String(format: "MER %.1f", mer)) }
            if let m = r.marginDb { parts.append(String(format: "%+.1f dB", m)) }
        }
        return parts.joined(separator: " · ")
    }

    /// rx.band is the margin's colour, not a radio band (REMOTE-API.md).
    private func marginColour(_ r: Receiver) -> Color {
        switch r.marginBand {
        case "good": return .green
        case "marginal": return .yellow
        case "bad": return .red
        default: return .white
        }
    }

    private var playerLine: String? {
        switch player.phase {
        case .stopped: return nil
        case .connecting:
            // Unlocked, the box sends only null packets, which libVLC may
            // not count as read for a long while: say what is true.
            return receiver?.locked == false ? "No picture: waiting for a signal" : "Connecting to the picture…"
        case .waiting: return receiver?.locked == true ? "Waiting for a picture (keyframe)…" : "No picture: waiting for a signal"
        case .playing: return nil
        case .reconnecting(let why): return "\(why): reconnecting…"
        }
    }
}
