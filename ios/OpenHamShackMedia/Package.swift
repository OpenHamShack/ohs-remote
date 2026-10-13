// swift-tools-version:5.10
// The media half of OpenHamShack Remote: the received picture (libVLC) and the
// camera sent to the box (HaishinKit over SRT). Apart from OpenHamShackKit
// because these two need third-party binaries and a device or simulator,
// while OpenHamShackKit tests on a bare Mac.
//
// VLCKit comes from VideoLAN's own prebuilt xcframeworks, fetched and
// checked by ios/tools/fetch-vlckit.sh into Vendor/ (not committed: 140 MB
// of binary). HaishinKit is pinned exactly: its 2.x API has moved between
// minor versions.
import PackageDescription

let package = Package(
    name: "OpenHamShackMedia",
    platforms: [.iOS(.v17), .tvOS(.v17)],
    products: [
        .library(name: "OpenHamShackPlayer", targets: ["OpenHamShackPlayer"]),
        .library(name: "OpenHamShackCamera", targets: ["OpenHamShackCamera"]),
    ],
    dependencies: [
        .package(path: "../OpenHamShackKit"),
        .package(url: "https://github.com/HaishinKit/HaishinKit.swift", exact: "2.2.5"),
    ],
    targets: [
        .binaryTarget(name: "MobileVLCKit", path: "Vendor/MobileVLCKit.xcframework"),
        .binaryTarget(name: "TVVLCKit", path: "Vendor/TVVLCKit.xcframework"),
        .target(name: "OpenHamShackPlayer", dependencies: [
            .product(name: "OpenHamShackKit", package: "OpenHamShackKit"),
            .target(name: "MobileVLCKit", condition: .when(platforms: [.iOS])),
            .target(name: "TVVLCKit", condition: .when(platforms: [.tvOS])),
        ]),
        .target(name: "OpenHamShackCamera", dependencies: [
            .product(name: "OpenHamShackKit", package: "OpenHamShackKit"),
            .product(name: "HaishinKit", package: "HaishinKit.swift"),
            .product(name: "SRTHaishinKit", package: "HaishinKit.swift"),
        ]),
    ]
)
