import Foundation
import Observation
import SwiftUI
import UIKit
#if os(tvOS)
import TVVLCKit
#else
import MobileVLCKit
#endif

/// The received picture: libVLC playing the box's /api/rx/stream.ts.
///
/// libVLC rather than AVPlayer because AVPlayer does not play a raw MPEG-TS
/// over HTTP at all (only HLS), and because a DATV signal on the edge of
/// lock delivers a transport stream with holes in it, which libVLC's
/// demuxer and decoders ride through as the box's own mpv does.
///
/// What this adds to libVLC is keeping the picture up with nobody
/// attending to it:
///
///  - Small network caching (a setting, 250 ms by default) so the picture
///    is near the box's own; the box already smooths the clock jumps after
///    a dropout (TsRetimer) so the player does not need a deep buffer.
///  - Reconnect whenever the connection ends or errors - the box drops a
///    client that falls 2 MB behind and closes on its own restarts - after
///    1 s, backing off to 5 s while it keeps failing.
///  - A watchdog on bytes read while a picture is coming: 6 s of nothing
///    then means a dead connection (Wi-Fi gone without a FIN, a box that
///    lost power) though libVLC still says "playing". Idle, the box sends
///    only ten null packets a second, too little to judge by (see tick());
///    the session's reachability covers that case. No picture alone is
///    never a reason to reconnect: that is just no signal.
@MainActor
@Observable
public final class RxPlayer: NSObject {
    public enum Phase: Equatable {
        case stopped
        case connecting
        /// Bytes arriving, no picture decoded yet (no signal, or waiting
        /// for a keyframe).
        case waiting
        case playing
        case reconnecting(String)
    }

    public private(set) var phase: Phase = .stopped
    public private(set) var url: URL?
    public private(set) var videoSize: CGSize = .zero
    public private(set) var inputKbps: Double = 0
    public private(set) var lostPictures = 0
    public var muted = false { didSet { player.audio?.isMuted = muted } }

    public var cachingMs: Int
    public var hardwareDecoding: Bool

    /// The view libVLC draws into. Owned here, not by SwiftUI, so a view
    /// rebuilt by a layout change does not restart the stream.
    public let view: UIView = {
        let v = UIView()
        v.backgroundColor = .black
        v.isUserInteractionEnabled = false
        return v
    }()

    private let player: VLCMediaPlayer
    private var watchdog: Timer?
    private var reconnectTask: Task<Void, Never>?
    private var wanted = false
    private var failures = 0
    private var lastRead = 0
    private var lastReadChange = Date()
    private var lastDisplayed = 0
    private var lastPictureAt: Date?

    public init(cachingMs: Int = 250, hardwareDecoding: Bool = true) {
        self.cachingMs = cachingMs
        self.hardwareDecoding = hardwareDecoding
        // --no-osd: no title or "buffering" text drawn over the picture.
        // --clock-jitter=0: show frames as they come rather than holding
        // them to smooth a jittery PCR, which costs latency. --clock-synchro=0
        // would cut more, but gives up lip sync; not used.
        // --stats: the counters the watchdog reads.
        player = VLCMediaPlayer(options: ["--no-osd", "--clock-jitter=0", "--stats"])
        super.init()
        player.delegate = self
        player.drawable = view
    }

    /// Play this URL (a receiver's stream). The same URL again is a no-op
    /// while it plays.
    public func play(_ url: URL) {
        if wanted, self.url == url, phase != .stopped { return }
        self.url = url
        wanted = true
        failures = 0
        start()
    }

    public func stop() {
        wanted = false
        reconnectTask?.cancel()
        reconnectTask = nil
        watchdog?.invalidate()
        watchdog = nil
        player.stop()
        phase = .stopped
        videoSize = .zero
    }

    /// Open the stream again unless a picture is playing: the box has just
    /// come back after being unreachable, and a connection that died
    /// without closing while idle would otherwise wait for ever.
    public func reopen() {
        guard wanted, phase != .playing else { return }
        failures = 0
        start()
    }

    /// Start again from now (a settings change, or the operator asked).
    public func restart() {
        guard wanted else { return }
        failures = 0
        start()
    }

