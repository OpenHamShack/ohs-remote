import AVFoundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import HaishinKit
import UIKit

/// Moving colour bars with a clock, and a 1 kHz tone, fed to the mixer as
/// if they came from a camera and microphone.
///
/// For checking the link to the box without a camera pointed at anything,
/// and the only source in the simulator, where there is no camera - which
/// is how the SRT path to the box's Stream source was tested before any
/// iPad was at hand.
final class TestPattern: @unchecked Sendable {
    private let mixer: MediaMixer
    private var task: Task<Void, Never>?
    private let width = 1280, height = 720, fps = 30
    private let sampleRate = 48_000.0
    private var phase = 0.0
    private var format: CMVideoFormatDescription?
    private var pool: CVPixelBufferPool?

    init(mixer: MediaMixer) {
        self.mixer = mixer
    }

    func start() {
        task?.cancel()
        task = Task.detached(priority: .userInitiated) { [weak self] in
            var frame = 0
            let started = CACurrentMediaTime()
            while !Task.isCancelled {
                guard let self else { return }
                if let video = self.videoFrame(frame) { await self.mixer.append(video) }
                if let audio = self.audioChunk(frames: Int(self.sampleRate) / self.fps) {
                    await self.mixer.append(audio, when: AVAudioTime(hostTime: mach_absolute_time()))
                }
                frame += 1
                // Paced against the start, so sleep's slop does not add up.
                let next = started + Double(frame) / Double(self.fps)
                let wait = next - CACurrentMediaTime()
                if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private func videoFrame(_ n: Int) -> CMSampleBuffer? {
        if pool == nil {
            let attrs: [String: Any] = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                                        kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
                                        kCVPixelBufferIOSurfacePropertiesKey as String: [:]]
            CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
        }
        guard let pool else { return nil }
        var pb: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
        guard let pb else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: width, height: height, bitsPerComponent: 8,
                               bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
            draw(ctx, frame: n)
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        if format == nil {
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pb, formatDescriptionOut: &format)
        }
        guard let format else { return nil }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: CMTimeScale(fps)),
                                        presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                                        decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pb, formatDescription: format,
                                                 sampleTiming: &timing, sampleBufferOut: &sb)
        return sb
    }

    private func draw(_ ctx: CGContext, frame n: Int) {
        // EBU bars, sliding one bar width every 4 s, so a frozen picture at
        // the box is obvious.
        let bars: [(CGFloat, CGFloat, CGFloat)] = [(1, 1, 1), (1, 1, 0), (0, 1, 1), (0, 1, 0), (1, 0, 1), (1, 0, 0), (0, 0, 1), (0, 0, 0)]
        let w = CGFloat(width), h = CGFloat(height), bw = w / CGFloat(bars.count)
        let shift = CGFloat(n % (fps * 4)) / CGFloat(fps * 4) * bw
        for i in -1..<bars.count {
            let c = bars[(i + bars.count) % bars.count]
            ctx.setFillColor(red: c.0 * 0.75, green: c.1 * 0.75, blue: c.2 * 0.75, alpha: 1)
            ctx.fill(CGRect(x: CGFloat(i) * bw + shift, y: h * 0.25, width: bw + 1, height: h * 0.75))
        }
        ctx.setFillColor(red: 0.1, green: 0.1, blue: 0.12, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h * 0.25))
        // Text, drawn through UIKit into the same context (flipped: CG's
        // origin is bottom left).
        UIGraphicsPushContext(ctx)
        ctx.saveGState()
        ctx.translateBy(x: 0, y: h)
        ctx.scaleBy(x: 1, y: -1)
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        let text = "OpenHamShack Remote  \(f.string(from: Date())).\(String(format: "%02d", n % fps))"
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.monospacedDigitSystemFont(ofSize: 64, weight: .bold),
                                                    .foregroundColor: UIColor.white]
        (text as NSString).draw(at: CGPoint(x: 48, y: h * 0.75 + 40), withAttributes: attrs)
        ctx.restoreGState()
        UIGraphicsPopContext()
    }

    private func audioChunk(frames: Int) -> AVAudioPCMBuffer? {
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(frames)),
              let data = buf.floatChannelData?[0] else { return nil }
        buf.frameLength = AVAudioFrameCount(frames)
        let step = 2 * Double.pi * 1000 / sampleRate
        for i in 0..<frames {
            data[i] = Float(sin(phase) * 0.25)
            phase += step
        }
        phase = phase.truncatingRemainder(dividingBy: 2 * Double.pi)
        return buf
    }
}
