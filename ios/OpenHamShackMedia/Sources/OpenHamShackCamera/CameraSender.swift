import AVFoundation
import Foundation
import HaishinKit
import OpenHamShackKit
import Observation
import SRTHaishinKit
import SwiftUI
import VideoToolbox

/// The iPad's camera and microphone, sent to the box's Stream source over
/// SRT - what Larix Broadcaster does when an operator scans the box's QR
/// code, done inside the app.
///
/// SRT caller to the box (the listener), H.264 + AAC in MPEG-TS, at the
/// picture the box asks for in /api/stream/larix (1280x720, 30 fps,
/// 2.5 Mbit/s): the box re-encodes for the air whatever arrives, so this is
/// a sensible default rather than a demand. HaishinKit (BSD-3-Clause) does
/// the capture, encoding and SRT, through its libsrt build (MPL-2.0).
///
/// The address is the one the app reaches the box on, never one the box
/// reports: over a tailnet or through NAT those are wrong. A host name is
/// resolved here, once per connection, because libsrt's own lookup is a
/// blocking gethostbyname without a timeout.
///
/// Kept connected while wanted: a dropped connection (Wi-Fi blip, the
/// box's listener restarting after a settings change) is retried every two
/// seconds until the operator stops it.
@MainActor
@Observable
public final class CameraSender {
    public enum Phase: Equatable {
        case off
        case connecting
        case sending
        case retrying(String)
        case failed(String)
    }

    public enum Source: String, CaseIterable, Identifiable, Sendable {
        case back, front
        /// Moving colour bars and a 1 kHz tone, made here: a link test
        /// without pointing a camera at anything - and the only source
        /// there is in the simulator, which has no camera.
        case pattern
        public var id: String { rawValue }
        public var words: String {
            switch self {
            case .back: return "Back camera"
            case .front: return "Front camera"
            case .pattern: return "Test pattern"
            }
        }
    }

    public private(set) var phase: Phase = .off
    public private(set) var source: Source = .back
    public private(set) var kbps: Int = 0
    public private(set) var target: String = ""
    public var microphoneOn = true {
        didSet { Task { await applyMute() } }
    }

    public static var cameraAvailable: Bool {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil
            || AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil
    }

    let mixer = MediaMixer()
    private var connection: SRTConnection?
    private var stream: SRTStream?
    private var supervisor: Task<Void, Never>?
    private var mixerRunning = false
    private var pattern: TestPattern?
    private var wanted = false

    public init() {
        if !Self.cameraAvailable { source = .pattern }
    }

    /// Show the camera (or pattern) in the preview, without sending.
    public func startPreview() async {
        guard !mixerRunning else { return }
        mixerRunning = true
        await attach(source)
        await mixer.startRunning()
    }

    public func stopPreview() async {
        guard !wanted else { return }
        await detachAll()
        await mixer.stopRunning()
        mixerRunning = false
    }

    public func select(_ s: Source) async {
        guard s != source else { return }
        source = s
        if mixerRunning { await attach(s) }
    }

    /// Start sending to this box's Stream source; the target from
    /// /api/stream/larix. Keeps trying until stop().
    public func start(address: BoxAddress, target t: StreamTarget) {
        stopSending(keepPreview: true)
        guard t.proto == "srt" else {
            phase = .failed("The box's Stream source is set to \(t.proto.uppercased()). Set it to SRT (Settings › Picture › Stream on the box).")
            return
        }
        if t.secretSet && t.passphrase == nil {
            phase = .failed("The box's stream has a passphrase, and it is only given with the PIN. Sign in first.")
            return
        }
        wanted = true
        target = "\(address.urlHost):\(t.port)"
        phase = .connecting
        supervisor = Task { [weak self] in
            await self?.run(address: address, target: t)
        }
    }

    public func stop() {
        stopSending(keepPreview: true)
        phase = .off
    }

    private func stopSending(keepPreview: Bool) {
        wanted = false
        supervisor?.cancel()
        supervisor = nil
        let c = connection, s = stream
        connection = nil
        stream = nil
        kbps = 0
        Task {
            if let s { await self.mixer.removeOutput(s); await s.close() }
            if let c { await c.close() }
        }
    }

    // MARK: - Sending