    private func start() {
        guard let url else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        player.stop()
        let media = VLCMedia(url: url)
        let ms = max(50, min(cachingMs, 5000))
        media.addOption(":network-caching=\(ms)")
        media.addOption(":live-caching=\(ms)")
        // The stream is always MPEG-TS: skip probing for anything else.
        media.addOption(":demux=ts")
        // Keep decoding through damage rather than waiting for a clean
        // keyframe: a marginal signal's picture with blocks in it is more
        // use to an operator than a frozen one.
        media.addOption(":avcodec-skip-frame=0")
        media.addOption(":avcodec-skip-idct=0")
        if !hardwareDecoding { media.addOption(":avcodec-hw=none") }
        player.media = media
        player.audio?.isMuted = muted
        phase = .connecting
        lastRead = 0
        lastReadChange = Date()
        lastDisplayed = 0
        lastPictureAt = nil
        player.play()
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    private func tick() {
        guard wanted, let media = player.media else { return }
        let stats = media.statistics
        let read = Int(stats.readBytes)
        if read != lastRead {
            lastRead = read
            lastReadChange = Date()
            failures = 0
        }
        inputKbps = Double(stats.inputBitrate) * 8000
        lostPictures = Int(stats.lostPictures)
        let shown = Int(stats.displayedPictures)
        if shown != lastDisplayed {
            lastDisplayed = shown
            lastPictureAt = Date()
        }
        videoSize = player.videoSize
        if case .reconnecting = phase { return }
        // Silence counts only while a picture was coming. With no signal
        // the box sends ten null packets a second, which libVLC's HTTP
        // access reads in large blocks, so readBytes can sit still for many
        // seconds on a healthy idle connection (the Android app's libVLC
        // reopened a no-signal stream every few seconds for ever until this
        // was learnt). A connection that dies while idle is caught by the
        // session instead: when the box comes back after being unreachable
        // it calls reopen().
        if let at = lastPictureAt, Date().timeIntervalSince(at) < 10, Date().timeIntervalSince(lastReadChange) > 6 {
            reconnect("No data from the box for 6 s")
            return
        }
        if let at = lastPictureAt, Date().timeIntervalSince(at) < 2 {
            phase = .playing
        } else if read > 0 {
            phase = .waiting
        }
    }

    private func reconnect(_ why: String) {
        guard wanted else { return }
        watchdog?.invalidate()
        watchdog = nil
        player.stop()
        failures += 1
        phase = .reconnecting(why)
        let delay = min(5.0, Double(failures))
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self, self.wanted else { return }
            self.start()
        }
    }

    fileprivate func stateChanged(_ state: VLCMediaPlayerState) {
        guard wanted else { return }
        // Our own stop() before a reconnect reports .stopped too.
        if case .reconnecting = phase { return }
        switch state {
        case .ended:
            reconnect("The box closed the stream")
        case .error:
            reconnect("Could not open the stream")
        default:
            // Not .stopped: our own stop() during a reopen posts it late,
            // after the new media has begun, and with no picture nothing
            // follows to mask it (seen on Android's libVLC). Only the end of
            // the stream and an error mean the box closed it now.
            break
        }
    }
}

extension RxPlayer: VLCMediaPlayerDelegate {
    nonisolated public func mediaPlayerStateChanged(_ aNotification: Notification) {
        guard let p = aNotification.object as? VLCMediaPlayer else { return }
        let state = p.state
        Task { @MainActor in self.stateChanged(state) }
    }
}

/// The player's picture as a SwiftUI view.
public struct RxPlayerView: UIViewRepresentable {
    let player: RxPlayer

    public init(player: RxPlayer) {
        self.player = player
    }

    public func makeUIView(context: Context) -> UIView {
        let host = UIView()
        host.backgroundColor = .black
        attach(player.view, to: host)
        return host
    }

    public func updateUIView(_ host: UIView, context: Context) {
        if player.view.superview !== host { attach(player.view, to: host) }
    }

    private func attach(_ v: UIView, to host: UIView) {
        v.removeFromSuperview()
        v.frame = host.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(v)
    }
}
