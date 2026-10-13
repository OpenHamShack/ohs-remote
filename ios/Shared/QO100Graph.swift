import CoreGraphics
import OpenHamShackKit
import SwiftUI

/// QO-100's wideband transponder as BATC's monitor shows it, drawn from the
/// box's /api/qo100: the spectrum with each signal the box found boxed and
/// labelled, the waterfall of the last three minutes under it, and a green
/// line where the receiver is tuned. The iPad's, the iPhone's and the Apple
/// TV's QO-100 screens all draw it with this one view.
///
/// Drawn on a dark ground on every device and in either appearance, as a
/// spectrum display is: the light-mode grouped background washed the
/// waterfall's blues out to nothing.
///
/// The drawing follows the console's drawSpectrum (www/js/qo100.js): the
/// beacon's segment shaded, the band plan's edges at 10492.5 and 10497
/// dashed, the vertical scale from 2 dB under the floor to 12 dB or the
/// strongest signal, labels on up to three rows so neighbouring stations'
/// do not run into one line.
struct QO100Graph: View {
    let monitor: QO100Monitor
    /// The receiver's frequency, while it is on QO-100.
    var receiveMHz: Double?
    /// Index into the snapshot's signals to mark (the TV's focused row).
    var highlighted: Int?
    /// The Apple TV, read from across a room.
    var large = false
    var spectrumHeight: CGFloat = 190
    var waterfallHeight: CGFloat = 110
    /// A tap at a frequency (MHz). nil: not tappable (the TV).
    var onTap: ((Double) -> Void)?

    static let ground = Color(red: 0.043, green: 0.063, blue: 0.125)

