import OpenHamShackKit
import OpenHamShackPlayer
import Observation
import SwiftUI

/// OpenHamShack on the Apple TV: the received picture full screen, receive only.
///
/// No transmit and no camera here, by design: a TV across the room is no
/// place to hold a transmitter's dead-man, and has no camera. What it does:
/// pick a box, show its picture, click for the status overlay, up/down to
/// step through the box's receivers and (with the PIN) a few receive
/// presets, and a Tune panel (TVTunePanel) for any band, rate or frequency.
@main
struct TVApp: App {
    @State private var model = TVModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            TVRootView().environment(model)
        }
        .onChange(of: scenePhase) { _, phase in model.scenePhase(phase) }
    }
}

@MainActor
@Observable
final class TVModel {
    let store = Store.shared
    private(set) var boxes: [SavedBox] = []
    private(set) var session: BoxSession?
    let player: RxPlayer
    var watching = 1
    private var active = true

    init() {
        let s = Store.shared.player
        player = RxPlayer(cachingMs: s.cachingMs, hardwareDecoding: s.hardwareDecoding)
        boxes = store.boxes
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "list") { return }   // -list YES: start at the box list
        // As the iPad's DebugLaunch: -openBox <address> opens a box at launch.
        // -pin <pin> signs in first; -tune YES opens the tuning panel, and
        // -nudge <presses> -step <MHz> stand in for presses on its frequency
        // row (the simulator's remote cannot be driven from the command line).
        if let raw = UserDefaults.standard.string(forKey: "openBox"), let a = try? BoxAddress.parse(raw) {
            let box = store.remember(name: "", address: a)
            if let pin = UserDefaults.standard.string(forKey: "pin") {
                Task {
                    let token = try? await OpenHamShackClient(address: a).login(pin: pin)
                    store.setSession(token, for: a)
                    open(box)
                }
            } else {
                open(box)
            }
            return
        }
        #endif
        if let id = store.lastBoxID, let box = boxes.first(where: { $0.id == id }) { open(box) }
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
            play()
        }
    }

    func close() {
        session?.stop(reason: "closed")
        player.stop()
        session = nil
        store.lastBoxID = nil
    }

    func forget(_ box: SavedBox) {
        if session?.box.id == box.id { close() }
        store.forget(box)
        reloadBoxes()
    }

    func play() {
        guard let s = session, active else { return }
        player.play(s.client.streamURL(receiver: watching))
    }

    func scenePhase(_ phase: ScenePhase) {
        if phase == .active {
            guard !active else { return }
            active = true
            session?.start()
            play()
        } else if phase == .background {
            active = false
            session?.stop(reason: "the app went to the background")
            player.stop()
        }
    }
}

struct TVRootView: View {
    @Environment(TVModel.self) private var model

    var body: some View {
        if let s = model.session {
            TVWatchView(session: s).id(s.box.id)
        } else {
            TVBoxList()
        }
    }
}