    private func run(address: BoxAddress, target t: StreamTarget) async {
        if !mixerRunning { await startPreview() }
        var lastError = ""
        while !Task.isCancelled, wanted {
            do {
                let url = try await Self.srtURL(address: address, target: t)
                let c = SRTConnection()
                let s = SRTStream(connection: c)
                try await s.setVideoSettings(VideoCodecSettings(
                    videoSize: CGSize(width: t.width, height: t.height), bitRate: t.kbps * 1000,
                    profileLevel: kVTProfileLevel_H264_Main_AutoLevel as String, maxKeyFrameIntervalDuration: 1,
                    expectedFrameRate: Double(t.fps)))
                try await s.setAudioSettings(AudioCodecSettings(bitRate: 96_000))
                await mixer.addOutput(s)
                try await c.connect(url)
                guard wanted, !Task.isCancelled else {
                    await mixer.removeOutput(s)
                    await c.close()
                    return
                }
                connection = c
                stream = s
                await s.publish()
                phase = .sending
                lastError = ""
                // Watch it: libsrt notices a dead peer within its idle
                // timeout and HaishinKit then closes the connection.
                while !Task.isCancelled, wanted, await c.connected {
                    if let perf = await c.performanceData {
                        kbps = Int(perf.mbpsSendRate * 1000)
                    }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                await mixer.removeOutput(s)
                await s.close()
                await c.close()
                if connection === c { connection = nil; stream = nil }
                lastError = "The connection to the box ended"
            } catch {
                lastError = Self.words(error)
            }
            guard wanted, !Task.isCancelled else { return }
            kbps = 0
            phase = .retrying(lastError)
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
    }

    /// srt://<address>:<port>?mode=caller&latency=<ms>[&passphrase=...]
    /// libsrt's latency option is milliseconds (ffmpeg's is microseconds;
    /// the box converts its own).
    static func srtURL(address: BoxAddress, target t: StreamTarget) async throws -> URL {
        let ip = try await resolveIPv4(address.host)
        var c = URLComponents()
        c.scheme = "srt"
        c.host = ip
        c.port = t.port
        var q = [URLQueryItem(name: "mode", value: "caller"), URLQueryItem(name: "latency", value: String(t.latencyMs))]
        if let p = t.passphrase { q.append(URLQueryItem(name: "passphrase", value: p)) }
        c.queryItems = q
        guard let url = c.url else { throw OpenHamShackError.badResponse("Could not make the SRT address") }
        return url
    }

    /// HaishinKit's SRT socket is IPv4 (sockaddr_in) only.
    static func resolveIPv4(_ host: String) async throws -> String {
        var addr = in_addr()
        if inet_pton(AF_INET, host, &addr) == 1 { return host }
        if host.contains(":") {
            throw OpenHamShackError.network("Sending the camera needs the box's IPv4 address; this one is IPv6")
        }
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global().async {
                var hints = addrinfo(ai_flags: 0, ai_family: AF_INET, ai_socktype: SOCK_DGRAM, ai_protocol: 0,
                                     ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
                var res: UnsafeMutablePointer<addrinfo>?
                guard getaddrinfo(host, nil, &hints, &res) == 0, let first = res, let sa = first.pointee.ai_addr else {
                    cont.resume(throwing: OpenHamShackError.network("Could not find \(host) to send the camera to"))
                    return
                }
                defer { freeaddrinfo(res) }
                let sin = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                var a = sin.sin_addr
                inet_ntop(AF_INET, &a, &buf, socklen_t(INET_ADDRSTRLEN))
                cont.resume(returning: String(cString: buf))
            }
        }
    }

    static func words(_ error: Error) -> String {
        if let e = error as? SRTConnection.Error {
            switch e {
            case .failedToConnect(let reason):
                let r = "\(reason)".lowercased()
                if r.contains("badsecret") || r.contains("unsecure") {
                    return "The box turned the camera away: wrong or missing passphrase"
                }
                return "The box did not accept the camera (\(reason)). Is its picture source set to Stream?"
            case .unsupportedUri:
                return "Could not make the SRT address"
            case .invalidState:
                return "No answer from the box's Stream source. Is its picture source set to Stream?"
            }
        }
        return (error as? LocalizedError)?.errorDescription ?? "\(error)"
    }

    // MARK: - Sources

    private func attach(_ s: Source) async {
        await detachAll()
        switch s {
        case .back, .front:
            let pos: AVCaptureDevice.Position = s == .front ? .front : .back
            let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: pos)
            try? await mixer.attachVideo(cam, track: 0) { unit in
                unit.isVideoMirrored = s == .front
            }
            if microphoneOn { try? await mixer.attachAudio(AVCaptureDevice.default(for: .audio)) }
            await mixer.setVideoOrientation(.landscapeRight)
        case .pattern:
            let p = TestPattern(mixer: mixer)
            pattern = p
            p.start()
        }
    }

    private func detachAll() async {
        pattern?.stop()
        pattern = nil
        try? await mixer.attachVideo(nil, track: 0)
        try? await mixer.attachAudio(nil)
    }

    private func applyMute() async {
        var settings = await mixer.audioMixerSettings
        var track = settings.tracks[0] ?? .init()
        track.isMuted = !microphoneOn
        settings.tracks[0] = track
        await mixer.setAudioMixerSettings(settings)
    }
}

/// The preview of what is being sent.
public struct CameraPreview: View {
    let sender: CameraSender

    public init(sender: CameraSender) {
        self.sender = sender
    }

    public var body: some View {
        MTHKViewRepresentable(previewSource: Source(mixer: sender.mixer), videoGravity: .resizeAspect)
    }

    struct Source: MTHKViewRepresentable.PreviewSource {
        let mixer: MediaMixer
        func connect(to view: MTHKView) {
            Task { await mixer.addOutput(view) }
        }
    }
}
