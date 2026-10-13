import OpenHamShackCamera
import OpenHamShackKit
import OpenHamShackPlayer
import Observation
import SwiftUI

/// The app's state above any one screen: the saved boxes, the box open
/// now, and the player and camera, which outlive the views that show them.
@MainActor
@Observable
final class AppModel {
    let store = Store.shared
    private(set) var boxes: [SavedBox] = []
    private(set) var session: BoxSession?
    /// The receiver whose picture this app shows (not necessarily the one
    /// the box itself shows: that is status.rxView.selected).
    var watching = 1 { didSet { if watching != oldValue { playCurrent() } } }
    let player: RxPlayer
    let camera = CameraSender()
    var settings: PlayerSettings {
        didSet {
            store.player = settings
            player.cachingMs = settings.cachingMs
            player.hardwareDecoding = settings.hardwareDecoding
            player.restart()
        }
    }
    private var active = true

    init() {
        let s = Store.shared.player
        settings = s
        player = RxPlayer(cachingMs: s.cachingMs, hardwareDecoding: s.hardwareDecoding)
        boxes = store.boxes
        #if DEBUG
        if DebugLaunch.apply(to: self) { return }
        if UserDefaults.standard.bool(forKey: "list") { return }   // -list YES: start at the box list
        #endif
        if let id = store.lastBoxID, let box = boxes.first(where: { $0.id == id }) {
            open(box)
        }
    }

    func reloadBoxes() { boxes = store.boxes }

    func open(_ box: SavedBox) {
        close()
        let b = store.remember(name: box.name, address: box.address)
        store.lastBoxID = b.id
        reloadBoxes()
        let s = BoxSession(box: b, store: store)
        s.onReturn = { [weak self] in self?.player.reopen() }
        session = s
        watching = 1
        if active {
            s.start()
            playCurrent()
        }
    }

    /// Back to the box list: let go of the transmitter, stop the camera and
    /// the picture.
    func close() {
        session?.stop(reason: "the box was closed in the app")
        camera.stop()
        player.stop()
        session = nil
        store.lastBoxID = nil
    }

    func forget(_ box: SavedBox) {
        if session?.box.id == box.id { close() }
        store.forget(box)
        reloadBoxes()
    }

    func playCurrent() {
        guard let s = session, active else { return }
        player.play(s.client.streamURL(receiver: watching))
    }

    /// The scene's phase. Anything but active lets go of the transmitter
    /// (the safe direction), stops the camera and the picture; coming back
    /// starts the picture and the poll again, never the transmitter.
    func scenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            guard !active else { return }
            active = true
            session?.start()
            playCurrent()
        default:
            guard active else { return }
            active = false
            let why = phase == .background ? "the app went to the background" : "the app left the screen"
            session?.stop(reason: why)
            camera.stop()
            Task { await camera.stopPreview() }
            player.stop()
        }
    }
}
