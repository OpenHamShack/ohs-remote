#if DEBUG
import OpenHamShackKit
import SwiftUI

/// Launch arguments for driving the app in the simulator without touching
/// it - how the screenshots in the iOS README were taken, against
/// tools/fakebox.py. Debug builds only; a release build ignores them.
///
///     xcrun simctl launch booted com.openhamshack.remote \
///         -openBox shack:18291 -pin 1234 -panel transmit -action key
///
/// -openFound <name> opens one found by Bonjour; -list YES starts at the
/// box list; -openBox opens (and remembers) a box; -pin signs in first; -panel picks
/// the side panel; -action, once the first status is in: "key" keys the
/// transmitter as the hold gesture would, "camera" starts sending.
/// -fullScreen YES opens the picture full screen (an iPhone upright);
/// -orient landscape turns the simulator on its side, since simctl cannot
/// rotate a device and the Simulator app's menu needs a person at the Mac.
/// On a phone on its side, -panel also opens the controls column.
/// -sheet pin or -sheet settings opens that sheet.
@MainActor
enum DebugLaunch {
    static var panel: String? { UserDefaults.standard.string(forKey: "panel") }
    static var fullScreen: Bool { UserDefaults.standard.bool(forKey: "fullScreen") }
    /// -sheet pin | settings: open that sheet over the box screen.
    static var sheet: String? { UserDefaults.standard.string(forKey: "sheet") }
    /// -panel qo100 -qoTap 10499.25: a tap on the QO-100 spectrum there,
    /// once it is in (the same path as a finger: a signal tunes, nothing
    /// asks).
    static var qoTap: Double? {
        UserDefaults.standard.object(forKey: "qoTap") == nil ? nil : UserDefaults.standard.double(forKey: "qoTap")
    }

    static func orient() {
        guard let o = UserDefaults.standard.string(forKey: "orient") else { return }
        let mask: UIInterfaceOrientationMask = o == "landscape" ? .landscapeRight : .portrait
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        }
    }

    static func apply(to model: AppModel) -> Bool {
        let d = UserDefaults.standard
        if let wanted = d.string(forKey: "openFound") {
            // -openFound <name>: open a box found by Bonjour, as a tap would.
            let browser = BoxBrowser()
            browser.start()
            Task {
                for _ in 0..<50 {
                    if let f = browser.found.first(where: { $0.name == wanted }),
                       let a = try? await BoxBrowser.resolve(f) {
                        browser.stop()
                        model.open(model.store.remember(name: f.name, address: a))
                        return
                    }
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
            }
            return true
        }
        guard let raw = d.string(forKey: "openBox"), let addr = try? BoxAddress.parse(raw) else { return false }
        let box = model.store.remember(name: "", address: addr)
        let pin = d.string(forKey: "pin")
        let action = d.string(forKey: "action")
        Task {
            if let pin {
                let token = try? await OpenHamShackClient(address: addr).login(pin: pin)
                model.store.setSession(token, for: addr)
            }
            model.open(box)
            guard let action, let s = model.session else { return }
            while s.status == nil { try? await Task.sleep(nanoseconds: 200_000_000) }
            switch action {
            case "key": s.transmit()
            case "txTune":
                // -action txTune -patch frequencyMHz=146.95,fec=2/3 (not JSON:
                // launch arguments are read as property lists).
                var patch: [String: JSON] = [:]
                for pair in (d.string(forKey: "patch") ?? "").split(separator: ",") {
                    let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
                    guard kv.count == 2 else { continue }
                    patch[kv[0]] = Double(kv[1]).map { .number($0) } ?? .string(kv[1])
                }
                s.txTune(patch)
            case "camera":
                if s.status?.config.source != PictureSource.stream.rawValue { s.setSource(.stream) }
                if let t = try? await s.client.streamTarget() {
                    await model.camera.startPreview()
                    model.camera.start(address: addr, target: t)
                }
            default: break
            }
        }
        return true
    }
}
#endif
