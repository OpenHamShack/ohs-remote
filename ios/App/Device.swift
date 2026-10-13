import UIKit

/// The words for this device in sentences that name it: "this iPhone" or
/// "this iPad". The app was written for the iPad first and said "this iPad"
/// everywhere, which read wrongly on a phone - and the remote id sentence
/// and the ON AIR bar's "from this ..." are what an operator checks to know
/// which device holds the transmitter.
@MainActor
enum Device {
    static var this: String { "this \(UIDevice.current.model)" }
    static var This: String { "This \(UIDevice.current.model)" }
    static var isPhone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
}