    private var lo: Double { monitor.snapshot?.startMHz ?? QO100.lowMHz }
    private var hi: Double { monitor.snapshot?.stopMHz ?? QO100.highMHz }

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                spectrum.frame(height: spectrumHeight)
                if waterfallHeight > 0 {
                    waterfall.frame(height: waterfallHeight).clipped()
                }
            }
            .contentShape(Rectangle())
            #if os(iOS)
            .gesture(SpatialTapGesture().onEnded { v in
                guard let onTap, geo.size.width > 0 else { return }
                onTap(lo + Double(v.location.x / geo.size.width) * (hi - lo))
            })
            #endif
        }
        .frame(height: spectrumHeight + waterfallHeight)
        .background(Self.ground)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("QO-100 wideband spectrum")
        .accessibilityValue(accessibilityWords)
    }

    private var accessibilityWords: String {
        let sigs = monitor.snapshot?.signals ?? []
        return sigs.isEmpty ? "No signals" : sigs.map { $0.beacon ? "Beacon" : $0.title }.joined(separator: ", ")
    }

    // MARK: - Spectrum

    private var spectrum: some View {
        Canvas { ctx, size in
            let q = monitor.snapshot
            let S = q?.spectrum ?? [], sigs = q?.signals ?? []
            let labelFont: Font = large ? .system(size: 24, weight: .bold) : .system(size: 11, weight: .semibold)
            let rowH: CGFloat = large ? 30 : 14
            let T: CGFloat = rowH * 3 + 4, B: CGFloat = large ? 32 : 16
            let top = max(12, (S.max() ?? 0) + 1), bot = -2.0
            let narrow = size.width < 600
            let x = { (f: Double) -> CGFloat in CGFloat((f - lo) / (hi - lo)) * size.width }
            let y = { (v: Double) -> CGFloat in T + CGFloat((top - v) / (top - bot)) * (size.height - T - B) }
            let base = size.height - B

            // The beacon's segment, and the band plan's two edges.
            ctx.fill(Path(CGRect(x: 0, y: T, width: x(10492.5), height: base - T)), with: .color(.white.opacity(0.06)))
            for f in [10492.5, 10497.0] {
                var p = Path()
                p.move(to: CGPoint(x: x(f), y: T))
                p.addLine(to: CGPoint(x: x(f), y: base))
                ctx.stroke(p, with: .color(.white.opacity(0.3)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
            var f = lo.rounded(.up)
            while f <= hi {
                ctx.draw(Text(String(Int(f))).font(large ? .system(size: 22) : .system(size: 10)).foregroundStyle(.white.opacity(0.55)),
                         at: CGPoint(x: min(max(x(f), large ? 34 : 16), size.width - (large ? 34 : 16)), y: base + B / 2))
                f += 1
            }

            if !S.isEmpty {
                var p = Path()
                p.move(to: CGPoint(x: 0, y: base))
                for (i, v) in S.enumerated() {
                    p.addLine(to: CGPoint(x: x(lo + (Double(i) + 0.5) / Double(S.count) * (hi - lo)), y: y(v)))
                }
                p.addLine(to: CGPoint(x: size.width, y: base))
                p.closeSubpath()
                ctx.fill(p, with: .color(Color(red: 0.84, green: 0.89, blue: 0.97).opacity(0.35)))
                ctx.stroke(p, with: .color(Color(red: 0.55, green: 0.72, blue: 1.0)), lineWidth: large ? 2 : 1.2)
            }

            if let f = receiveMHz, f >= lo, f <= hi {
                var p = Path()
                p.move(to: CGPoint(x: x(f), y: 0))
                p.addLine(to: CGPoint(x: x(f), y: base))
                ctx.stroke(p, with: .color(.green), lineWidth: large ? 4 : 2)
            }

            // Signals: a dashed box over each, and its label on the lowest
            // of three rows where it clears the labels already placed.
            var placed: [(CGFloat, CGFloat, Int)] = []
            // The marked one placed first, so it has the lowest row and the
            // crowding costs its neighbours' labels, never its own.
            let order = sigs.indices.sorted { a, b in (a == highlighted ? 0 : 1, a) < (b == highlighted ? 0 : 1, b) }
            for i in order {
                let s = sigs[i]
                let x0 = x(s.lowMHz), x1 = x(s.highMHz), yt = min(y(s.levelDb), base - 2)
                let colour: Color = s.overPower ? .red : .white
                let box = CGRect(x: x0, y: yt, width: max(3, x1 - x0), height: base - yt)
                if i == highlighted {
                    ctx.fill(Path(box.insetBy(dx: -3, dy: 0)), with: .color(.yellow.opacity(0.35)))
                }
                ctx.stroke(Path(box), with: .color(colour.opacity(0.9)),
                           style: StrokeStyle(lineWidth: i == highlighted ? (large ? 4 : 2) : 1, dash: i == highlighted ? [] : [3, 3]))
                // A phone's 9 MHz across 350 points has no room for the rate
                // too: the frequency alone, the list below says the rest.
                let words = narrow && !s.beacon && !s.overPower ? Tuning.mhz(s.freqMHz) : s.graphLabel
                let label = ctx.resolve(Text(words).font(labelFont)
                    .foregroundStyle(i == highlighted ? Color.yellow : colour))
                let w = label.measure(in: size).width
                let half = w / 2 + 3
                let cx = min(size.width - half, max(half, (x0 + x1) / 2))
                var row = 0
                while row < 3 && placed.contains(where: { $0.2 == row && cx - half < $0.1 && cx + half > $0.0 }) { row += 1 }
                // No row free: no label rather than one printed over another
                // (the console's third row took everything left and was
                // unreadable where stations crowd the top of the band).
                if row == 3 { continue }
                placed.append((cx - half, cx + half, row))
                // Fixed rows across the top rather than each label over its own
                // signal's peak: peaks of different heights put "row 0" at
                // different heights, and the rows then overlapped anyway.
                let ty = T - rowH / 2 - 2 - CGFloat(row) * rowH
                ctx.draw(label, at: CGPoint(x: cx, y: ty))
            }
        }
    }

    // MARK: - Waterfall

    private var waterfall: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let img = Self.image(monitor.waterfall) {
                    Image(decorative: img, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .frame(width: geo.size.width, height: geo.size.height)
                }
                if let f = receiveMHz, f >= lo, f <= hi {
                    Rectangle().fill(.green.opacity(0.8))
                        .frame(width: large ? 3 : 1.5, height: geo.size.height)
                        .offset(x: CGFloat((f - lo) / (hi - lo)) * geo.size.width)
                }
            }
        }
    }

    static func image(_ buffer: QO100WaterfallBuffer) -> CGImage? {
        guard let (w, h, bytes) = buffer.rgba(),
              let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
