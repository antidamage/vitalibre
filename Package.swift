// swift-tools-version:5.9
import PackageDescription

// `Core` holds the pure signal-processing and geometry logic. The app target
// compiles the same files directly (see VitaLibre.xcodeproj), so nothing in
// Core may import UIKit or SwiftUI. This package exists so `swift test` can run
// the logic on any Mac without signing or a simulator.
let package = Package(
    name: "VitaLibreCore",
    platforms: [.macOS(.v13), .iOS(.v17)],
    targets: [
        .target(name: "VitaLibreCore", path: "Core"),
        .testTarget(
            name: "VitaLibreCoreTests",
            dependencies: ["VitaLibreCore"],
            path: "Tests/VitaLibreCoreTests"
        ),
    ]
)
