// swift-tools-version:5.10
// The platform-neutral half of OpenHamShack Remote: the console API, the status
// model, the PTT lease, saved boxes and Bonjour discovery. No UI and no
// third-party code, so it builds and tests on a Mac with `swift test`,
// without a simulator. The iPad and Apple TV apps both use it.
import PackageDescription

let package = Package(
    name: "OpenHamShackKit",
    platforms: [.iOS(.v17), .tvOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "OpenHamShackKit", targets: ["OpenHamShackKit"]),
    ],
    targets: [
        .target(name: "OpenHamShackKit"),
        .testTarget(name: "OpenHamShackKitTests", dependencies: ["OpenHamShackKit"], resources: [.copy("Fixtures")]),
    ]
)
